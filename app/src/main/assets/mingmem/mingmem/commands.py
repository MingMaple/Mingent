import hashlib
import json
import os
import sqlite3
import sys

from .briefing import build_briefing
from .chain import read_pin, verify_store, write_pin
from .cli import clamp_limit, emit, fail, read_text, resolve_scopes
from .core import (audit_ask, expected_nodes, pending_batches, perfume_candidates,
                    purge_events)
from .decay import age_days, decayed
from .projection import apply_event, connect, full_rebuild, open_projection, sync_signature
from .retrieve import bm25_search, fetch_nodes, filter_conds, retrieve, vector_search
from .settings import (ARCHIVE_RETRIEVAL, ARCHIVE_STORAGE, CONSOLIDATE_KINDS, CONSOLIDATE_MIN,
                      EVENT_KINDS, LIB_ROOT, NL, RETRIEVAL_SWEEP, SCRIPT_PATH)
from .text import clamp01, default_ns, new_id, now_iso, parse_list, summarize
from .vector import embed_missing, status as vector_status


def cmd_add(args, store):
    kind = (args.kind or "episode").strip()
    if kind == "consolidate":
        return fail("consolidate 请用 consolidate 子命令（需要 --from 来源）")
    # 这些事件类型只能由内核自己产生：审计类（consent/purge/forget/restore）若允许手写，
    # 就等于让调用方伪造"同意/删除"记录；派生类（access/sweep/archive/link/correction/
    # consolidate）各自有专用子命令，手写会绕过配套逻辑（双时态、来源校验、引用清理）。
    banned = ("access", "sweep", "archive", "consolidate", "link", "correction",
              "consent", "forget", "restore", "purge")
    if kind not in EVENT_KINDS or kind in banned:
        return fail("不支持的 kind：" + kind + "（可用：" +
                    "、".join([k for k in EVENT_KINDS if k not in banned]) + "）")
    text = read_text(args.text)
    if not text.strip():
        return fail("正文为空：--text 必须非空")
    ev = {"id": new_id(kind), "ts": now_iso(), "ns": args.ns or default_ns(), "kind": kind,
          "text": text, "tags": parse_list(args.tags), "entities": parse_list(args.entities),
          "refs": parse_list(args.refs), "disclosure": (args.disclosure or "public"),
          "source": (args.source or "user")}
    for key in ("S", "D", "E", "C", "I"):
        val = getattr(args, key, None)
        if val is not None:
            ev[key] = clamp01(val)
    # client_key 幂等：重试（网络重发、宿主重启后重放）不该写出第二条记忆。
    client_key = (getattr(args, "client_key", None) or "").strip()
    if client_key:
        probe, _pfresh = open_projection(store)
        hit = probe.execute("SELECT id FROM nodes WHERE client_key = ? LIMIT 1",
                            (client_key,)).fetchone()
        probe.close()
        if hit is not None:
            if args.json:
                print(json.dumps({"id": hit[0], "idempotent": True, "client_key": client_key},
                                 ensure_ascii=False, indent=2))
            else:
                emit("幂等命中：client_key 已存在，返回 " + str(hit[0]) + "（未重复写入）",
                     args.quiet)
            return 0
        ev["client_key"] = client_key
    if args.dry_run:
        if args.json:
            print(json.dumps({"dry_run": True, "event": ev}, ensure_ascii=False, indent=2))
        else:
            emit("（--dry-run，未写入）" + json.dumps(ev, ensure_ascii=False), args.quiet)
        return 0
    conn, _fresh = open_projection(store)
    refs = ev.get("refs") or []
    if refs:
        existing = fetch_nodes(conn, refs, [], [])
        missing_refs = [r for r in refs if r not in existing]
        if missing_refs:
            conn.close()
            return fail("refs 不存在：" + "、".join(missing_refs))
    store.append(ev)
    apply_event(conn, ev)
    embed_missing(conn, ids=[ev["id"]])
    sync_signature(store, conn)
    conn.close()
    if args.json:
        print(json.dumps({"id": ev["id"], "ts": ev["ts"], "ns": ev["ns"], "kind": kind,
                          "tags": ev["tags"], "entities": ev["entities"], "refs": ev["refs"],
                          "disclosure": ev["disclosure"], "source": ev["source"],
                          "chars": len(text)}, ensure_ascii=False, indent=2))
    else:
        emit("已写入 " + str(ev["id"]) + " ｜ " + ev["ns"] + " ｜ " + kind + " ｜ " +
             summarize(text, 40), args.quiet)
    return 0


def cmd_recall(args, store):
    query = args.query
    scopes = resolve_scopes(args)
    conn, _fresh = open_projection(store)
    args.limit = clamp_limit(args.limit)
    items, debug = retrieve(conn, query, scopes, args.limit,
                            args.include_ask, args.include_archived,
                            getattr(args, "min_score", None))
    briefing = build_briefing(query, scopes, items, conn)
    # G20：展示 ask 级记忆必须留同意记录（审计事件，不是静默放行）。
    consent = None
    if args.include_ask and not args.dry_run:
        ask_ids = [it["node"]["id"] for it in items
                   if str(it["node"].get("disclosure")) == "ask"]
        if ask_ids:
            consent = audit_ask(store, args.ns or default_ns(), query, ask_ids)
    if args.json:
        payload = {
            "query": query, "scopes": scopes, "count": len(items), "channels": debug,
            "gate": debug.get("gate"),
            "ask_audit": consent["id"] if consent else None,
            "briefing": briefing,
            "items": [{"id": it["node"]["id"], "ns": it["node"]["ns"], "kind": it["node"]["kind"],
                       "ts": it["node"]["ts"], "text": it["node"]["text"],
                       "disclosure": it["node"]["disclosure"],
                       "score": round(it["score"], 4),
                       "evidence": it.get("evidence"), "boundary": it.get("boundary"),
                       "filler": bool(it.get("filler")),
                       "dense_sim": it.get("dense_sim"),
                       "channels": it["channels"],
                       "S": round(it["now"]["S"], 4), "E": round(it["now"]["E"], 4)}
                      for it in items],
        }
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(briefing)
    if items and not args.dry_run:
        ev = {"id": new_id("access"), "ts": now_iso(), "ns": (args.ns or default_ns()),
              "kind": "access", "text": "recall: " + summarize(query, 30),
              "targets": [it["node"]["id"] for it in items]}
        store.append(ev)
        apply_event(conn, ev)
        sync_signature(store, conn)
    if consent is not None:
        apply_event(conn, consent)
        sync_signature(store, conn)
        print("（已记录 ask 披露同意：" + str(len(consent["targets"])) + " 条 ｜ 审计事件 " +
              str(consent["id"]) + "）", file=sys.stderr)
    conn.close()
    return 0


def cmd_search(args, store):
    scopes = resolve_scopes(args)
    conds, params = filter_conds(scopes, args.include_ask, args.include_archived)
    conn, _fresh = open_projection(store)
    args.limit = clamp_limit(args.limit)
    bm = bm25_search(conn, args.query, args.limit, conds, params)
    vec = vector_search(conn, args.query, args.limit, conds, params)
    nodes = fetch_nodes(conn, [i[0] for i in bm + vec], conds, params)
    consent = None
    if args.include_ask:
        ask_ids = [nid for nid, node in nodes.items()
                   if str(node.get("disclosure")) == "ask"]
        if ask_ids:
            consent = audit_ask(store, args.ns or default_ns(), args.query, ask_ids)
            sync_signature(store, conn)
            print("（已记录 ask 披露同意：" + str(len(ask_ids)) + " 条 ｜ 审计事件 " +
                  str(consent["id"]) + "）", file=sys.stderr)
    if args.json:
        print(json.dumps({"bm25": [i[0] for i in bm], "vector": [i[0] for i in vec],
                          "ask_audit": consent["id"] if consent else None,
                          "nodes": nodes}, ensure_ascii=False, indent=2))
        conn.close()
        return 0
    print("原始命中（调试用；briefing 见 recall）")
    print("BM25：" + str(len(bm)) + " 条；向量：" + str(len(vec)) + " 条")
    for rank, (nid, score) in enumerate(bm, 1):
        node = nodes.get(nid)
        if node is None:
            continue
        print("  bm25 #" + str(rank) + "  " + ("%.3f" % (-score)) + "  [" + str(node["kind"]) + "] " +
              summarize(node["text"], 50) + "（" + nid + "）")
    for rank, (nid, score) in enumerate(vec, 1):
        node = nodes.get(nid)
        if node is None:
            continue
        print("  向量 #" + str(rank) + "  " + ("%.3f" % score) + "  [" + str(node["kind"]) + "] " +
              summarize(node["text"], 50) + "（" + nid + "）")
    conn.close()
    return 0


def cmd_links(args, store):
    conn, _fresh = open_projection(store)
    row = conn.execute("SELECT * FROM nodes WHERE id = ?", (args.id,)).fetchone()
    if row is None:
        conn.close()
        return fail("找不到节点：" + str(args.id))
    node = dict(row)
    if args.json:
        out_edges = conn.execute("SELECT * FROM edges WHERE src = ? ORDER BY weight DESC",
                                 (args.id,)).fetchall()
        in_edges = conn.execute("SELECT * FROM edges WHERE dst = ? ORDER BY weight DESC",
                                (args.id,)).fetchall()

        def _edge_rows(rows, key):
            items = []
            for edge in rows:
                other = edge["dst"] if key == "dst" else edge["src"]
                nb = conn.execute("SELECT kind, text FROM nodes WHERE id = ?", (other,)).fetchone()
                item = {k: edge[k] for k in edge.keys()}
                item["neighbor_kind"] = nb["kind"] if nb is not None else None
                item["neighbor_text"] = nb["text"] if nb is not None else None
                items.append(item)
            return items

        print(json.dumps({
            "node": {k: node[k] for k in ("id", "ns", "kind", "ts", "disclosure", "text",
                                         "S", "D", "E", "C", "I", "storage", "retrieval",
                                         "last_access", "archived", "superseded",
                                         "valid_from", "valid_to", "superseded_at",
                                         "erased", "erased_at")},
            "out_edges": _edge_rows(out_edges, "dst"),
            "in_edges": _edge_rows(in_edges, "src"),
        }, ensure_ascii=False, indent=2))
        conn.close()
        return 0
    d, s, e = decayed(node, age_days(node.get("ts")))
    print("节点 " + node["id"])
    print("- 命名空间：" + str(node["ns"]) + " ｜ 类型：" + str(node["kind"]) +
          " ｜ 时间：" + str(node["ts"]))
    print("- 披露等级：" + str(node["disclosure"]) + " ｜ 来源：" + str(node["source"]))
    print("- 原文：" + summarize(node["text"], 120))
    print("- 六变量：S=%.2f D=%.2f E=%.2f C=%.2f I=%.2f（现值 S=%.2f D=%.2f E=%.2f）"
          % (node["S"], node["D"], node["E"], node["C"], node["I"], s, d, e))
    print("- 强度：storage=%.3f retrieval=%.3f last_access=%s archived=%s superseded=%s"
          % (node["storage"], node["retrieval"], node["last_access"], node["archived"], node["superseded"]))
    if int(node.get("erased") or 0) == 1:
        print("- 状态：**在回收站**（" + str(node.get("erased_at")) +
              "）；检索与导出都看不到它，只有 bin / links 能查到")
    if node.get("valid_from") or node.get("valid_to"):
        # 双时态（G05）：被更正节点保留原文，只把有效期封口，历史可追溯。
        print("- 有效期：valid_from=" + str(node.get("valid_from")) +
              " ｜ valid_to=" + str(node.get("valid_to")) +
              " ｜ superseded_at=" + str(node.get("superseded_at")))
    out_edges = conn.execute("SELECT * FROM edges WHERE src = ? ORDER BY weight DESC", (args.id,)).fetchall()
    in_edges = conn.execute("SELECT * FROM edges WHERE dst = ? ORDER BY weight DESC", (args.id,)).fetchall()
    print("- 出边 " + str(len(out_edges)) + " 条：")
    for edge in out_edges[:10]:
        nb = conn.execute("SELECT kind, text FROM nodes WHERE id = ?", (edge["dst"],)).fetchone()
        print("   → " + edge["dst"] + " [" + edge["kind"] + " w=%.2f]" % edge["weight"] +
              ("  " + summarize(nb["text"], 30) if nb is not None else ""))
    print("- 入边 " + str(len(in_edges)) + " 条：")
    for edge in in_edges[:10]:
        nb = conn.execute("SELECT kind, text FROM nodes WHERE id = ?", (edge["src"],)).fetchone()
        print("   ← " + edge["src"] + " [" + edge["kind"] + " w=%.2f]" % edge["weight"] +
              ("  " + summarize(nb["text"], 30) if nb is not None else ""))
    conn.close()
    return 0


def cmd_cycle(args, store):
    conn, _fresh = open_projection(store)
    ns_filter = (args.ns or "").strip()
    where = "archived = 0"
    where_params = ()
    if ns_filter:
        where += " AND ns = ?"
        where_params = (ns_filter,)
    scanned = conn.execute("SELECT COUNT(*) FROM nodes WHERE " + where,
                           where_params).fetchone()[0]
    rows = conn.execute("SELECT id FROM nodes WHERE " + where +
                        " AND storage < ? AND retrieval < ?",
                        where_params + (ARCHIVE_STORAGE, ARCHIVE_RETRIEVAL)).fetchall()
    to_archive = [r["id"] for r in rows]
    kinds = tuple(parse_list(getattr(args, "kinds", None)) or ["episode"])
    batches = pending_batches(conn, kinds)
    note_batches = pending_batches(conn, ("note",))
    perfume = perfume_candidates(conn)
    if not args.dry_run:
        conn.execute("UPDATE nodes SET retrieval = retrieval * ? WHERE " + where,
                     (RETRIEVAL_SWEEP,) + where_params)
        for tid in to_archive:
            conn.execute("UPDATE nodes SET archived = 1 WHERE id = ?", (tid,))
        ev = {"id": new_id("sweep"), "ts": now_iso(), "ns": (args.ns or default_ns()),
              "kind": "sweep", "text": "cycle 遗忘扫描", "decay": RETRIEVAL_SWEEP,
              "archived": to_archive}
        store.append(ev)
        sync_signature(store, conn)
    if getattr(args, "json", False):
        print(json.dumps({
            "dry_run": bool(args.dry_run), "ns": (ns_filter or None),
            "kinds": list(kinds), "scanned": scanned, "archived": to_archive,
            "decay": RETRIEVAL_SWEEP, "batches": batches,
            "note_batches": note_batches, "perfume": perfume,
        }, ensure_ascii=False, indent=2))
        conn.close()
        return 0
    print("记忆维护 cycle" + ("（--dry-run，未写入）" if args.dry_run else ""))
    print("- 遗忘扫描：retrieval × " + str(RETRIEVAL_SWEEP) + "，覆盖 " + str(scanned) +
          " 个节点（范围：" + (ns_filter or "全部命名空间") + "）；本次归档 " +
          str(len(to_archive)) + " 个")
    print("- 待巩固批次（同一命名空间 ≥" + str(CONSOLIDATE_MIN) + " 条未巩固 " +
          "/".join(kinds) + "）：")
    if not batches:
        print("   （无）")
    for batch in batches:
        print("   · " + batch["ns"] + "：" + str(batch["count"]) + " 条 → " +
              "consolidate --from " + ",".join(batch["ids"]) + " --text ...")
    print("- 待熏习候选（同一标签 ≥" + str(CONSOLIDATE_MIN) + " 次 prefer）：")
    if not perfume:
        print("   （无）")
    for cand in perfume:
        print("   · " + cand["ns"] + " ｜ #" + cand["tag"] + " × " + str(cand["count"]))
    if note_batches and kinds == ("episode",):
        print("- 提示：另有 " + str(sum(b["count"] for b in note_batches)) +
              " 条未巩固 note（如课堂笔记）可一并巩固；加 --kinds episode,note 查看批次。")
    print("- 提示：consolidate 由会话里的 AI 读本报告后写回；原始 episode 永不删除。")
    conn.close()
    return 0


def cmd_consolidate(args, store):
    from_ids = parse_list(args.from_ids)
    if len(from_ids) < 2:
        return fail("至少需要 2 个来源 id（--from id1,id2[,...]）")
    text = read_text(args.text)
    if not text.strip():
        return fail("正文为空：--text 必须非空")
    conn, _fresh = open_projection(store)
    rows = fetch_nodes(conn, from_ids, [], [])
    missing = [i for i in from_ids if i not in rows]
    if missing:
        conn.close()
        return fail("来源 id 不存在：" + "、".join(missing))
    already = [i for i in from_ids if rows[i].get("consolidated")]
    if already and not getattr(args, "force", False):
        conn.close()
        return fail("来源已巩固：" + "、".join(already) + "（如需重做请加 --force）")
    ns = args.ns
    if not ns:
        nss = sorted(set(rows[i]["ns"] for i in from_ids))
        if len(nss) > 1:
            conn.close()
            return fail("来源跨命名空间（" + "、".join(nss) + "），请用 --ns 显式指定")
        ns = nss[0]
    kind = args.kind or "semantic"
    if kind not in CONSOLIDATE_KINDS:
        conn.close()
        return fail("--kind 只能是：" + "、".join(CONSOLIDATE_KINDS))
    ev = {"id": new_id("consolidate"), "ts": now_iso(), "ns": ns, "kind": "consolidate",
          "node_kind": kind, "text": text, "refs": from_ids,
          "tags": parse_list(args.tags), "disclosure": args.disclosure or "public",
          "source": "ai"}
    if args.dry_run:
        if args.json:
            print(json.dumps({"dry_run": True, "event": ev}, ensure_ascii=False, indent=2))
        else:
            emit("（--dry-run，未写入）" + json.dumps(ev, ensure_ascii=False), args.quiet)
        conn.close()
        return 0
    store.append(ev)
    apply_event(conn, ev)
    embed_missing(conn, ids=[ev["id"]])
    sync_signature(store, conn)
    conn.close()
    if args.json:
        print(json.dumps({"id": ev["id"], "ts": ev["ts"], "ns": ns, "kind": kind,
                          "refs": from_ids, "chars": len(text)}, ensure_ascii=False, indent=2))
    else:
        emit("已巩固 " + str(ev["id"]) + " ｜ " + ns + " ｜ " + kind + " ｜ 来源 " +
             str(len(from_ids)) + " 条 ｜ " + summarize(text, 40), args.quiet)
    return 0


def cmd_correct(args, store):
    text = read_text(args.text)
    if not text.strip():
        return fail("正文为空：--text 必须非空")
    conn, _fresh = open_projection(store)
    old = conn.execute("SELECT id, ns, text FROM nodes WHERE id = ?", (args.target,)).fetchone()
    if old is None:
        conn.close()
        return fail("找不到被更正的节点：" + str(args.target))
    ns = args.ns or old["ns"]
    ev = {"id": new_id("correction"), "ts": now_iso(), "ns": ns, "kind": "correction",
          "text": text, "refs": [args.target], "tags": parse_list(args.tags),
          "disclosure": args.disclosure or "public", "source": "user"}
    if args.dry_run:
        if args.json:
            print(json.dumps({"dry_run": True, "event": ev}, ensure_ascii=False, indent=2))
        else:
            emit("（--dry-run，未写入）" + json.dumps(ev, ensure_ascii=False), args.quiet)
        conn.close()
        return 0
    store.append(ev)
    apply_event(conn, ev)
    sync_signature(store, conn)
    conn.close()
    if args.json:
        print(json.dumps({"id": ev["id"], "ts": ev["ts"], "ns": ns, "kind": "correction",
                          "target": args.target, "chars": len(text)}, ensure_ascii=False, indent=2))
    else:
        emit("已追加更正 " + str(ev["id"]) + "：曾「" + summarize(old["text"], 26) +
             "」→ 现「" + summarize(text, 26) + "」（历史未改）", args.quiet)
    return 0


def _ids_arg(raw):
    return [str(x).strip() for x in parse_list(raw) if str(x).strip()]


def _classify(conn, ids):
    """把 id 分成：存在且未删 / 已在回收站 / 不存在。"""
    live, in_bin, missing = [], [], []
    for nid in ids:
        row = conn.execute("SELECT id, erased FROM nodes WHERE id = ?", (nid,)).fetchone()
        if row is None:
            missing.append(nid)
        elif int(row["erased"] or 0) == 1:
            in_bin.append(nid)
        else:
            live.append(nid)
    return live, in_bin, missing


def cmd_forget(args, store):
    ids = _ids_arg(args.ids)
    if not ids:
        return fail("--ids 不能为空")
    conn, _fresh = open_projection(store)
    live, in_bin, missing = _classify(conn, ids)
    if not live:
        conn.close()
        return fail("没有可软删的节点：已在回收站 " + str(len(in_bin)) + " 条，缺失 " +
                    str(len(missing)) + " 条")
    ev = {"id": new_id("forget"), "ts": now_iso(), "ns": (args.ns or default_ns()),
          "kind": "forget", "text": "软删 " + str(len(live)) + " 条",
          "targets": live, "reason": (args.reason or ""), "source": "user"}
    if args.dry_run:
        conn.close()
        if args.json:
            print(json.dumps({"dry_run": True, "event": ev}, ensure_ascii=False, indent=2))
        else:
            emit("（--dry-run，未写入）将软删 " + str(len(live)) + " 条：" + "、".join(live[:8]),
                 args.quiet)
        return 0
    store.append(ev)
    apply_event(conn, ev)
    sync_signature(store, conn)
    conn.close()
    if args.json:
        print(json.dumps({"event": ev["id"], "erased": live, "already": in_bin,
                          "missing": missing}, ensure_ascii=False, indent=2))
    else:
        emit("已软删 " + str(len(live)) + " 条（进回收站；bin 查看、restore 恢复、purge 真删）" +
             ("；已在回收站 " + str(len(in_bin)) if in_bin else "") +
             ("；缺失 " + str(len(missing)) if missing else ""), args.quiet)
    return 0


def cmd_bin(args, store):
    conn, _fresh = open_projection(store)
    total = conn.execute("SELECT COUNT(*) FROM nodes WHERE COALESCE(erased, 0) = 1").fetchone()[0]
    sql = ("SELECT id, ns, kind, ts, erased_at, text FROM nodes "
           "WHERE COALESCE(erased, 0) = 1 ORDER BY erased_at DESC")
    params = []
    limit = getattr(args, "limit", None)
    offset = int(getattr(args, "offset", 0) or 0)
    if limit:
        sql += " LIMIT ? OFFSET ?"
        params += [int(limit), offset]
    elif offset:
        sql += " LIMIT -1 OFFSET ?"
        params.append(offset)
    rows = conn.execute(sql, params).fetchall()
    conn.close()
    if args.json:
        print(json.dumps({"count": len(rows), "total": total, "offset": offset,
                          "items": [
            {"id": r["id"], "ns": r["ns"], "kind": r["kind"], "ts": r["ts"],
             "erased_at": r["erased_at"], "text": r["text"]} for r in rows]},
            ensure_ascii=False, indent=2))
        return 0
    if not rows:
        emit("回收站为空", args.quiet)
        return 0
    print("回收站（共 " + str(total) + " 条" +
          ("，本页 " + str(len(rows)) + " 条，偏移 " + str(offset) if limit or offset else "") +
          "；restore 恢复 / purge 真删）")
    for r in rows:
        print("  " + str(r["id"]) + " ｜ " + str(r["ns"]) + " ｜ " + str(r["kind"]) +
              " ｜ 删除于 " + str(r["erased_at"]) + " ｜ " + summarize(r["text"], 40))
    return 0


def cmd_restore(args, store):
    ids = _ids_arg(args.ids)
    if not ids:
        return fail("--ids 不能为空")
    conn, _fresh = open_projection(store)
    live, in_bin, missing = _classify(conn, ids)
    if not in_bin:
        conn.close()
        return fail("回收站里没有这些 id（未删除 " + str(len(live)) + " 条，缺失 " +
                    str(len(missing)) + " 条）")
    ev = {"id": new_id("restore"), "ts": now_iso(), "ns": (args.ns or default_ns()),
          "kind": "restore", "text": "恢复 " + str(len(in_bin)) + " 条",
          "targets": in_bin, "source": "user"}
    if args.dry_run:
        conn.close()
        emit("（--dry-run，未写入）将恢复 " + str(len(in_bin)) + " 条：" + "、".join(in_bin[:8]),
             args.quiet)
        return 0
    store.append(ev)
    apply_event(conn, ev)
    sync_signature(store, conn)
    conn.close()
    if args.json:
        print(json.dumps({"event": ev["id"], "restored": in_bin, "missing": missing},
                         ensure_ascii=False, indent=2))
    else:
        emit("已恢复 " + str(len(in_bin)) + " 条", args.quiet)
    return 0


def cmd_purge(args, store):
    ids = _ids_arg(args.ids)
    if not ids:
        return fail("--ids 不能为空")
    conn, _fresh = open_projection(store)
    live, in_bin, missing = _classify(conn, ids)
    conn.close()
    if live:
        return fail("这些记忆还没软删，不能直接真删（先 forget）：" + "、".join(live[:5]))
    if not in_bin:
        return fail("回收站里没有这些 id（缺失 " + str(len(missing)) + " 条）")
    if args.dry_run or not args.yes:
        if args.json:
            print(json.dumps({"confirmed": False, "purge": in_bin, "missing": missing,
                              "hint": "确认真删请加 --yes（不可逆，会物理重写事件流）"},
                             ensure_ascii=False, indent=2))
        else:
            emit("将真删 " + str(len(in_bin)) + " 条（不可逆）：" + "、".join(in_bin[:8]) +
                 ("…" if len(in_bin) > 8 else "") + "；确认请加 --yes", args.quiet)
        return 0
    # 审计只留「删了几条 + id 的哈希」，不留 id 本身：这样事件流里查不到被删对象的任何痕迹，
    # 但仍能核对「某条记忆是否曾被真删」（拿 id 算哈希来比对）。
    purged_sha = [hashlib.sha256(str(x).encode("utf-8")).hexdigest()[:16] for x in in_bin]
    audit = {"id": new_id("purge"), "ts": now_iso(), "ns": "global", "kind": "purge",
             "text": "真删 " + str(len(in_bin)) + " 条", "targets": [],
             "purged_count": len(in_bin), "purged_sha256": purged_sha, "source": "user"}
    # 先写审计（其 id 不在被删集合里，重写时不会被删掉），再物理清理，最后重建投影。
    store.append(audit)
    try:
        stats = purge_events(store, in_bin)
    except OSError as exc:
        conn, _fresh = full_rebuild(store, verbose=False)
        conn.close()
        return fail("真删失败（可能只替换了部分文件，请核对事件流后 rebuild）：" + str(exc))
    conn, _fresh = full_rebuild(store, verbose=False)
    conn.close()
    if args.json:
        print(json.dumps({"audit": audit["id"], "purged": in_bin,
                          "removed_events": stats["removed"],
                          "touched_events": stats["touched"], "missing": missing},
                         ensure_ascii=False, indent=2))
    else:
        emit("已真删 " + str(len(in_bin)) + " 条：移除事件 " + str(stats["removed"]) +
             " 条、清理引用 " + str(stats["touched"]) + " 条；审计事件 " + str(audit["id"]),
             args.quiet)
    return 0


def cmd_import(args, store):
    """从 v1 旧库（SQLite-only）一次性导入：把行翻译成事件再重放。"""
    from .legacy import (build_events, existing_event_ids, load_legacy, parse_ns_map)
    try:
        data = load_legacy(args.legacy)
    except FileNotFoundError as exc:
        return fail(str(exc))
    except sqlite3.DatabaseError as exc:
        return fail("旧库不可用：" + str(exc))
    ns_map = parse_ns_map(getattr(args, "ns_map", None))
    nodes, edges = build_events(data, ns_map)
    limit = getattr(args, "limit", None)
    if limit:
        keep = int(limit)
        if keep < 1:
            return fail("--limit 必须 ≥ 1")
        nodes = nodes[:keep]
        ids = set(ev["id"] for ev in nodes)
        edges = [ev for ev in edges if ev["src"] in ids and (ev.get("refs") or [""])[0] in ids]
    done = existing_event_ids(store)
    todo_nodes = [ev for ev in nodes if ev["id"] not in done]
    todo_edges = [ev for ev in edges if ev["id"] not in done]
    skipped = (len(nodes) - len(todo_nodes)) + (len(edges) - len(todo_edges))
    by_ns = {}
    for ev in todo_nodes:      # 只统计这次真正要写的
        by_ns[ev["ns"]] = by_ns.get(ev["ns"], 0) + 1
    if args.dry_run:
        if args.json:
            print(json.dumps({"legacy": data["path"], "dry_run": True, "nodes": len(todo_nodes),
                              "edges": len(todo_edges), "skipped": skipped, "by_ns": by_ns,
                              "sample": (todo_nodes or [None])[0]}, ensure_ascii=False, indent=2))
        else:
            emit("（--dry-run，未写入）将导入 节点 " + str(len(todo_nodes)) + " 条 / 边 " +
                 str(len(todo_edges)) + " 条（跳过已存在 " + str(skipped) + "）" + NL +
                 "- 旧库：" + data["path"] + NL +
                 "- 命名空间：" + ("；".join("%s=%d" % (k, v) for k, v in by_ns.items()) or "（空）"),
                 args.quiet)
        return 0
    for ev in todo_nodes + todo_edges:
        store.append(ev)
    conn, stats = full_rebuild(store, verbose=False)
    node_count = conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
    edge_count = conn.execute("SELECT COUNT(*) FROM edges").fetchone()[0]
    conn.close()
    if args.json:
        print(json.dumps({"legacy": data["path"], "nodes": len(todo_nodes),
                          "edges": len(todo_edges), "skipped": skipped, "by_ns": by_ns,
                          "projection": {"nodes": node_count, "edges": edge_count,
                                         "events": stats["events"],
                                         "bad_lines": stats["bad_lines"]}},
                         ensure_ascii=False, indent=2))
    else:
        emit("已导入旧库：" + data["path"] + NL +
             "- 节点 " + str(len(todo_nodes)) + " 条（跳过已存在 " + str(skipped) + "）；边 " +
             str(len(todo_edges)) + " 条" + NL +
             "- 命名空间：" + ("；".join("%s=%d" % (k, v) for k, v in by_ns.items()) or "（空）") + NL +
             "- 投影：节点 " + str(node_count) + " ｜ 边 " + str(edge_count) + " ｜ 已重建", args.quiet)
    return 0


def cmd_rebuild(args, store):
    conn, stats = full_rebuild(store, verbose=False)
    nodes = conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
    archived = conn.execute("SELECT COUNT(*) FROM nodes WHERE archived = 1").fetchone()[0]
    vecs = conn.execute("SELECT COUNT(*) FROM vectors").fetchone()[0]
    conn.close()
    if args.json:
        print(json.dumps({"index": str(store.index_path), "events": stats["events"],
                          "bad_lines": stats["bad_lines"], "nodes": nodes,
                          "archived": archived, "vectors": vecs}, ensure_ascii=False, indent=2))
    else:
        emit("投影已重建：" + str(store.index_path) + NL +
             "- 重放事件 " + str(stats["events"]) + " 条（坏行 " + str(stats["bad_lines"]) + "）" + NL +
             "- 节点 " + str(nodes) + " 个（归档 " + str(archived) + "）；向量 " + str(vecs) + " 条",
             args.quiet)
    return 0


def cmd_capabilities(args, store):
    """能力探测（协议/版本/命令/限额/错误分类），壳与插件的第一步。"""
    from .spec import capabilities
    data = capabilities()
    if args.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    print("mingmem 能力")
    print("- 协议：" + data["protocol"] + " ｜ 软件：" + data["version"] +
          " ｜ 投影 schema：" + str(data["schema_version"]))
    print("- 数据模型：" + data["data_model"])
    for name, layer in data["layers"].items():
        print("- 层 " + name + "：" + str(layer.get("entry") or layer.get("generic")))
    print("- 命令（" + str(len(data["commands"])) + "）：" +
          "、".join(sorted(data["commands"])))
    print("- 限额：max_limit " + str(data["limits"]["max_limit"]) + " ｜ max_batch " +
          str(data["limits"]["max_batch"]))
    print("- 错误分类：" + "、".join(sorted(data["errors"])))
    return 0


def cmd_describe(args, store):
    """机器可读的命令与参数描述（参数从 argparse 抽，不会与真实 CLI 漂移）。"""
    from .spec import describe
    try:
        data = describe(getattr(args, "command", None))
    except KeyError:
        return fail("未知命令：" + str(getattr(args, "command", "")))
    if args.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    for name in sorted(data):
        info = data[name]
        print(name + " —— " + info["summary"])
        for p in info["params"]:
            flags = " ".join(p["cli"])
            req = "必填" if p["required"] else "可选"
            default = "" if p.get("default") is None else " 默认 " + repr(p["default"])
            print("  " + flags + "  [" + p["kind"] + "，" + req + default + "]" +
                  ("  " + p["help"] if p.get("help") else ""))
        print("")
    return 0


def cmd_verify(args, store):
    """事件链校验（G44）：发现事件被删 / 被插 / 被改。

    本地链只能证明「自上次入链以来没被动过」；要防「整条链被重算」，必须把链头
    锚点写到数据根之外（--pin），这也是唯一能让本地改写失效的办法。
    """
    reports = verify_store(store, getattr(args, "ns", None))
    heads = dict((r["file"], r["head"]) for r in reports)
    pin_path = getattr(args, "pin", None)
    pin_state, pin_target, pin_diff = None, None, []
    if pin_path:
        pin = read_pin(pin_path)
        if pin is None:
            pin_target = os.path.normpath(str(write_pin(pin_path, heads)))
            pin_state = "written"
        else:
            pin_target = os.path.normpath(str(pin_path))
            pin_state = "checked"
            for name in sorted(set(list(heads.keys()) + list(pin.keys()))):
                now, old = heads.get(name), pin.get(name)
                if now is None or old is None or old != now:
                    pin_diff.append({"file": name, "pin": old, "now": now})
    ok = all(r["ok"] for r in reports) and not pin_diff
    if args.json:
        print(json.dumps({"ok": ok, "files": reports, "pin": pin_target,
                          "pin_state": pin_state, "pin_diff": pin_diff},
                         ensure_ascii=False, indent=2))
        return 0 if ok else 1
    print("事件链校验 verify")
    if not reports:
        print("- 没有事件文件（空库）")
    for rep in reports:
        state = "链完整" if rep["ok"] else "**断裂**"
        print("- " + rep["file"] + "：" + str(rep["lines"]) + " 行，入链 " + str(rep["chained"]) +
              " 条，" + state + " ｜ head " + rep["head"][:16])
        for br in rep["breaks"][:5]:
            print("  断点：第 " + str(br["line"]) + " 行（期望 " + str(br["expect"])[:16] +
                  " ｜ 实得 " + str(br["got"])[:16] + "）")
    if pin_state == "written":
        print("- 锚点：已写入 " + str(pin_target) + "（要有效，必须放在数据根之外）")
    elif pin_state == "checked":
        if pin_diff:
            print("- 锚点：与 " + str(pin_target) + " 不一致 " + str(len(pin_diff)) + " 个文件")
            for d in pin_diff[:5]:
                print("  " + str(d["file"]) + "：锚 " + str(d["pin"])[:16] + " → 现 " +
                      str(d["now"])[:16])
        else:
            print("- 锚点：与 " + str(pin_target) + " 一致")
    else:
        print("- 锚点：未提供（本地链发现不了「整条链被重算」；要防它用 --pin <数据根之外的文件>）")
    print("- 结论：" + ("通过" if ok else "发现问题（见上）"))
    return 0 if ok else 1


def cmd_status(args, store):
    info = store.stats()
    conn, fresh = open_projection(store)
    nodes = conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
    archived = conn.execute("SELECT COUNT(*) FROM nodes WHERE archived = 1").fetchone()[0]
    ns_rows = conn.execute("SELECT ns, COUNT(*) AS c FROM nodes GROUP BY ns ORDER BY c DESC").fetchall()
    kind_rows = conn.execute("SELECT kind, COUNT(*) AS c FROM nodes GROUP BY kind ORDER BY c DESC").fetchall()
    batches = pending_batches(conn)
    vecs = conn.execute("SELECT COUNT(*) FROM vectors").fetchone()[0]
    vst = vector_status()
    vec_requested = vst["enabled"]
    vec_allowed = vst["available"]
    vec_available = vst["available"]
    payload = {"data_root": str(store.root), "events_files": info["files"],
               "events_lines": info["lines"], "bad_lines": info["bad_lines"],
               "nodes": nodes, "archived": archived,
               "by_ns": dict((r["ns"], r["c"]) for r in ns_rows),
               "by_kind": dict((r["kind"], r["c"]) for r in kind_rows),
               "pending_batches": len(batches), "vectors": vecs,
               "vector_installed": vst["available"],
               "vector_enabled": vec_requested,
               "vector_allowed": vec_allowed,
               "vector_model": vst["model"],
               "vector_dim": vst["dim"],
               "vector_precision": vst["precision"],
               "vector_reason": vst["reason"],
               "vector_min_nodes": None,
               "vector_available": vec_available}
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        conn.close()
        return 0
    print("记忆内核状态")
    print("- 数据根：" + str(store.root) + ("（投影刚重建）" if fresh else ""))
    print("- 事件：" + str(info["files"]) + " 个文件 / " + str(info["lines"]) + " 行（坏行 " +
          str(info["bad_lines"]) + "）；节点 " + str(nodes) + " 个（归档 " + str(archived) + "）")
    print("- 命名空间：" + ("；".join("%s=%d" % (k, v) for k, v in payload["by_ns"].items()) or "（空）"))
    print("- 记忆类型：" + ("；".join("%s=%d" % (k, v) for k, v in payload["by_kind"].items()) or "（空）"))
    if payload["vector_available"]:
        vec_state = "已启用：" + str(vst["model"]) + "，dim=" + str(vst["dim"]) + "，" + str(vst["precision"])
    elif not payload["vector_enabled"]:
        vec_state = "已关闭（MEM_VECTOR 显式关闭）"
    else:
        vec_state = "不可用，已降级 BM25 + 图扩散（" + (vst["reason"] or "未知原因") + "）"
    print("- 待巩固批次：" + str(len(batches)) + " 个；向量：" + str(vecs) + " 条（通道" + vec_state + "）")
    conn.close()
    return 0


def cmd_doctor(args, store):
    ok = True
    lines = ["记忆内核体检 doctor"]
    lines.append("- 脚本：" + str(SCRIPT_PATH))
    lines.append("- 库根：" + str(LIB_ROOT))
    root_state = "存在" if store.root.exists() else "不存在（命令会自动创建）"
    root_src = str(getattr(store, "root_source", "") or "")
    lines.append("- 数据根：" + str(store.root) + "（" + root_state +
                 ("；来源 " + root_src if root_src else "") + "）")
    lines.append("- Python：" + sys.version.split()[0] + " ｜ " + sys.executable)
    sqlite_ver = "未知"
    fts_state = "未知"
    try:
        tmp_conn = sqlite3.connect(":memory:")
        sqlite_ver = str(tmp_conn.execute("SELECT sqlite_version()").fetchone()[0])
        tmp_conn.execute("CREATE VIRTUAL TABLE t USING fts5(x)")
        fts_state = "可用"
        tmp_conn.close()
    except sqlite3.Error as exc:
        fts_state = "不可用（" + str(exc) + "）"
        ok = False
    lines.append("- SQLite：" + sqlite_ver + " ｜ FTS5：" + fts_state)
    info = store.stats()
    lines.append("- 事件流：" + str(info["files"]) + " 个文件 / " + str(info["lines"]) + " 行 / " +
                 str(info["bytes"]) + " 字节；坏行 " + str(info["bad_lines"]))
    if info["bad_lines"]:
        ok = False
        lines.append("  警告：存在无法解析的事件行（rebuild 会跳过它们，请人工排查）")
    # 哈希链：能发现「事件被删 / 被插 / 被改」—— 这是「事件↔投影」对账发现不了的
    # （对账只保证投影没漏应用事件，前提恰恰是事件流本身没被动过）。
    chain_reports = verify_store(store)
    chain_ok = all(r["ok"] for r in chain_reports)
    chained = sum(r["chained"] for r in chain_reports)
    unchained = sum(r["unchained"] for r in chain_reports)
    if chain_ok:
        lines.append("- 哈希链：完整（入链 " + str(chained) + " 条" +
                     ("；未入链 " + str(unchained) + " 条＝历史行或坏行" if unchained else "") + "）")
    else:
        ok = False
        breaks = [(rep["file"], b) for rep in chain_reports for b in rep["breaks"]]
        lines.append("- 哈希链：断裂 " + str(len(breaks)) + " 处")
        for name, br in breaks[:5]:
            lines.append("  断点：" + str(name) + ":" + str(br["line"]) + "（期望 " +
                         str(br["expect"])[:16] + " ｜ 实得 " + str(br["got"])[:16] + "）")
        lines.append("  说明：本地链发现不了「整条链被重算」的改写，那需要 verify --pin 的外部锚点")
    # 空库（还没有投影）时 nodes 也得有初值：稠密通道那行要拿它做分母。
    nodes = 0
    if store.index_path.exists():
        try:
            conn = connect(store.index_path)
            sig = conn.execute("SELECT value FROM meta WHERE key='signature'").fetchone()
            nodes = conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
            edges = conn.execute("SELECT COUNT(*) FROM edges").fetchone()[0]
            fresh = sig is not None and sig["value"] == store.signature()
            lines.append("- 投影：" + str(store.index_path) + " ｜ 节点 " + str(nodes) +
                         " ｜ 边 " + str(edges) + " ｜ " + ("最新" if fresh else "已过期（下次命令自动重建）"))
            conn.close()
        except sqlite3.DatabaseError as exc:
            ok = False
            lines.append("- 投影：损坏（" + str(exc) + "）；可执行 rebuild 重建")
    else:
        lines.append("- 投影：尚未生成（首次命令会自动重建）")
    # G43：事件流是真相源，投影只是派生视图 —— 逐 id 对账，漏应用就报错并指认。
    expect, _bad_events = expected_nodes(store)
    if store.index_path.exists():
        try:
            rc_conn = connect(store.index_path)
            have = set(row[0] for row in rc_conn.execute("SELECT id FROM nodes").fetchall())
            rc_conn.close()
        except sqlite3.DatabaseError:
            have = None
        if have is not None:
            missing = [k for k in expect if k not in have]
            extra = [k for k in have if k not in expect]
            if missing or extra:
                ok = False
                lines.append("- 事件↔投影对账：不一致（事件应有节点 " + str(len(expect)) +
                             " ｜ 投影实际 " + str(len(have)) + "）")
                for mid in missing[:5]:
                    lines.append("  缺失事件：" + str(mid) + " ｜ " + str(expect[mid][0]) +
                                 ":" + str(expect[mid][1]))
                for xid in extra[:5]:
                    lines.append("  多余节点：" + str(xid) + "（事件流里找不到来源）")
                lines.append("  处理：执行 rebuild 重建投影（共缺失 " + str(len(missing)) +
                             " / 多余 " + str(len(extra)) + "）")
            else:
                lines.append("- 事件↔投影对账：一致（节点 " + str(len(have)) + "）")
    else:
        lines.append("- 事件↔投影对账：未生成投影，跳过")
    vst = vector_status()
    if vst["available"]:
        # 这里必须自己开连接：上面统计投影的 conn 已经 close()，复用会抛
        # ProgrammingError 并被 except 吞成 0（实测 doctor 显示 0/N 条）。
        # 投影不存在时不要 connect：sqlite3.connect 会凭空建出一个空 index.sqlite，
        # 让只读的体检命令产生写副作用。
        vec_rows = 0
        if store.index_path.exists():
            try:
                vconn = connect(store.index_path)
                vec_rows = int(vconn.execute("SELECT COUNT(*) FROM vectors").fetchone()[0])
                vconn.close()
            except sqlite3.DatabaseError:
                vec_rows = 0
        lines.append("- 稠密通道：已启用（" + str(vst["model"]) + " ｜ dim " + str(vst["dim"]) +
                     " ｜ " + str(vst["precision"]) + " ｜ " + str(vst["runtime"]) +
                     " ｜ 已向量化 " + str(vec_rows) + "/" + str(nodes) + " 条）")
        if nodes and vec_rows < nodes:
            lines.append("- 稠密通道：有 " + str(nodes - vec_rows) + " 条节点还没向量化 ⇒ 跑一次 rebuild 补齐")
    elif not vst["enabled"]:
        lines.append("- 稠密通道：已关闭（MEM_VECTOR 显式关闭）⇒ 只用 BM25 + 图扩散")
    else:
        lines.append("- 稠密通道：不可用（" + (vst["reason"] or "未知原因") +
                     "）⇒ 已降级为 BM25 + 图扩散")
    lines.append("- 结论：" + ("通过" if ok else "发现问题（见上）"))
    if args.json:
        print(json.dumps({"ok": ok, "lines": lines}, ensure_ascii=False, indent=2))
    else:
        print(NL.join(lines))
    return 0 if ok else 1
