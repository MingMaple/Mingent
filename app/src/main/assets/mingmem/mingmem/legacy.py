"""旧库（v1，SQLite-only）一次性导入。

v1 的真相源就是 `index.sqlite` 里的 `nodes` / `edges`；v2 的真相源是事件流，
所以导入＝**把行翻译成事件**再重放，不复制、不链接旧库文件（旧库按 D-03 冻结，这里只读）。

保真度约定（重要，别为了"能跑"丢信息）：
- `S/D/E/C/I`、`tags/entities/refs`、`disclosure/source`、`ts`、原始 `id` 全部照搬；
- **强度与状态**（`storage / retrieval / last_access / archived / superseded / consolidated`）
  通过事件的 `strength` 字段带过去 —— 否则重建后"哪些已归档、哪些已被更正"会全部回退，
  等于把旧库里已经压下去的东西重新翻出来；
- 边的 `kind` 与 `weight` 通过 `link` 事件的 `edge_kind` / `weight` 带过去（绝对赋值）。

导入是**幂等**的：事件 id 已存在就跳过，重复跑不会翻倍。
"""

import hashlib
import json
import sqlite3
from pathlib import Path

from .text import json_list, now_iso

NODE_KINDS = ("episode", "note", "feel", "prefer", "decide",
              "semantic", "preference", "skill", "correction")
# 这些列在 v1 的 nodes 里存在，且必须带过去
STRENGTH_KEYS = ("storage", "retrieval", "last_access", "archived",
                 "superseded", "consolidated")


def legacy_db_path(target):
    p = Path(str(target))
    if p.is_dir():
        return p / "index.sqlite"
    return p


def _connect_ro(path):
    uri = path.as_uri() + "?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def _table_cols(conn, table):
    try:
        return [r[1] for r in conn.execute("PRAGMA table_info(" + table + ")")]
    except sqlite3.DatabaseError:
        return []


def load_legacy(target):
    """读旧库，返回 `{"nodes": [...], "edges": [...], "meta": {...}}`（纯 Python 值）。"""
    path = legacy_db_path(target)
    if not path.is_file():
        raise FileNotFoundError("找不到旧库：" + str(path))
    conn = _connect_ro(path)
    try:
        cols = _table_cols(conn, "nodes")
        if not cols:
            raise sqlite3.DatabaseError("不是 v1 旧库：缺少 nodes 表（" + str(path) + "）")
        nodes = [dict(r) for r in conn.execute("SELECT * FROM nodes").fetchall()]
        edge_cols = _table_cols(conn, "edges")
        edges = [dict(r) for r in conn.execute("SELECT * FROM edges").fetchall()] if edge_cols else []
        meta = {}
        if _table_cols(conn, "meta"):
            for r in conn.execute("SELECT key, value FROM meta").fetchall():
                meta[str(r[0])] = r[1]
    finally:
        conn.close()
    return {"nodes": nodes, "edges": edges, "meta": meta, "path": str(path)}


def parse_ns_map(raw):
    """`--ns-map project:旧=project:新[,其他=...]` → dict。"""
    mapping = {}
    for part in str(raw or "").replace(";", ",").split(","):
        part = part.strip()
        if not part or "=" not in part:
            continue
        old, new = part.split("=", 1)
        old, new = old.strip(), new.strip()
        if old and new:
            mapping[old] = new
    return mapping


def edge_event_id(src, dst, kind):
    """边事件的确定性 id：同一 (src, dst, kind) 永远是同一条，便于幂等与排查。"""
    digest = hashlib.sha1(("%s|%s|%s" % (src, dst, kind)).encode("utf-8")).hexdigest()
    return "link-" + digest[:16]


def node_event(row, ns_map):
    kind = str(row.get("kind") or "note")
    if kind not in NODE_KINDS:
        kind = "note"
    ns = str(row.get("ns") or "global")
    ev = {
        "id": str(row.get("id")),
        "ts": str(row.get("ts") or now_iso()),
        "ns": ns_map.get(ns, ns),
        "kind": kind,
        "text": str(row.get("text") or ""),
        "tags": json_list(row.get("tags")),
        "entities": json_list(row.get("entities")),
        "refs": json_list(row.get("refs")),
        "disclosure": str(row.get("disclosure") or "public"),
        "source": str(row.get("source") or "user"),
        "imported_from": "legacy-v1",
    }
    for key in ("S", "D", "E", "C", "I"):
        val = row.get(key)
        if isinstance(val, (int, float)):
            ev[key] = float(val)
    strength = {}
    for key in STRENGTH_KEYS:
        val = row.get(key)
        if val is not None:
            strength[key] = float(val) if key in ("storage", "retrieval") else val
    if strength:
        ev["strength"] = strength
    return ev


def edge_event(edge, ns_by_id):
    src = str(edge.get("src") or "")
    dst = str(edge.get("dst") or "")
    kind = str(edge.get("kind") or "ref")
    if not src or not dst or src == dst:
        return None
    ev = {
        "id": edge_event_id(src, dst, kind),
        "ts": str(edge.get("updated") or now_iso()),
        "ns": ns_by_id.get(src, "global"),
        "kind": "link",
        "text": "旧库迁移的边",
        "src": src,
        "refs": [dst],
        "edge_kind": kind,
        "imported_from": "legacy-v1",
    }
    weight = edge.get("weight")
    if isinstance(weight, (int, float)):
        ev["weight"] = float(weight)
    return ev


def build_events(data, ns_map=None):
    ns_map = ns_map or {}
    nodes = [node_event(row, ns_map) for row in data["nodes"] if row.get("id")]
    ns_by_id = dict((ev["id"], ev["ns"]) for ev in nodes)
    edges = []
    for edge in data["edges"]:
        ev = edge_event(edge, ns_by_id)
        if ev is not None:
            edges.append(ev)
    return nodes, edges


def existing_event_ids(store):
    """事件流里出现过的所有 id（含非节点事件），用于幂等导入。"""
    ids = set()
    for path in store.event_files():
        try:
            with open(path, "r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        ev = json.loads(line)
                    except ValueError:
                        continue
                    if isinstance(ev, dict) and ev.get("id"):
                        ids.add(str(ev["id"]))
        except OSError:
            continue
    return ids
