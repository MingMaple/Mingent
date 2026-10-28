import math
import re
import sqlite3

from .decay import age_days, decayed
from .settings import (DEFAULT_LIMIT, DENSE_ADMIT_ABS, DENSE_ADMIT_MAX, DENSE_ADMIT_REL,
                      DENSE_W,
                      GRAPH_FILL, GRAPH_HOPS, HOP_DECAY, MIN_SCORE, RECENCY_TAU_DAYS,
                      REL_KEEP_RATIO, RRF_K, SEAM_CREDIT, W_CH, W_E,
                      W_R, W_REC, W_RRF, W_S, CJK_RE)
from .text import (FUNC_CHARS, clamp01, is_cjk_char, jaccard_bigrams, term_boundary_ok,
                   term_match, term_occurrences, tokenize)
from .vector import embed_texts, unpack_vec, vector_available


def filter_conds(scopes, include_ask=False, include_archived=False, alias="n",
                 include_superseded=False):
    conds = []
    params = []
    if scopes:
        conds.append(alias + ".ns IN (" + ",".join(["?"] * len(scopes)) + ")")
        params.extend(scopes)
    if not include_archived:
        conds.append(alias + ".archived = 0")
    conds.append("COALESCE(" + alias + ".erased, 0) = 0")
    if not include_superseded:
        # G05：被 correct 更正的节点默认不再参与检索（否则旧事实与新更正会一起排序）。
        conds.append("COALESCE(" + alias + ".superseded, 0) = 0")
    conds.append("COALESCE(" + alias + ".disclosure, 'public') <> 'private'")
    if not include_ask:
        conds.append("COALESCE(" + alias + ".disclosure, 'public') <> 'ask'")
    return conds, params


def bm25_search(conn, query, limit, conds, params):
    toks = [t for t in tokenize(query) if re.search(r"[0-9a-z\u4e00-\u9fff]", t)]
    if not toks:
        return []
    match = " OR ".join('"' + t + '"' for t in toks)
    sql = ("SELECT n.id AS id, bm25(nodes_fts) AS s FROM nodes_fts "
           "JOIN nodes n ON n.id = nodes_fts.id WHERE nodes_fts MATCH ?")
    if conds:
        sql += " AND " + " AND ".join(conds)
    sql += " ORDER BY s ASC LIMIT ?"
    rows = conn.execute(sql, [match] + list(params) + [int(limit)]).fetchall()
    return [(r["id"], float(r["s"])) for r in rows]


def vector_search(conn, query, limit, conds, params):
    """稠密通道检索：查询侧加 BGE 指令前缀，与库内向量做余弦（已归一化 ⇒ 点积）。

    10 万条以内直接全量点积（numpy 一次矩阵乘 ≈10–30ms），不需要 ANN。
    没有 numpy 时退回逐条点积（慢但结果一致，保证"缺依赖也能跑"）。
    """
    if not vector_available():
        return []
    qv = embed_texts([query], is_query=True)
    if qv is None or len(qv) == 0:
        return []
    q = qv[0]
    sql = "SELECT v.id AS id, v.vec AS vec FROM vectors v JOIN nodes n ON n.id = v.id"
    if conds:
        sql += " WHERE " + " AND ".join(conds)
    rows = conn.execute(sql, list(params)).fetchall()
    if not rows:
        return []
    try:
        import numpy as np
        q32 = np.asarray(q, dtype=np.float32)
        dim = int(q32.shape[0])
        ids, mats = [], []
        for r in rows:
            blob = r["vec"]
            if len(blob) != dim * 4:
                continue
            ids.append(r["id"])
            mats.append(np.frombuffer(blob, dtype="<f4"))
        if not mats:
            return []
        sims = np.vstack(mats) @ q32
        order = np.argsort(-sims)[:int(limit)]
        return [(ids[int(i)], float(sims[int(i)])) for i in order]
    except Exception:
        scored = []
        for r in rows:
            v = unpack_vec(r["vec"])
            if len(v) != len(q):
                continue
            scored.append((r["id"], sum(a * b for a, b in zip(q, v))))
        scored.sort(key=lambda kv: -kv[1])
        return scored[:int(limit)]


def graph_expand(conn, seeds, hops=GRAPH_HOPS, hop_decay=HOP_DECAY):
    """从种子出发按边权扩散；返回 [(id, score)] 降序。"""
    if not seeds:
        return []
    frontier = dict(seeds)
    seen = set(seeds)
    scores = {}
    for _ in range(int(hops)):
        nxt = {}
        for nid, sc in frontier.items():
            rows = conn.execute(
                "SELECT dst AS nb, weight AS w FROM edges WHERE src = ? "
                "UNION SELECT src AS nb, weight AS w FROM edges WHERE dst = ?",
                (nid, nid)).fetchall()
            for r in rows:
                nb = r["nb"]
                if nb in seen:
                    continue
                val = sc * hop_decay * (0.5 + 0.5 * float(r["w"]))
                if val > nxt.get(nb, 0.0):
                    nxt[nb] = val
        if not nxt:
            break
        seen.update(nxt)
        scores.update(nxt)
        frontier = nxt
    out = sorted(scores.items(), key=lambda kv: -kv[1])
    return out


def norm_channel(pairs):
    """把 [(id, 分数)]（分数越大越好）在通道内归一到 0~1，保留幅度信息。"""
    if not pairs:
        return {}
    vals = [s for _i, s in pairs]
    lo, hi = min(vals), max(vals)
    if hi - lo < 1e-12:
        return dict((i, 1.0) for i, _s in pairs)
    return dict((i, (s - lo) / (hi - lo)) for i, s in pairs)


def rrf_fuse(rank_lists):
    scores = {}
    for lst in rank_lists:
        for i, item in enumerate(lst, 1):
            nid = item[0]
            scores[nid] = scores.get(nid, 0.0) + 1.0 / (RRF_K + i)
    return scores


def fetch_nodes(conn, ids, conds, params):
    if not ids:
        return {}
    ph = ",".join(["?"] * len(ids))
    sql = "SELECT * FROM nodes n WHERE n.id IN (" + ph + ")"
    if conds:
        sql += " AND " + " AND ".join(conds)
    rows = conn.execute(sql, list(ids) + list(params)).fetchall()
    return dict((r["id"], dict(r)) for r in rows)


def pair_clusters(texts, threshold=0.6):
    """把正文两两 Jaccard ≥ threshold 的条目并成同一簇（并查集，单链接）。"""
    n = len(texts)
    parent = list(range(n))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for i in range(n):
        for j in range(i + 1, n):
            if jaccard_bigrams(texts[i], texts[j]) >= threshold:
                ri, rj = find(i), find(j)
                if ri != rj:
                    parent[rj] = ri
    return [find(i) for i in range(n)]


def mmr_select(items, limit, lam=0.7, max_per_cluster=2, cluster_threshold=0.6):
    """G23：截断前做「MMR + 硬簇上限」，把同簇近重复压下去。

    为什么不能只靠软惩罚：同簇条目往往分数本身就高，0.3 的惩罚项压不住，
    实测 5 条同义改写仍会 5/5 占满 top5。因此改为硬约束：
    同一簇最多 max_per_cluster 条；只有当剩余候选不足时才放宽
    （宁可有重复，也不返回条数少于 limit 的残缺结果）。
    相关度＝重排分；相似度＝字符 2-gram Jaccard（向量通道停用时的唯一可用口径）。
    """
    need = int(limit)
    if need <= 0 or not items:
        return []
    texts = [it["node"].get("text") or "" for it in items]
    clusters = pair_clusters(texts, cluster_threshold)
    sizes = {}
    for cl in clusters:
        sizes[cl] = sizes.get(cl, 0) + 1
    if max(sizes.values()) <= max_per_cluster:
        # 没有超限簇：保持原有排序，不做无谓扰动。
        return list(items)[:need]
    remaining = [(items[i], texts[i], clusters[i]) for i in range(len(items))]
    chosen = []
    counts = {}
    while remaining and len(chosen) < need:
        eligible = [m for m in remaining if counts.get(m[2], 0) < max_per_cluster]
        if not eligible:
            # 候选里只剩超限簇：宁可少返回，也不拿近重复凑数。
            break
        best = None
        best_val = None
        for it, text, cluster in eligible:
            penalty = 0.0
            for cit, ctext, _ccl in chosen:
                v = jaccard_bigrams(text, ctext)
                if v > penalty:
                    penalty = v
            val = lam * it["score"] - (1.0 - lam) * penalty
            if best_val is None or val > best_val:
                best_val = val
                best = (it, text, cluster)
        chosen.append(best)
        counts[best[2]] = counts.get(best[2], 0) + 1
        remaining.remove(best)
    return [it for it, _text, _cluster in chosen]


def scope_count(conn, conds, params):
    sql = "SELECT count(*) AS c FROM nodes n WHERE " + (" AND ".join(conds) if conds else "1=1")
    return int(conn.execute(sql, list(params)).fetchone()["c"])


def term_df(conn, term, conds, params):
    """词在全范围内的文档频次（走 FTS，避免全表扫）。"""
    sql = ("SELECT count(*) AS c FROM nodes_fts JOIN nodes n ON n.id = nodes_fts.id "
           "WHERE nodes_fts MATCH ?")
    if conds:
        sql += " AND " + " AND ".join(conds)
    try:
        return int(conn.execute(sql, ['"' + term + '"'] + list(params)).fetchone()["c"])
    except sqlite3.OperationalError:
        return 0


def query_terms(conn, query, conds, params):
    """查询词 + IDF 权重 + 文档频次。纯虚词查询返回 []（= 没有检索意图）。

    去掉纯虚词 bigram：查询「的」「了的」这类词本身不承载信息，放进去只会把
    一堆条目按 recency 排出来。宁可返回 0 条，也不给一份看起来像答案的清单。
    """
    picked, seen = [], set()
    for tok in tokenize(query):
        if tok in seen:
            continue
        if CJK_RE.match(tok):
            if len(tok) == 1 and tok in FUNC_CHARS:
                continue
            if len(tok) >= 2 and all(c in FUNC_CHARS for c in tok):
                continue
        seen.add(tok)
        picked.append(tok)
    if not picked:
        return []
    total = max(1, scope_count(conn, conds, params))
    out = []
    for tok in picked:
        df = max(0, term_df(conn, tok, conds, params))
        if df <= 0:
            # 范围内一条都没有的词（长查询里大量是分词造出来的假 bigram，如
            # 「离线评测用不用」切出的「测用/用不」）：把它算进分母会凭空压低
            # 所有候选的证据，等于让分词噪声决定门槛。直接不参与证据计算。
            continue
        idf = math.log(1.0 + total / float(df))
        out.append({"term": tok, "weight": max(idf, 1e-6), "df": df})
    return out


def _df_cached(conn, term, conds, params, cache):
    if term not in cache:
        cache[term] = max(0, term_df(conn, term, conds, params))
    return cache[term]


def seam_occurrence(conn, text, term, df, conds, params, cache):
    """这次出现是不是「两个词的接缝」——不用词典，用库里的词频反推。

    判据（三条件同时成立才算接缝）：
      1. 两侧都有重叠的邻接 bigram（不是标点/串首串尾/虚词边界）；
      2. 两个邻接 bigram 都是**库里立得住的常用组合**（df ≥ 2，垃圾接缝 df 多为 0/1）；
      3. 查询词自身不比这两个邻接组合更常见（df(q) ≤ max(两侧)）。
    典型：「忆内」出现在「记忆内核」里 —— 记忆、内核 都是高频 bigram，而「忆内」
    只在它们合起来时才出现；而「偏好」出现在「用户偏好简短」里时，户偏/好简
    这类接缝 bigram 在库里根本不成立，所以不算接缝。
    """
    n = len(text)
    for i, _seam in term_occurrences(text, term):
        left = text[i - 1:i + 1] if i > 0 else ""
        right = text[i + 1:i + 3] if i + 2 <= n else ""
        if not (len(left) == 2 and all(is_cjk_char(c) for c in left)):
            return False                      # 左侧是边界 ⇒ 不是接缝
        if not (len(right) == 2 and all(is_cjk_char(c) for c in right)):
            return False                      # 右侧是边界 ⇒ 不是接缝
        dl = _df_cached(conn, left, conds, params, cache)
        dr = _df_cached(conn, right, conds, params, cache)
        if dl < 2 or dr < 2:
            return False                      # 邻接组合在别处也立不住（不是常用词）⇒ 当作真词
        if df > dl + dr:
            return False                      # 查询词比两侧组合加起来还常见 ⇒ 当作真词
    return True


def _hay(text, term):
    """ASCII 词大小写不敏感（'Android' 要能被 'android' 命中）；中文原样。"""
    if CJK_RE.search(term):
        return text or ""
    return (text or "").lower()


def relevance(conn, text, terms, conds, params, cache):
    """(证据分 0~1, 是否至少有一次非接缝命中)。

    证据＝IDF 加权覆盖率；只落在接缝上的命中打折（单词语查询则直接不算证据）。
    """
    if not terms:
        return 0.0, False
    den = sum(t["weight"] for t in terms) or 1.0
    num, aligned = 0.0, False
    short_query = len(terms) == 1
    for t in terms:
        term = t["term"]
        found, ok = term_match(_hay(text or "", term), term)
        if not found:
            continue
        if not ok and term_boundary_ok(term):
            # 两侧都是实词字：可能只是接缝，用语料词频复核
            ok = not seam_occurrence(conn, text or "", term, t["df"], conds, params, cache)
        if ok:
            aligned = True
            num += t["weight"]
        elif not short_query:
            num += t["weight"] * SEAM_CREDIT
    return min(1.0, num / den), aligned


def query_has_intent(query):
    """查询里有没有**内容词**（与"这些词在库里存不存在"无关）。

    与小语料无关：新库里查「线上机器怎么登录」，所有 bigram 的 df 都是 0，
    query_terms() 会把它们全丢进过滤器 ⇒ terms 空。但这显然是有检索意图的查询，
    稠密通道必须照常工作。所以"意图"只看词性（非纯虚词），不看 df。
    """
    for tok in tokenize(query):
        if CJK_RE.match(tok):
            if len(tok) == 1 and tok in FUNC_CHARS:
                continue
            if len(tok) >= 2 and all(c in FUNC_CHARS for c in tok):
                continue
        if re.search(r"[0-9a-z\u4e00-\u9fff]", tok):
            return True
    return False


def retrieve(conn, query, scopes, limit=DEFAULT_LIMIT, include_ask=False,
             include_archived=False, min_score=None):
    conds, params = filter_conds(scopes, include_ask, include_archived)
    pool = max(50, int(limit) * 20)
    bm = bm25_search(conn, query, pool, conds, params)
    vec = vector_search(conn, query, pool, conds, params)
    seeds = {}
    for i, item in enumerate(bm, 1):
        seeds[item[0]] = max(seeds.get(item[0], 0.0), 1.0 / (RRF_K + i))
    for i, item in enumerate(vec, 1):
        seeds[item[0]] = max(seeds.get(item[0], 0.0), 1.0 / (RRF_K + i))
    graph = []
    if seeds:
        graph = graph_expand(conn, seeds)
    rrf = rrf_fuse([bm, vec, graph])
    cand = set(rrf) | set(seeds)
    nodes = fetch_nodes(conn, list(cand), conds, params)
    chan_norm = {"bm25": norm_channel([(i, -s) for i, s in bm]),
                 "向量": norm_channel(vec),
                 "图扩散": norm_channel(graph)}
    ranks = {}
    raw_scores = {}
    for name, lst in (("bm25", bm), ("向量", vec), ("图扩散", graph)):
        for i, item in enumerate(lst, 1):
            ranks.setdefault(item[0], {})[name] = i
            raw_scores.setdefault(item[0], {})[name] = (-item[1]) if name == "bm25" else item[1]
    max_rrf = max(rrf.values()) if rrf else 1.0
    # 稠密加分只看名次：第 1 名拿满 DENSE_W，往后线性衰减到 0。
    # 为什么不能"只要出现在向量通道就给分"：61 条语料里向量通道会返回 51 个邻居，
    # 人人都加分 ⇒ 相对次序不变、调权重完全无效（实测 0.35/0.7/1.0 指标一模一样）。
    dense_bonus = {}
    for rank, (nid, _sim) in enumerate(vec[:DENSE_ADMIT_MAX], 1):
        dense_bonus[nid] = max(0.0, 1.0 - (rank - 1) / float(DENSE_ADMIT_MAX))
    items = []
    for nid, node in nodes.items():
        rrf_v = rrf.get(nid, 0.0)
        rec = math.exp(-age_days(node.get("ts")) / RECENCY_TAU_DAYS)
        best_ch, best_norm = "", 0.0
        for name in ("bm25", "向量", "图扩散"):
            val = chan_norm[name].get(nid)
            if val is not None and val > best_norm:
                best_norm, best_ch = val, name
        d, s, e = decayed(node, age_days(node.get("ts")))
        score = (W_RRF * (rrf_v / max_rrf if max_rrf else 0.0)
                 + W_CH * best_norm
                 + W_REC * rec
                 + W_E * clamp01(e)
                 + W_S * clamp01(s)
                 + W_R * clamp01(node.get("retrieval") or 0.0)
                 + DENSE_W * dense_bonus.get(nid, 0.0))
        items.append({"node": node, "score": score, "channels": ranks.get(nid, {}),
                      "best": {"channel": best_ch, "rank": ranks.get(nid, {}).get(best_ch, 0),
                               "raw": raw_scores.get(nid, {}).get(best_ch, 0.0), "norm": best_norm},
                      "rrf": rrf_v, "recency": rec, "now": {"D": d, "S": s, "E": e},
                      "dense_sim": round(float(raw_scores.get(nid, {}).get("向量", 0.0)), 4)})
    items.sort(key=lambda it: (-it["score"], str(it["node"].get("ts"))))
    # ---- 最低相关分（0.2.1）：门槛只看检索证据，不看融合分（融合分被 recency/
    # 图扩散归一化污染，噪声与真命中都会到 0.8 量级，见 settings.MIN_SCORE 注释）。
    terms = query_terms(conn, query, conds, params)
    floor = MIN_SCORE if min_score is None else float(min_score)
    cache = {}
    for it in items:
        ev, ok = relevance(conn, it["node"].get("text") or "", terms, conds, params, cache)
        it["evidence"] = round(ev, 4)
        it["boundary"] = ok
    lexical = [it for it in items if it["evidence"] > 0.0]
    # 稠密通道录取（0.3.0）：向量命中天然没有"词面证据"，但它能救同义改写。
    # 门槛只能用**相对**的 —— 绝对余弦在 bge-small 上区分不了"同领域无关"与"同义"
    # （实测答案最低 0.349，而无关查询的最强干扰中位数 0.474）。所以取：
    # 本次向量最高分往下 DENSE_ADMIT_REL 以内、且不低于 DENSE_ADMIT_ABS 的前几名。
    dense_admit = set()
    # 没有内容词（纯虚词查询）＝没有检索意图：稠密通道也不许兜出"最近的几条"，
    # 否则「的」「怎么」会返回 5 条看似答案的东西，违背「宁可空、不可编」。
    if vec and query_has_intent(query):
        # 按**名次**录取（不是按与最高分的差）：实测 alias 的正确答案常比首位低 0.1 以上，
        # 用窗口会把它切掉；而"名次前 N"天然被向量排序约束，不会无限外扩。
        dense_admit = set(nid for nid, sim in vec[:DENSE_ADMIT_MAX] if sim >= DENSE_ADMIT_ABS)
    admitted = [it for it in items
                if it["evidence"] <= 0.0 and it["node"]["id"] in dense_admit]
    for it in admitted:
        it["dense_admit"] = True
    gate = {"candidates": len(items), "lexical": len(lexical), "terms": len(terms),
            "dropped_no_evidence": len(items) - len(lexical) - len(admitted),
            "dropped_score": 0, "admitted_dense": len(admitted)}
    kept = lexical + [it for it in admitted if it not in lexical]
    if floor > 0.0 and lexical:
        after_floor = [it for it in lexical if it["evidence"] >= floor]
        gate["dropped_score"] = len(lexical) - len(after_floor)
        if after_floor:
            best = max(it["evidence"] for it in after_floor)
            keep_line = max(floor, best * REL_KEEP_RATIO)
            before = len(after_floor)
            after_floor = [it for it in after_floor if it["evidence"] >= keep_line]
            gate["dropped_score"] += before - len(after_floor)
        # admitted（稠密录取）的候选本来就没有词面证据，**不能**跟着分数地板一起丢，
        # 否则"词面有噪声命中"的那些查询会把同义命中的正确答案整批丢掉
        # —— 这正是 alias 桶卡在 0.619 的原因（实测修掉后到 0.9+）。
        kept = after_floor + admitted
    picked = mmr_select(kept, limit)
    # 图扩散只做「补位」：词面证据为零的候选（只有边连得上）最多补 GRAPH_FILL 条，
    # 且永远排在所有词面命中之后。理由：图扩散原本能把语义相关但用词不同的记忆
    # 带出来（评测 c15 就是这种），但也会把邻居无差别塞满 top5（实测查「笔记」
    # 时 4/5 是邻居）。补位是两者的折中，且条数可控。
    room = int(limit) - len(picked)
    # 只在「词面命中恰好 1 条」时补 1 条线索：0 条说明查询没有词面依据（补位只会
    # 凭空造答案），≥2 条说明词面已经够用（补位就是噪声）。
    # 单词短查询（terms 只有 1 个）不补位：这类查询本来就最容易把噪声顶上
    # top5，补位只会雪上加霜；长查询词面只落到 1 条时才值得补一条线索。
    if room > 0 and GRAPH_FILL > 0 and len(kept) == 1 and len(terms) >= 2:
        fill = [it for it in items if it["evidence"] <= 0.0 and it["node"]["id"] not in dense_admit]
        fill.sort(key=lambda it: (-it["score"], str(it["node"].get("ts"))))
        for it in fill[:min(room, GRAPH_FILL)]:
            it["filler"] = True
        picked = picked + fill[:min(room, GRAPH_FILL)]
    gate["filled"] = len([it for it in picked if it.get("filler")])
    return picked, {"bm25": len(bm), "vector": len(vec), "graph": len(graph), "gate": gate}
