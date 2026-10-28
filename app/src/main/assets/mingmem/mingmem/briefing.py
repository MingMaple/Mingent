from .settings import BRIEFING_MAX_LINES, NL
from .text import event_sort_key, json_list, short_ts, summarize, tokenize


CURRENT_KINDS = ("semantic", "preference", "skill", "correction")


def _indep_current(conn, scopes, query, exclude, need):
    """G22：③ 现状段独立取数。

    只看 top-k 命中，会把「库里明明有语义卡、但没被这条查询召回」显示成「无结论」。
    这里按 kind 直接在作用域内再查一次当前结论，用查询词重叠排序；一条都不重叠时，
    退回到最近的 2 条（宁可给出「目前的几条结论」，也不整段空着）。
    """
    if need <= 0:
        return []
    where = ["kind IN (" + ",".join(["?"] * len(CURRENT_KINDS)) + ")",
             "COALESCE(archived, 0) = 0",
             "COALESCE(superseded, 0) = 0",
             "COALESCE(disclosure, 'public') <> 'private'",
             "COALESCE(disclosure, 'public') <> 'ask'"]
    params = list(CURRENT_KINDS)
    if scopes:
        where.append("ns IN (" + ",".join(["?"] * len(scopes)) + ")")
        params.extend(scopes)
    try:
        rows = conn.execute("SELECT id, ns, kind, text, ts, tags, refs FROM nodes WHERE " +
                            " AND ".join(where) + " LIMIT 200", params).fetchall()
    except Exception:
        return []
    toks = [t for t in tokenize(query) if t and t.strip()]
    scored = []
    for row in rows:
        node = dict(row)
        if node.get("id") in exclude:
            continue
        body = (node.get("text") or "") + " " + str(node.get("tags") or "")
        overlap = sum(1 for t in toks if t in body)
        scored.append((overlap, event_sort_key(node.get("ts")), node))
    scored.sort(key=lambda x: (-x[0], -x[1]))
    hits = [node for overlap, _key, node in scored if overlap > 0][:need]
    if hits:
        return hits
    return [node for _overlap, _key, node in scored][:min(need, 2)]


def build_briefing(query, scopes, items, conn, max_lines=BRIEFING_MAX_LINES):
    lines = []
    scope_text = "、".join(scopes) if scopes else "全部命名空间"

    def add(text):
        if len(lines) < max_lines:
            lines.append(text)
            return True
        return False

    # ① 主题与范围
    span = ""
    # G26：按绝对时刻排序，不能按 ISO 字符串排（不同时区偏移下字符串序会错）。
    ts_pairs = sorted([(event_sort_key(it["node"].get("ts")), str(it["node"].get("ts")))
                       for it in items if it["node"].get("ts")])
    if ts_pairs:
        span = "｜ 时间跨度 " + short_ts(ts_pairs[0][1]) + " ~ " + short_ts(ts_pairs[-1][1])
    add("① 主题与范围：查询「" + summarize(query, 30) + "」｜ 范围：" + scope_text +
        "｜ 命中 " + str(len(items)) + " 条" + span)

    # ② 时间线
    add("② 时间线：")
    if not items:
        add("   （无匹配记忆）")
    else:
        ids = set(it["node"]["id"] for it in items)
        for it in sorted(items, key=lambda x: (event_sort_key(x["node"].get("ts")),
                                              str(x["node"].get("id")))):
            node = it["node"]
            rel = [r for r in json_list(node.get("refs")) if r in ids and r != node["id"]]
            link = ("  ← 关联 " + "、".join(rel[:2])) if rel else ""
            add("   - " + short_ts(node.get("ts")) + " [" + str(node.get("kind")) + "] " +
                summarize(node.get("text"), 38) + "（" + str(node.get("id")) + "）" + link)

    # ③ 现状（G22：不只看 top-k 命中，作用域内独立再取一次当前结论）
    add("③ 现状：")
    current = [it["node"] for it in items
               if it["node"].get("kind") in CURRENT_KINDS]
    rows = list(current)
    if len(rows) < 4:
        seen = set(node.get("id") for node in rows)
        rows.extend(_indep_current(conn, scopes, query, seen, 4 - len(rows)))
    if not rows:
        add("   （无已巩固的结论；可用 cycle + consolidate 生成）")
    else:
        for node in rows[:5]:
            add("   - [" + str(node.get("kind")) + "] " + summarize(node.get("text"), 44) +
                "（" + str(node.get("id")) + "）")
            if node.get("kind") == "correction":
                refs = json_list(node.get("refs"))
                if refs:
                    old = conn.execute("SELECT id, text, ts FROM nodes WHERE id = ?",
                                       (refs[0],)).fetchone()
                    if old is not None:
                        add("     曾经：" + summarize(old["text"], 30) + "（" + str(old["id"]) + "）")

    # ④ 相关实体
    add("④ 相关实体：")
    counts = {}
    for it in items:
        for ent in json_list(it["node"].get("entities")):
            counts[ent] = counts.get(ent, 0) + 1
        for tag in json_list(it["node"].get("tags")):
            counts["#" + tag] = counts.get("#" + tag, 0) + 1
    if not counts:
        add("   （无）")
    else:
        top = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:8]
        add("   " + "｜ ".join([k + "(" + str(v) + ")" for k, v in top]))

    # ⑤ 情绪与偏好线索
    add("⑤ 情绪与偏好线索：")
    emo = [it for it in items if it["node"].get("kind") in ("feel", "prefer", "preference")]
    if not emo:
        add("   （无）")
    else:
        for it in emo[:4]:
            node = it["node"]
            add("   - [" + str(node.get("kind")) + "] E=" + ("%.2f" % it["now"]["E"]) +
                " ｜ " + summarize(node.get("text"), 34) + "（" + str(node.get("id")) + "）")

    # ⑥ 来源
    add("⑥ 来源（为什么召回它）：")
    if not items:
        add("   （无）")
    else:
        for it in items:
            node = it["node"]
            ch = it["channels"]
            best = it.get("best") or {}
            parts = []
            if best.get("channel"):
                parts.append("%s#%d(%.3f)" % (best["channel"], best["rank"], best["raw"]))
            for name in sorted(ch):
                if name != best.get("channel"):
                    parts.append(name + "#" + str(ch[name]))
            why = "、".join(parts) or "重排"
            add("   - " + str(node.get("id")) + " ｜ " + str(node.get("ns")) + " ｜ " +
                short_ts(node.get("ts")) + " ｜ " + why + " ｜ 分 %.3f" % it["score"])
    return NL.join(lines)
