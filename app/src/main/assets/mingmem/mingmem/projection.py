import json
import sqlite3
from pathlib import Path

from .decay import node_kind_for, variables_for
from .settings import CO_RETRIEVAL_INC, RETRIEVAL_SWEEP, SCHEMA_VERSION, STORAGE_INC
from .text import event_sort_key, index_tokens, json_list, now_iso, parse_list, tokenize
from .vector import embed_missing


SCHEMA_SQL = [
    """CREATE TABLE IF NOT EXISTS nodes (
        id TEXT PRIMARY KEY,
        ns TEXT NOT NULL,
        kind TEXT NOT NULL,
        text TEXT NOT NULL DEFAULT '',
        ts TEXT NOT NULL,
        S REAL DEFAULT 0, D REAL DEFAULT 0, E REAL DEFAULT 0, C REAL DEFAULT 0, I REAL DEFAULT 0,
        storage REAL DEFAULT 0, retrieval REAL DEFAULT 1,
        last_access TEXT,
        disclosure TEXT DEFAULT 'public', source TEXT DEFAULT 'user',
        tags TEXT DEFAULT '[]', entities TEXT DEFAULT '[]', refs TEXT DEFAULT '[]',
        consolidated INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, superseded INTEGER DEFAULT 0,
        valid_from TEXT, valid_to TEXT, superseded_at TEXT,
        erased INTEGER DEFAULT 0, erased_at TEXT,
        client_key TEXT
    )""",
    "CREATE INDEX IF NOT EXISTS idx_nodes_ns ON nodes(ns)",
    "CREATE INDEX IF NOT EXISTS idx_nodes_kind ON nodes(kind)",
    "CREATE INDEX IF NOT EXISTS idx_nodes_ts ON nodes(ts)",
    """CREATE TABLE IF NOT EXISTS edges (
        src TEXT NOT NULL, dst TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'ref',
        weight REAL DEFAULT 1.0, updated TEXT,
        PRIMARY KEY (src, dst, kind)
    )""",
    "CREATE INDEX IF NOT EXISTS idx_edges_dst ON edges(dst)",
    "CREATE INDEX IF NOT EXISTS idx_nodes_client_key ON nodes(client_key)",
    "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)",
    "CREATE TABLE IF NOT EXISTS vectors (id TEXT PRIMARY KEY, dim INTEGER, vec BLOB)",
    "CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(id UNINDEXED, body, tokenize='unicode61')",
]

NODE_COLS = ("id", "ns", "kind", "text", "ts", "S", "D", "E", "C", "I",
             "storage", "retrieval", "last_access", "disclosure", "source",
             "tags", "entities", "refs", "consolidated", "archived", "superseded",
             "valid_from", "valid_to", "superseded_at", "erased", "erased_at", "client_key")


def connect(path):
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    try:
        conn.execute("PRAGMA journal_mode=WAL")
    except sqlite3.Error:
        pass
    return conn


def create_schema(conn):
    for sql in SCHEMA_SQL:
        conn.execute(sql)


def bump_edge(conn, src, dst, kind, inc, ts, absolute=False):
    if not src or not dst or src == dst:
        return
    row = conn.execute("SELECT weight FROM edges WHERE src=? AND dst=? AND kind=?",
                       (src, dst, kind)).fetchone()
    if row is None:
        w = 1.0 if absolute else min(1.0, inc)
        conn.execute("INSERT INTO edges (src, dst, kind, weight, updated) VALUES (?,?,?,?,?)",
                     (src, dst, kind, w, ts))
    elif not absolute:
        w = min(1.0, float(row["weight"]) + inc)
        conn.execute("UPDATE edges SET weight=?, updated=? WHERE src=? AND dst=? AND kind=?",
                     (w, ts, src, dst, kind))


def set_edge(conn, src, dst, kind, weight, ts):
    """绝对赋值（旧库迁移用）：已有边也要改成给定权重，不能像 bump_edge 那样忽略。"""
    if not src or not dst or src == dst:
        return
    conn.execute("INSERT OR REPLACE INTO edges (src, dst, kind, weight, updated) VALUES (?,?,?,?,?)",
                 (src, dst, kind, float(weight), ts))


def fts_body(ev):
    parts = [ev.get("text") or ""]
    parts.extend(json_list(ev.get("tags") if isinstance(ev.get("tags"), str)
                           else json.dumps(ev.get("tags") or [], ensure_ascii=False)))
    parts.extend(json_list(ev.get("entities") if isinstance(ev.get("entities"), str)
                           else json.dumps(ev.get("entities") or [], ensure_ascii=False)))
    return " ".join(index_tokens(" ".join(parts)))


def insert_node(conn, ev):
    nk = node_kind_for(ev)
    if nk is None:
        return
    v = variables_for(ev, nk)
    ts = ev.get("ts") or now_iso()
    tags = ev.get("tags") or []
    entities = ev.get("entities") or []
    refs = ev.get("refs") or []
    if isinstance(tags, str):
        tags = parse_list(tags)
    if isinstance(entities, str):
        entities = parse_list(entities)
    if isinstance(refs, str):
        refs = parse_list(refs)
    vals = (ev.get("id"), ev.get("ns"), nk, ev.get("text") or "", ts,
            v["S"], v["D"], v["E"], v["C"], v["I"],
            0.0, 1.0, ts,
            ev.get("disclosure") or "public", ev.get("source") or "user",
            json.dumps(tags, ensure_ascii=False),
            json.dumps(entities, ensure_ascii=False),
            json.dumps(refs, ensure_ascii=False),
            0, 0, 0, ts, None, None, 0, None, ev.get("client_key"))
    sql = "INSERT OR REPLACE INTO nodes (" + ",".join(NODE_COLS) + ") VALUES (" + \
          ",".join(["?"] * len(NODE_COLS)) + ")"
    conn.execute(sql, vals)
    # 旧库迁移：强度与状态（storage/retrieval/last_access/archived/superseded/consolidated）
    # 必须一起带过来，否则重建后"已归档 / 已被更正"的节点会重新出现在检索里。
    strength = ev.get("strength")
    if isinstance(strength, dict):
        sets, params = [], []
        for key in ("storage", "retrieval", "last_access", "archived",
                    "superseded", "superseded_at", "valid_from", "valid_to", "consolidated"):
            if key in strength and strength[key] is not None:
                sets.append(key + "=?")
                params.append(strength[key])
        if sets:
            conn.execute("UPDATE nodes SET " + ",".join(sets) + " WHERE id=?",
                         params + [ev.get("id")])
    body = fts_body(ev)
    conn.execute("DELETE FROM nodes_fts WHERE id = ?", (ev.get("id"),))
    if body:
        conn.execute("INSERT INTO nodes_fts (id, body) VALUES (?, ?)", (ev.get("id"), body))
    for ref in refs:
        bump_edge(conn, ev.get("id"), ref, "ref", 1.0, ts, absolute=True)
    if ev.get("kind") == "consolidate":
        for ref in refs:
            conn.execute("UPDATE nodes SET consolidated = 1 WHERE id = ?", (ref,))
    if nk == "correction":
        # 双时态（G05）：被更正者置 superseded 并封口 valid_to，检索默认不再取它；
        # 更正事件本身 valid_from=ts，历史原文一个字都不改。
        for ref in refs:
            conn.execute("UPDATE nodes SET superseded = 1, superseded_at = ?, valid_to = ? "
                         "WHERE id = ?", (ts, ts, ref))


# 写入时是否顺手向量化。重放（rebuild）期间关掉：那里最后统一补，避免 N 次单条推理。
_EMBED_ON_WRITE = True


def apply_event(conn, ev):
    """把一条事件重放进投影。rebuild 与增量写入共用此函数。"""
    kind = ev.get("kind")
    ts = ev.get("ts") or now_iso()
    if kind == "access":
        targets = [t for t in (ev.get("targets") or []) if t]
        for tid in targets:
            row = conn.execute("SELECT storage FROM nodes WHERE id = ?", (tid,)).fetchone()
            if row is None:
                continue
            st = float(row["storage"])
            conn.execute("UPDATE nodes SET storage=?, retrieval=1.0, last_access=? WHERE id=?",
                         (st + STORAGE_INC * (1.0 - st), ts, tid))
        for i in range(len(targets)):
            for j in range(i + 1, len(targets)):
                bump_edge(conn, targets[i], targets[j], "co", CO_RETRIEVAL_INC, ts)
                bump_edge(conn, targets[j], targets[i], "co", CO_RETRIEVAL_INC, ts)
    elif kind == "sweep":
        d = ev.get("decay")
        if not isinstance(d, (int, float)):
            d = RETRIEVAL_SWEEP
        conn.execute("UPDATE nodes SET retrieval = retrieval * ? WHERE archived = 0", (float(d),))
        for tid in ev.get("archived") or []:
            conn.execute("UPDATE nodes SET archived = 1 WHERE id = ?", (tid,))
    elif kind == "archive":
        for tid in ev.get("targets") or []:
            conn.execute("UPDATE nodes SET archived = 1 WHERE id = ?", (tid,))
    elif kind == "link":
        src = ev.get("src")
        edge_kind = str(ev.get("edge_kind") or "explicit")
        weight = ev.get("weight")
        for dst in ev.get("refs") or []:
            if isinstance(weight, (int, float)):
                set_edge(conn, src, dst, edge_kind, float(weight), ts)   # 旧库迁移：绝对赋值
            else:
                bump_edge(conn, src, dst, edge_kind, 1.0, ts, absolute=True)
    elif kind == "consent":
        # 同意 / 审计事件只作记录（G20），不进节点表，避免污染检索与统计。
        pass
    elif kind == "forget":
        # 软删：只打标记进回收站，事件流里的原文一个字都不动。
        for tid in ev.get("targets") or []:
            conn.execute("UPDATE nodes SET erased = 1, erased_at = ? WHERE id = ?", (ts, tid))
    elif kind == "restore":
        for tid in ev.get("targets") or []:
            conn.execute("UPDATE nodes SET erased = 0, erased_at = NULL WHERE id = ?", (tid,))
    elif kind == "purge":
        # 真删的投影侧动作由 purge 命令重写事件流后 rebuild 完成；这里只留审计。
        pass
    else:
        insert_node(conn, ev)
        # 单点保证：任何"新建节点"的事件（add/consolidate/correct/import/restore…）
        # 都在这里补向量。以前只在 add/correct 里补，导致 consolidate 出的语义卡
        # 没有向量 ⇒ rebuild 前后检索顺序不一致（实测被自检抓到）。
        if _EMBED_ON_WRITE:
            embed_missing(conn, ids=[ev.get("id")])


def replay_all(conn, store):
    events = []
    bad = 0
    for path in store.event_files():
        with open(path, "r", encoding="utf-8") as f:
            for lineno, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    ev = json.loads(line)
                except ValueError:
                    bad += 1
                    continue
                if not isinstance(ev, dict) or not ev.get("id"):
                    bad += 1
                    continue
                events.append((str(ev.get("ts") or ""), path.name, lineno, ev))
    events.sort(key=lambda t: (event_sort_key(t[0]), t[1], t[2]))
    global _EMBED_ON_WRITE
    prev, _EMBED_ON_WRITE = _EMBED_ON_WRITE, False
    try:
        for _ts, _name, _ln, ev in events:
            apply_event(conn, ev)
    finally:
        _EMBED_ON_WRITE = prev
    return {"events": len(events), "bad_lines": bad}


def full_rebuild(store, verbose=False):
    store.ensure_dirs()
    if store.index_path.exists():
        store.index_path.unlink()
    for side in ("-wal", "-shm"):
        p = Path(str(store.index_path) + side)
        if p.exists():
            try:
                p.unlink()
            except OSError:
                pass
    conn = connect(store.index_path)
    create_schema(conn)
    stats = replay_all(conn, store)
    for key, value in (("schema_version", str(SCHEMA_VERSION)),
                       ("built_at", now_iso()),
                       ("signature", store.signature()),
                       ("data_root", str(store.root))):
        conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", (key, value))
    conn.commit()
    embedded = embed_missing(conn)
    conn.commit()
    if verbose:
        n = conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
        print("rebuild 完成：事件 %d 条（坏行 %d）→ 节点 %d 个；向量 %d 条"
              % (stats["events"], stats["bad_lines"], n, embedded))
    return conn, stats


def open_projection(store, verbose=False):
    """返回 (conn, fresh)。签名不一致或库不存在时全量重建。"""
    if store.index_path.exists():
        conn = None
        try:
            conn = connect(store.index_path)
            sig = conn.execute("SELECT value FROM meta WHERE key='signature'").fetchone()
            ver = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
            if sig is not None and ver is not None and \
               sig["value"] == store.signature() and ver["value"] == str(SCHEMA_VERSION):
                return conn, False
        except sqlite3.DatabaseError:
            pass
        if conn is not None:
            conn.close()
    conn, _stats = full_rebuild(store, verbose=verbose)
    return conn, True


def sync_signature(store, conn):
    conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('signature', ?)",
                 (store.signature(),))
    conn.commit()
