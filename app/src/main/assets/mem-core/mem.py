#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""记忆内核（mem core）——通用、可检索、会衰减、能巩固、可重建的长期记忆。

单一真相源：<data-root>/events/*.jsonl   append-only，一行一个 JSON 事件
可弃投影：  <data-root>/index.sqlite     FTS5 全文 + 图边 + 强度，可随时 rebuild

只用 Python 标准库；向量通道可选（缺依赖时自动降级为 BM25 + 图扩散）。
脚本自定位，不依赖当前工作目录。
"""

import argparse
import hashlib
import json
import math
import os
import random
import re
import sqlite3
import struct
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

SCHEMA_VERSION = 1
NL = chr(10)

SCRIPT_PATH = Path(__file__).resolve()
LIB_ROOT = SCRIPT_PATH.parent.parent
DEFAULT_DATA_ROOT = LIB_ROOT / "_Data"

# ------------------------------------------------------------------ 常量与默认值

EVENT_KINDS = ("episode", "note", "feel", "prefer", "decide", "consolidate",
               "correction", "access", "sweep", "archive", "link")
NODE_KINDS = ("episode", "note", "feel", "prefer", "decide",
              "semantic", "preference", "skill", "correction")
CONSOLIDATE_KINDS = ("semantic", "preference", "skill")
DISCLOSURES = ("public", "ask", "private")
SOURCES = ("user", "ai", "mixed")

DEFAULT_VARS = {
    "episode":    {"S": 0.5, "D": 0.7, "E": 0.2, "C": 0.1, "I": 0.1},
    "note":       {"S": 0.5, "D": 0.6, "E": 0.1, "C": 0.1, "I": 0.1},
    "feel":       {"S": 0.6, "D": 0.6, "E": 0.7, "C": 0.1, "I": 0.1},
    "prefer":     {"S": 0.7, "D": 0.5, "E": 0.2, "C": 0.3, "I": 0.1},
    "decide":     {"S": 0.8, "D": 0.6, "E": 0.1, "C": 0.3, "I": 0.1},
    "semantic":   {"S": 0.8, "D": 0.5, "E": 0.1, "C": 0.8, "I": 0.1},
    "preference": {"S": 0.8, "D": 0.5, "E": 0.2, "C": 0.8, "I": 0.1},
    "skill":      {"S": 0.8, "D": 0.6, "E": 0.1, "C": 0.7, "I": 0.1},
    "correction": {"S": 0.8, "D": 0.6, "E": 0.1, "C": 0.4, "I": 0.1},
}

# 三条衰减（详见设计文档）：细节 D、意义 S、情绪 E；λE < λS < λD
DECAY_BASE = {"D": 0.02, "S": 0.005, "E": 0.0015}
ALPHA, BETA, GAMMA = 0.6, 0.5, 0.4

STORAGE_INC = 0.05          # 访问：storage += 0.05 * (1 - storage)
RETRIEVAL_SWEEP = 0.95      # 遗忘扫描：retrieval *= 0.95
CO_RETRIEVAL_INC = 0.1      # 共检索强化（LTP）
ARCHIVE_STORAGE = 0.1       # 归档阈值
ARCHIVE_RETRIEVAL = 0.05

RRF_K = 60
# 重排权重：RRF 管「跨通道共识」，W_CH 管「单通道幅度」（RRF 会丢掉通道内分差）
W_RRF, W_CH, W_REC, W_E, W_S, W_R = 0.30, 0.20, 0.20, 0.15, 0.10, 0.05
RECENCY_TAU_DAYS = 30.0
HOP_DECAY = 0.6
GRAPH_HOPS = 2

BRIEFING_MAX_LINES = 24
DEFAULT_LIMIT = 5
MAX_LIMIT = 50
CONSOLIDATE_MIN = 3
# --json 支持矩阵（未列出的命令给 stderr 警告，仍走文本输出）
JSON_SUPPORTED = ("recall", "search", "status", "doctor",
                  "add", "consolidate", "correct", "cycle", "rebuild", "export", "links")
VECTOR_MODEL = "BAAI/bge-small-zh-v1.5"
# 向量通道暂不使用：用户 2026-10-20 的口径是「约两三千条」（量级约数，积累得比较多时再引入）；实现取阈值 2300。
VECTOR_MIN_NODES = 2300

CJK_RE = re.compile(r"[\u4e00-\u9fff]")

# ------------------------------------------------------------------ 基础工具


def now_iso():
    """写入时间戳。保留毫秒：跨文件的事件只靠 ts 排序，秒级精度会让同一秒内的事件乱序。"""
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def parse_ts(text):
    try:
        return datetime.fromisoformat(str(text))
    except Exception:
        return None


def event_sort_key(ts):
    """重放排序键：解析成时间戳，避免不同时区偏移下按字符串排错。"""
    dt = parse_ts(ts)
    if dt is None:
        return 0.0
    try:
        return dt.timestamp()
    except Exception:
        return 0.0


def age_days(ts):
    dt = parse_ts(ts)
    if dt is None:
        return 0.0
    now = datetime.now().astimezone()
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=now.tzinfo)
    return max(0.0, (now - dt).total_seconds() / 86400.0)


def clamp01(x):
    return min(1.0, max(0.0, float(x)))


def parse_list(text):
    if text is None:
        return []
    if isinstance(text, (list, tuple)):
        return [str(x).strip() for x in text if str(x).strip()]
    return [p.strip() for p in re.split(r"[,，;；]", str(text)) if p.strip()]


def json_list(text):
    if not text:
        return []
    try:
        v = json.loads(text)
    except Exception:
        return []
    if isinstance(v, list):
        return [str(x) for x in v]
    return []


def tokenize(text):
    """中文 bigram + ASCII 词。FTS5 默认分词器会把整段中文当一个 token。"""
    out = []
    if not text:
        return out
    for seg in re.findall(r"[\u4e00-\u9fff]+|[0-9A-Za-z_.+\-]+", str(text)):
        if CJK_RE.match(seg):
            if len(seg) == 1:
                out.append(seg)
            else:
                for i in range(len(seg) - 1):
                    out.append(seg[i:i + 2])
        else:
            t = seg.lower()
            if re.search(r"[0-9a-z]", t):
                out.append(t)
    return out


def new_id(kind):
    stamp = datetime.now().strftime("%y%m%d%H%M%S")
    return "%s-%s-%s" % (str(kind)[:4], stamp, "%04x" % random.getrandbits(16))


def ns_filename(ns):
    name = str(ns).replace(":", "-")
    name = re.sub(r"[^0-9A-Za-z_\-\u4e00-\u9fff]", "-", name)
    name = re.sub(r"-{2,}", "-", name).strip("-")
    return (name or "unnamed") + ".jsonl"


def short_ts(ts):
    s = str(ts or "")
    if len(s) >= 16 and s[10] == "T":
        return s[5:10] + " " + s[11:16]
    return s[:16]


def summarize(text, n=42):
    t = re.sub(r"\s+", " ", str(text or "")).strip()
    if len(t) <= n:
        return t
    return t[:max(1, n - 1)] + "…"


def default_ns():
    v = os.environ.get("MEM_NS")
    if v:
        return v.strip()
    name = LIB_ROOT.name
    if name.endswith("库") and len(name) > 1:
        name = name[:-1]
    return "project:" + name


def node_kind_for(ev):
    k = ev.get("kind")
    if k in ("access", "sweep", "archive", "link"):
        return None
    if k == "consolidate":
        nk = ev.get("node_kind") or "semantic"
        return nk if nk in CONSOLIDATE_KINDS else "semantic"
    if k == "correction":
        return "correction"
    return k if k in NODE_KINDS else "note"


def variables_for(ev, kind):
    base = dict(DEFAULT_VARS.get(kind, DEFAULT_VARS["note"]))
    for k in ("S", "D", "E", "C", "I"):
        v = ev.get(k)
        if isinstance(v, (int, float)):
            base[k] = clamp01(v)
    return base


def decayed(node, days):
    e = clamp01(node.get("E") or 0.0)
    c = clamp01(node.get("C") or 0.0)
    i = clamp01(node.get("I") or 0.0)
    lam_e = DECAY_BASE["E"]
    lam_s = max(DECAY_BASE["S"], lam_e)
    lam_d = DECAY_BASE["D"] * (1 - ALPHA * e) * (1 - BETA * c) * (1 + GAMMA * i)
    lam_d = max(lam_d, lam_s)
    d0 = clamp01(node.get("D") or 0.0)
    s0 = clamp01(node.get("S") or 0.0)
    return (d0 * math.exp(-lam_d * days),
            s0 * math.exp(-lam_s * days),
            e * math.exp(-lam_e * days))


def pack_vec(vec):
    return struct.pack("<%df" % len(vec), *[float(x) for x in vec])


def unpack_vec(blob):
    return list(struct.unpack("<%df" % (len(blob) // 4), blob))


# ------------------------------------------------------------------ 事件存储


class Store(object):
    def __init__(self, data_root):
        self.root = Path(data_root)
        self.events_dir = self.root / "events"
        self.index_path = self.root / "index.sqlite"

    def ensure_dirs(self):
        self.events_dir.mkdir(parents=True, exist_ok=True)

    def event_files(self):
        if not self.events_dir.is_dir():
            return []
        return sorted([p for p in self.events_dir.glob("*.jsonl") if p.is_file()])

    def ns_path(self, ns):
        base = self.events_dir / ns_filename(ns)
        # 不同 ns 经过安全化后可能落到同一文件名（如 project:A/B 与 project:A-B）。
        # 已存在文件的首条有效事件若属于别的 ns，则改用带 hash 后缀的文件，避免混写。
        if base.exists():
            try:
                with open(base, "r", encoding="utf-8") as fh:
                    for line in fh:
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            old = json.loads(line)
                        except ValueError:
                            continue
                        if old.get("ns") != ns:
                            suffix = hashlib.sha1(str(ns).encode("utf-8")).hexdigest()[:8]
                            return self.events_dir / (base.stem + "-" + suffix + ".jsonl")
                        break
            except OSError:
                pass
        return base

    def append(self, ev):
        self.ensure_dirs()
        path = self.ns_path(ev["ns"])
        line = json.dumps(ev, ensure_ascii=False)
        with open(path, "a", encoding="utf-8", newline="") as f:
            f.write(line + NL)
        return path

    def signature(self):
        items = []
        for p in self.event_files():
            st = p.stat()
            items.append([p.name, int(st.st_size), int(st.st_mtime)])
        raw = json.dumps(items, ensure_ascii=False).encode("utf-8")
        return hashlib.sha256(raw).hexdigest()

    def stats(self):
        info = {"files": 0, "lines": 0, "bad_lines": 0, "bytes": 0}
        for p in self.event_files():
            info["files"] += 1
            info["bytes"] += p.stat().st_size
            with open(p, "r", encoding="utf-8") as f:
                for line in f:
                    if not line.strip():
                        continue
                    info["lines"] += 1
                    try:
                        json.loads(line)
                    except ValueError:
                        info["bad_lines"] += 1
        return info


# ------------------------------------------------------------------ 投影（SQLite）

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
        consolidated INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, superseded INTEGER DEFAULT 0
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
    "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)",
    "CREATE TABLE IF NOT EXISTS vectors (id TEXT PRIMARY KEY, dim INTEGER, vec BLOB)",
    "CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(id UNINDEXED, body, tokenize='unicode61')",
]

NODE_COLS = ("id", "ns", "kind", "text", "ts", "S", "D", "E", "C", "I",
             "storage", "retrieval", "last_access", "disclosure", "source",
             "tags", "entities", "refs", "consolidated", "archived", "superseded")


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


def fts_body(ev):
    parts = [ev.get("text") or ""]
    parts.extend(json_list(ev.get("tags") if isinstance(ev.get("tags"), str)
                           else json.dumps(ev.get("tags") or [], ensure_ascii=False)))
    parts.extend(json_list(ev.get("entities") if isinstance(ev.get("entities"), str)
                           else json.dumps(ev.get("entities") or [], ensure_ascii=False)))
    return " ".join(tokenize(" ".join(parts)))


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
            0, 0, 0)
    sql = "INSERT OR REPLACE INTO nodes (" + ",".join(NODE_COLS) + ") VALUES (" + \
          ",".join(["?"] * len(NODE_COLS)) + ")"
    conn.execute(sql, vals)
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
        for ref in refs:
            conn.execute("UPDATE nodes SET superseded = 1 WHERE id = ?", (ref,))


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
        for dst in ev.get("refs") or []:
            bump_edge(conn, src, dst, "explicit", 1.0, ts, absolute=True)
    else:
        insert_node(conn, ev)


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
    for _ts, _name, _ln, ev in events:
        apply_event(conn, ev)
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


# ------------------------------------------------------------------ 向量通道（可选）

_EMBEDDER = None
_VECTOR_ENABLED = None


def vector_enabled():
    """向量通道是否被请求启用。默认关闭；设计上等记忆 ≥ VECTOR_MIN_NODES 后再引入。"""
    global _VECTOR_ENABLED
    if _VECTOR_ENABLED is None:
        flag = str(os.environ.get("MEM_VECTOR", "")).strip().lower()
        _VECTOR_ENABLED = flag in ("1", "true", "yes", "on")
    return bool(_VECTOR_ENABLED)


def projected_node_count(store):
    """只读估算投影里的节点数（用于向量阈值；不触发重建、不加载模型）。"""
    path = store.index_path
    if not path.exists():
        return 0
    try:
        conn = sqlite3.connect("file:" + str(path) + "?mode=ro", uri=True)
        try:
            return int(conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0])
        finally:
            conn.close()
    except sqlite3.Error:
        return 0


def vector_force():
    flag = str(os.environ.get("MEM_VECTOR_FORCE", "")).strip().lower()
    return flag in ("1", "true", "yes", "on")


def vector_allowed(store):
    """是否允许启用向量：用户口径「约两三千条」后再引入（实现阈值 VECTOR_MIN_NODES = 2300）；测试可用 MEM_VECTOR_FORCE=1 放行。"""
    if vector_force():
        return True
    return projected_node_count(store) >= VECTOR_MIN_NODES


def vector_installed():
    """依赖是否装了（只查 spec，不 import，毫秒级）。"""
    try:
        import importlib.util
        return importlib.util.find_spec("sentence_transformers") is not None
    except Exception:
        return False


def get_embedder():
    global _EMBEDDER
    if _EMBEDDER is not None:
        return _EMBEDDER if _EMBEDDER is not False else None
    if not vector_enabled():
        _EMBEDDER = False
        return None
    try:
        from sentence_transformers import SentenceTransformer
    except Exception:
        _EMBEDDER = False
        return None
    try:
        _EMBEDDER = SentenceTransformer(VECTOR_MODEL)
    except Exception:
        _EMBEDDER = False
        return None
    return _EMBEDDER


def vector_available():
    return get_embedder() is not None


def vector_loaded():
    return _EMBEDDER is not None and _EMBEDDER is not False


def embed_texts(texts):
    model = get_embedder()
    if model is None:
        return None
    try:
        vecs = model.encode(list(texts), normalize_embeddings=True)
    except Exception:
        return None
    return [[float(x) for x in v] for v in vecs]


def embed_missing(conn, ids=None):
    model = get_embedder()
    if model is None:
        return 0
    if ids:
        ph = ",".join(["?"] * len(ids))
        rows = conn.execute("SELECT id, text FROM nodes WHERE id IN (" + ph + ")", list(ids)).fetchall()
    else:
        rows = conn.execute("SELECT n.id AS id, n.text AS text FROM nodes n "
                            "LEFT JOIN vectors v ON v.id = n.id WHERE v.id IS NULL").fetchall()
    rows = [r for r in rows if (r["text"] or "").strip()]
    if not rows:
        return 0
    vecs = embed_texts([r["text"] for r in rows])
    if not vecs or len(vecs) != len(rows):
        return 0
    for row, vec in zip(rows, vecs):
        conn.execute("INSERT OR REPLACE INTO vectors (id, dim, vec) VALUES (?,?,?)",
                     (row["id"], len(vec), pack_vec(vec)))
    return len(rows)


# ------------------------------------------------------------------ 检索管线


def filter_conds(scopes, include_ask=False, include_archived=False, alias="n"):
    conds = []
    params = []
    if scopes:
        conds.append(alias + ".ns IN (" + ",".join(["?"] * len(scopes)) + ")")
        params.extend(scopes)
    if not include_archived:
        conds.append(alias + ".archived = 0")
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
    if not vector_available():
        return []
    vecs = embed_texts([query])
    if not vecs:
        return []
    qv = vecs[0]
    sql = "SELECT v.id AS id, v.vec AS vec FROM vectors v JOIN nodes n ON n.id = v.id"
    if conds:
        sql += " WHERE " + " AND ".join(conds)
    rows = conn.execute(sql, list(params)).fetchall()
    scored = []
    for r in rows:
        v = unpack_vec(r["vec"])
        if len(v) != len(qv):
            continue
        dot = 0.0
        for a, b in zip(qv, v):
            dot += a * b
        scored.append((r["id"], dot))
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


def retrieve(conn, query, scopes, limit=DEFAULT_LIMIT, include_ask=False,
             include_archived=False):
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
    items = []
    for nid, node in nodes.items():
        rrf_v = rrf.get(nid, 0.0)
        rec = math.exp(-age_days(node.get("ts")) / RECENCY_TAU_DAYS)
        best_ch, best_norm = "", 0.0
        for name in ("bm25", "向量", "图扩散"):
            val = chan_norm[name].get(nid)
            if val is not None and val > best_norm:
                best_norm, best_ch = val, name
        score = (W_RRF * (rrf_v / max_rrf if max_rrf else 0.0)
                 + W_CH * best_norm
                 + W_REC * rec
                 + W_E * clamp01(node.get("E") or 0.0)
                 + W_S * clamp01(node.get("S") or 0.0)
                 + W_R * clamp01(node.get("retrieval") or 0.0))
        d, s, e = decayed(node, age_days(node.get("ts")))
        items.append({"node": node, "score": score, "channels": ranks.get(nid, {}),
                      "best": {"channel": best_ch, "rank": ranks.get(nid, {}).get(best_ch, 0),
                               "raw": raw_scores.get(nid, {}).get(best_ch, 0.0), "norm": best_norm},
                      "rrf": rrf_v, "recency": rec, "now": {"D": d, "S": s, "E": e}})
    items.sort(key=lambda it: (-it["score"], str(it["node"].get("ts"))))
    return items[:int(limit)], {"bm25": len(bm), "vector": len(vec), "graph": len(graph)}


# ------------------------------------------------------------------ briefing 组装


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
    ts_list = sorted([str(it["node"].get("ts") or "") for it in items if it["node"].get("ts")])
    if ts_list:
        span = "｜ 时间跨度 " + short_ts(ts_list[0]) + " ~ " + short_ts(ts_list[-1])
    add("① 主题与范围：查询「" + summarize(query, 30) + "」｜ 范围：" + scope_text +
        "｜ 命中 " + str(len(items)) + " 条" + span)

    # ② 时间线
    add("② 时间线：")
    if not items:
        add("   （无匹配记忆）")
    else:
        ids = set(it["node"]["id"] for it in items)
        for it in sorted(items, key=lambda x: str(x["node"].get("ts") or "")):
            node = it["node"]
            rel = [r for r in json_list(node.get("refs")) if r in ids and r != node["id"]]
            link = ("  ← 关联 " + "、".join(rel[:2])) if rel else ""
            add("   - " + short_ts(node.get("ts")) + " [" + str(node.get("kind")) + "] " +
                summarize(node.get("text"), 38) + "（" + str(node.get("id")) + "）" + link)

    # ③ 现状
    add("③ 现状：")
    current = [it for it in items if it["node"].get("kind") in ("semantic", "preference", "skill", "correction")]
    if not current:
        add("   （无已巩固的结论；可用 cycle + consolidate 生成）")
    else:
        for it in current:
            node = it["node"]
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


# ------------------------------------------------------------------ 命令实现


def emit(text, quiet=False):
    if not quiet:
        print(text)


def fail(message):
    sys.stderr.write("错误：" + str(message) + NL)
    return 2


def clamp_limit(value, default=DEFAULT_LIMIT):
    """把 --limit 夹到 [1, MAX_LIMIT]；越界时 stderr 提示并截断（防一次拉整库）。"""
    try:
        n = int(value)
    except (TypeError, ValueError):
        return default
    if n > MAX_LIMIT:
        print("警告：--limit " + str(n) + " 超过上限 " + str(MAX_LIMIT) +
              "，已按 " + str(MAX_LIMIT) + " 执行", file=sys.stderr)
        return MAX_LIMIT
    if n < 1:
        print("警告：--limit " + str(n) + " 无效，已按 " + str(default) + " 执行", file=sys.stderr)
        return default
    return n


def read_text(raw):
    if raw == "-":
        return sys.stdin.read()
    return raw or ""


def resolve_scopes(args):
    scope = getattr(args, "scope", None)
    if scope:
        if str(scope).strip() == "all":
            return []
        return parse_list(scope)
    ns = getattr(args, "ns", None) or default_ns()
    if ns == "global":
        return ["global"]
    return [ns, "global"]


def pending_batches(conn, kinds=("episode",)):
    """待巩固批次。kinds 默认只有 episode；传 ("episode","note") 可把课堂笔记也算进来。"""
    kinds = tuple(kinds) or ("episode",)
    ph = ",".join("?" * len(kinds))
    base = "kind IN (" + ph + ") AND consolidated=0 AND archived=0"
    rows = conn.execute(
        "SELECT ns, COUNT(*) AS c FROM nodes WHERE " + base +
        " GROUP BY ns HAVING COUNT(*) >= ?", kinds + (CONSOLIDATE_MIN,)).fetchall()
    out = []
    for row in rows:
        ids = [x["id"] for x in conn.execute(
            "SELECT id FROM nodes WHERE ns=? AND " + base +
            " ORDER BY ts LIMIT 10", (row["ns"],) + kinds).fetchall()]
        out.append({"ns": row["ns"], "count": row["c"], "ids": ids})
    return out


def perfume_candidates(conn):
    rows = conn.execute("SELECT ns, tags FROM nodes WHERE kind='prefer' AND archived=0").fetchall()
    counts = {}
    for row in rows:
        for tag in json_list(row["tags"]):
            key = (row["ns"], tag)
            counts[key] = counts.get(key, 0) + 1
    out = [{"ns": k[0], "tag": k[1], "count": v} for k, v in counts.items() if v >= CONSOLIDATE_MIN]
    out.sort(key=lambda d: (-d["count"], d["ns"], d["tag"]))
    return out


def cmd_add(args, store):
    kind = (args.kind or "episode").strip()
    if kind == "consolidate":
        return fail("consolidate 请用 consolidate 子命令（需要 --from 来源）")
    banned = ("access", "sweep", "archive", "consolidate", "link", "correction")
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
                            args.include_ask, args.include_archived)
    briefing = build_briefing(query, scopes, items, conn)
    if args.json:
        payload = {
            "query": query, "scopes": scopes, "count": len(items), "channels": debug,
            "briefing": briefing,
            "items": [{"id": it["node"]["id"], "ns": it["node"]["ns"], "kind": it["node"]["kind"],
                       "ts": it["node"]["ts"], "text": it["node"]["text"],
                       "disclosure": it["node"]["disclosure"],
                       "score": round(it["score"], 4), "channels": it["channels"],
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
    if args.json:
        print(json.dumps({"bm25": [i[0] for i in bm], "vector": [i[0] for i in vec],
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
                                         "last_access", "archived", "superseded")},
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


def cmd_status(args, store):
    info = store.stats()
    conn, fresh = open_projection(store)
    nodes = conn.execute("SELECT COUNT(*) FROM nodes").fetchone()[0]
    archived = conn.execute("SELECT COUNT(*) FROM nodes WHERE archived = 1").fetchone()[0]
    ns_rows = conn.execute("SELECT ns, COUNT(*) AS c FROM nodes GROUP BY ns ORDER BY c DESC").fetchall()
    kind_rows = conn.execute("SELECT kind, COUNT(*) AS c FROM nodes GROUP BY kind ORDER BY c DESC").fetchall()
    batches = pending_batches(conn)
    vecs = conn.execute("SELECT COUNT(*) FROM vectors").fetchone()[0]
    vec_requested = vector_enabled()
    vec_allowed = vector_allowed(store) if vec_requested else False
    vec_available = vector_available() if vec_allowed else False
    payload = {"data_root": str(store.root), "events_files": info["files"],
               "events_lines": info["lines"], "bad_lines": info["bad_lines"],
               "nodes": nodes, "archived": archived,
               "by_ns": dict((r["ns"], r["c"]) for r in ns_rows),
               "by_kind": dict((r["kind"], r["c"]) for r in kind_rows),
               "pending_batches": len(batches), "vectors": vecs,
               "vector_installed": vector_installed(),
               "vector_enabled": vec_requested,
               "vector_allowed": vec_allowed,
               "vector_min_nodes": VECTOR_MIN_NODES,
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
        vec_state = "已启用"
    elif payload["vector_enabled"] and not payload["vector_allowed"]:
        vec_state = ("暂不使用（当前 " + str(nodes) + " 节点 < " + str(VECTOR_MIN_NODES) +
                     "；计划 2300+ 后再引入）")
    elif payload["vector_enabled"]:
        vec_state = "已请求启用但加载失败，降级 BM25 + 图扩散"
    else:
        vec_state = "未启用（按设计暂不使用；计划 2300+ 条后再引入）"
    print("- 待巩固批次：" + str(len(batches)) + " 个；向量：" + str(vecs) + " 条（通道" + vec_state + "）")
    conn.close()
    return 0


def export_filter(args):
    """把 --ns / --kind / --tag / 披露等级变成 SQL 条件（模块用它生成笔记与看板）。"""
    conds = []
    params = []
    # 导出默认排除 private / ask；要导出必须显式加 --include-private / --include-ask。
    if not getattr(args, "include_private", False):
        conds.append("COALESCE(disclosure, 'public') <> 'private'")
    if not getattr(args, "include_ask", False):
        conds.append("COALESCE(disclosure, 'public') <> 'ask'")
    nss = parse_list(getattr(args, "ns", None))
    if nss:
        conds.append("ns IN (" + ",".join(["?"] * len(nss)) + ")")
        params.extend(nss)
    kinds = parse_list(getattr(args, "kind", None))
    if kinds:
        conds.append("kind IN (" + ",".join(["?"] * len(kinds)) + ")")
        params.extend(kinds)
    tag_conds = []
    for tag in parse_list(getattr(args, "tag", None)):
        tag_conds.append("tags LIKE ?")
        params.append('%"' + tag + '"%')
    if tag_conds:
        conds.append("(" + " OR ".join(tag_conds) + ")")
    for tag in parse_list(getattr(args, "exclude_tag", None)):
        conds.append("tags NOT LIKE ?")
        params.append('%"' + tag + '"%')
    return conds, params


def render_note_block(node, fmt, lean, no_meta=False):
    """渲染单条记忆。lean 用于「给人看的笔记」，只留时间/类型/标签/正文。

    no_meta=True 时进一步去掉「时间 ｜ 类型 ｜ 摘要」抬头与标签行，只输出正文
    —— 供模块把正文本身写成结构化 markdown 的笔记使用（抬头属于机器元数据）。
    """
    tags = json_list(node.get("tags"))
    entities = json_list(node.get("entities"))
    原文 = str(node.get("text") or "").strip()
    # no_meta 的正文是人工写好的 markdown（可能含代码缩进），必须原样保留；
    # 其余模式为了单行可读，才把连续空白压成一个空格。
    text = 原文 if no_meta else re.sub(r"[ \t]+", " ", 原文).strip()
    kind = str(node["kind"])
    first_line = text.split(NL)[0].strip()
    if lean:
        if no_meta:
            return ([text, ""] if text else [])
        out = ["### " + short_ts(node.get("ts")) + " ｜ " + kind + " ｜ " + summarize(first_line, 44)]
        if fmt == "obsidian":
            tag_line = " ".join(["#" + t.replace(" ", "-") for t in tags])
            ent_line = " ".join(["[[" + e + "]]" for e in entities])
            meta = " ".join([x for x in (tag_line, ent_line) if x])
            if meta:
                out.append(meta)
        out.append("")
        if len(text) > len(first_line) or len(first_line) > 44:
            out.append(text)
            out.append("")
        return out
    head = "**[" + kind + "]** " + summarize(text, 60)
    out = ["- " + head]
    flags = []
    if node.get("archived"):
        flags.append("已归档")
    if node.get("superseded"):
        flags.append("已被更正")
    if node.get("disclosure") != "public":
        flags.append(str(node.get("disclosure")))
    d, s, e = decayed(node, age_days(node.get("ts")))
    out.append("  - id " + str(node["id"]) + " ｜ " + str(node["ts"]) +
               " ｜ S=%.2f D=%.2f E=%.2f（现 S=%.2f D=%.2f E=%.2f）｜ storage=%.2f retrieval=%.2f"
               % (node["S"], node["D"], node["E"], s, d, e, node["storage"], node["retrieval"]))
    if tags:
        out.append("  - 标签：" + "、".join(tags))
    if entities:
        out.append("  - 实体：" + "、".join(entities))
    if flags:
        out.append("  - 标记：" + "、".join(flags))
    refs = json_list(node.get("refs"))
    if refs:
        out.append("  - 关联：" + "、".join(refs))
    return out


SECTION_TAG_RE = re.compile(r"^第(\d+)-(\d+)节$")
CHAPTER_TAG_RE = re.compile(r"^第(\d+)章$")


def section_key(node):
    """按「第0-1节」「第0章」这类标签给出课程顺序键；章排在它自己的节之前。"""
    for tag in json_list(node.get("tags")):
        标签 = str(tag).strip()
        m = CHAPTER_TAG_RE.match(标签)
        if m:
            return (int(m.group(1)), -1)
        m = SECTION_TAG_RE.match(标签)
        if m:
            return (int(m.group(1)), int(m.group(2)))
    return (9999, 9999)


def collect_headings(body_lines):
    """从正文里抓 ## / ### 标题，供 --toc 生成目录。"""
    heads = []
    # body 里每一项可能是多行正文，先摊平成行再找标题，否则整段会被当成标题
    for line in NL.join(body_lines).split(NL):
        if line.startswith("### "):
            heads.append((3, line[4:].strip()))
        elif line.startswith("## "):
            heads.append((2, line[3:].strip()))
    return heads


def render_toc(heads, fmt):
    """把标题列表渲染成目录；obsidian 格式用 [[#标题]] 双链，便于点击跳转。"""
    if not heads:
        return []
    out = ["## 目录", ""]
    for level, text in heads:
        indent = "" if level == 2 else "  "
        out.append(indent + "- " + ("[[#" + text + "]]" if fmt == "obsidian" else text))
    out.append("")
    return out


def cmd_export(args, store):
    conn, _fresh = open_projection(store)
    out_path = Path(args.out) if args.out else (store.root / "memory.md")
    fmt = (getattr(args, "format", None) or "md").strip().lower()
    if fmt not in ("md", "obsidian"):
        conn.close()
        return fail("--format 只支持 md / obsidian")
    order_raw = (getattr(args, "order", None) or "desc").strip().lower()
    by_section = order_raw == "section"
    order_sql = "ASC" if (order_raw == "asc" or by_section) else "DESC"
    no_meta = bool(getattr(args, "no_meta", False))
    # --no-meta 的语义是「只输出正文」，因此它隐含 lean（否则会带 ## ns 与元数据行）。
    lean = bool(getattr(args, "lean", False) or no_meta)
    want_toc = bool(getattr(args, "toc", False))
    conds, params = export_filter(args)
    where = (" WHERE " + " AND ".join(conds)) if conds else ""
    ns_list = [r["ns"] for r in conn.execute(
        "SELECT DISTINCT ns FROM nodes" + where + " ORDER BY ns", params).fetchall()]
    title = getattr(args, "title", None) or "记忆导出视图"
    lines = []
    if fmt == "obsidian":
        lines.extend(["---", "generated: " + now_iso(), "source: mem.py export",
                      "tags:", "  - 记忆导出", "---", ""])
    lines.append("# " + title)
    lines.append("")
    lines.append("> 由记忆内核 mem.py export 生成；**可重建视图**，不是真相源（真相源是 events/*.jsonl）。")
    lines.append("> 生成时间 " + now_iso() + " ｜ 格式 " + fmt + " ｜ 排序 " +
                 ("按课程的章/节" if by_section else order_sql.lower()) +
                 (" ｜ 精简" if lean else "") + (" ｜ 无抬头" if no_meta else "") +
                 " ｜ 命名空间 " + str(len(ns_list)) + " 个")
    lines.append("")
    body = []
    for ns in ns_list:
        ns_conds = ["ns = ?"] + list(conds)
        ns_params = [ns] + list(params)
        rows = conn.execute("SELECT * FROM nodes WHERE " + " AND ".join(ns_conds) +
                            " ORDER BY ts " + order_sql, ns_params).fetchall()
        if by_section:
            rows = sorted(rows, key=lambda r: section_key(dict(r)))
        if not lean:
            body.append("## " + str(ns))
            body.append("")
        for row in rows:
            body.extend(render_note_block(dict(row), fmt, lean, no_meta))
        if not lean:
            body.append("")
    if want_toc:
        lines.extend(render_toc(collect_headings(body), fmt))
    lines.extend(body)
    content = NL.join(lines) + NL
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write(content)
    conn.close()
    if args.json:
        print(json.dumps({"out": str(out_path), "lines": content.count(NL),
                          "namespaces": ns_list, "format": fmt, "order": order_raw,
                          "lean": lean, "no_meta": no_meta}, ensure_ascii=False, indent=2))
    else:
        emit("已导出 " + str(out_path) + "（" + str(content.count(NL)) + " 行，" + str(len(ns_list)) +
             " 个命名空间）", args.quiet)
    return 0


def cmd_doctor(args, store):
    ok = True
    lines = ["记忆内核体检 doctor"]
    lines.append("- 脚本：" + str(SCRIPT_PATH))
    lines.append("- 库根：" + str(LIB_ROOT))
    root_state = "存在" if store.root.exists() else "不存在（命令会自动创建）"
    lines.append("- 数据根：" + str(store.root) + "（" + root_state + "）")
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
    if vector_loaded():
        lines.append("- 向量通道：已启用并加载成功（" + VECTOR_MODEL + "）")
    elif not vector_installed():
        lines.append("- 向量通道：不可用（未安装 sentence-transformers）⇒ 已降级为 BM25 + 图扩散")
    elif vector_enabled():
        if vector_allowed(store):
            lines.append("- 向量通道：已请求启用（本轮未加载验证；如需确认请跑一次向量命令）")
        else:
            lines.append("- 向量通道：已请求启用，但按设计暂不使用（当前节点 " +
                         str(projected_node_count(store)) + " < " + str(VECTOR_MIN_NODES) +
                         "；计划 2300+ 后再引入）")
    else:
        lines.append("- 向量通道：未启用（按设计暂不使用；计划节点 ≥" + str(VECTOR_MIN_NODES) +
                     " 后再引入；依赖已安装）")
    lines.append("- 结论：" + ("通过" if ok else "发现问题（见上）"))
    if args.json:
        print(json.dumps({"ok": ok, "lines": lines}, ensure_ascii=False, indent=2))
    else:
        print(NL.join(lines))
    return 0 if ok else 1


def fingerprint(root):
    root = Path(root)
    items = []
    if root.is_dir():
        for path in sorted(root.rglob("*")):
            if path.is_file():
                st = path.stat()
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                items.append([str(path.relative_to(root)), int(st.st_size), digest])
    return items


def run_cli(data_root, argv):
    cmd = [sys.executable, str(SCRIPT_PATH), "--data-root", str(data_root)] + list(argv)
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          universal_newlines=True, encoding="utf-8")
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def strength_snapshot(root):
    path = Path(root) / "index.sqlite"
    if not path.exists():
        return []
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT id, storage, retrieval, archived FROM nodes ORDER BY id").fetchall()
    conn.close()
    return [(r["id"], round(float(r["storage"]), 6), round(float(r["retrieval"]), 6), int(r["archived"]))
            for r in rows]


def cmd_selftest(args, store):
    results = []
    real_before = fingerprint(store.root)
    default_before = fingerprint(DEFAULT_DATA_ROOT)

    def step(name, passed, detail=""):
        results.append((name, bool(passed), detail))

    with tempfile.TemporaryDirectory(prefix="mem_selftest_") as tmp:
        root = Path(tmp) / "data"
        root.mkdir(parents=True, exist_ok=True)
        rc1, _o, e1 = run_cli(root, ["add", "--ns", "project:A", "--kind", "episode",
                                     "--text", "决定把记忆主存储做成 SQLite 事件流，不用 Markdown",
                                     "--tags", "记忆,架构", "--entities", "用户,SQLite"])
        rc2, _o, e2 = run_cli(root, ["add", "--ns", "project:A", "--kind", "episode",
                                     "--text", "确认内核要通用，将来跨项目共用同一数据根",
                                     "--tags", "记忆,通用", "--entities", "内核"])
        rc3, _o, e3 = run_cli(root, ["add", "--ns", "project:A", "--kind", "episode",
                                     "--text", "向量模型用 bge-small-zh-v1.5 做语义检索",
                                     "--tags", "记忆,向量"])
        rc4, _o, e4 = run_cli(root, ["add", "--ns", "project:B", "--kind", "episode",
                                     "--text", "B 项目的私密内容 记忆 内核 检索",
                                     "--disclosure", "private"])
        rc5, _o, e5 = run_cli(root, ["add", "--ns", "global", "--kind", "prefer",
                                     "--text", "用户偏好简短结论在前，再展开细节",
                                     "--tags", "沟通"])
        step("add 写入 5 条", rc1 == 0 and rc2 == 0 and rc3 == 0 and rc4 == 0 and rc5 == 0,
             e1 + e2 + e3 + e4 + e5)

        rc, out, err = run_cli(root, ["recall", "--ns", "project:A", "--query", "记忆 内核", "--json"])
        step("recall 退出码 0", rc == 0, err)
        data = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("briefing 六段齐全",
             all(mark in (data.get("briefing") or "") for mark in ("①", "②", "③", "④", "⑤", "⑥")))
        step("召回有命中", data.get("count", 0) >= 1)
        step("命名空间隔离（A 不返回 B 的 private）",
             all(it["ns"] != "project:B" for it in data.get("items", [])))

        rc, out, err = run_cli(root, ["recall", "--query", "偏好", "--scope", "global", "--json"])
        gdata = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("scope=global 只返回 global 层",
             rc == 0 and gdata.get("count", 0) >= 1 and
             all(it["ns"] == "global" for it in gdata.get("items", [])))

        rc, out, err = run_cli(root, ["cycle"])
        step("cycle 报出待巩固批次", rc == 0 and "consolidate --from" in out, err)

        src_ids = [it["id"] for it in data.get("items", []) if it["kind"] == "episode"][:3]
        step("有可巩固的来源", len(src_ids) >= 2)
        rc, out, err = run_cli(root, ["consolidate", "--from", ",".join(src_ids),
                                      "--text", "记忆内核＝append-only 事件流 + 可重建 SQLite 投影",
                                      "--kind", "semantic"])
        step("consolidate 成功", rc == 0, err)
        rc, out, err = run_cli(root, ["recall", "--ns", "project:A", "--query", "事件流", "--json"])
        step("巩固出的语义卡可被召回", rc == 0 and "semantic" in out, err)
        rc, out, err = run_cli(root, ["cycle"])
        step("巩固后批次被消化", rc == 0 and "consolidate --from" not in out, err)

        rc, out, err = run_cli(root, ["search", "--ns", "project:A", "--query", "记忆", "--json"])
        sdata = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("search 可执行且有命中", rc == 0 and len(sdata.get("bm25", [])) >= 1, err)

        rc, out, err = run_cli(root, ["links", "--id", src_ids[0]])
        step("links 可执行", rc == 0 and "节点 " in out, err)

        rc, out, err = run_cli(root, ["correct", "--target", src_ids[0],
                                      "--text", "更正：主存储仍是 SQLite 事件流，但导出视图改用复习卡"])
        step("correct 可执行", rc == 0, err)
        rc, out, err = run_cli(root, ["recall", "--ns", "project:A", "--query", "更正", "--dry-run"])
        step("correct 后 briefing 出现双时态", rc == 0 and "曾经" in out, err)

        rc, out, err = run_cli(root, ["export", "--ns", "project:A", "--quiet",
                                      "--out", str(root / "export.md")])
        step("export 可执行", rc == 0 and (root / "export.md").exists(), err)

        rc, out, err = run_cli(root, ["status", "--quiet"])
        step("--quiet 静默", rc == 0 and out.strip() == "", out + err)

        rc, out, err = run_cli(root, ["add", "--ns", "project:A", "--kind", "note",
                                      "--text", "课堂笔记：手打三行 printf 并先写预测再运行",
                                      "--tags", "笔记", "--json"])
        addj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("add --json 输出可解析", rc == 0 and bool(addj.get("id")), out + err)
        step("JSON 支持矩阵覆盖原六条命令",
             all(c in JSON_SUPPORTED for c in ("add", "cycle", "rebuild", "export",
                                               "consolidate", "correct")))

        rc, out, err = run_cli(root, ["cycle", "--json", "--dry-run"])
        cj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("cycle --json 输出可解析",
             rc == 0 and "batches" in cj and "note_batches" in cj and "perfume" in cj, out + err)

        rc, out, err = run_cli(root, ["cycle", "--kinds", "episode,note", "--dry-run"])
        step("cycle --kinds 可把 note 纳入批次", rc == 0 and "note" in out, out + err)

        rc, out, err = run_cli(root, ["recall", "--ns", "project:A", "--query", "记忆",
                                      "--limit", "9999", "--dry-run", "--json"])
        limj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("--limit 超上限被夹到 MAX_LIMIT",
             rc == 0 and "超过上限" in err and len(limj.get("items", [])) <= MAX_LIMIT, out + err)

        rc, out, err = run_cli(root, ["links", "--id", src_ids[0], "--json"])
        lj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("links --json 输出可解析",
             rc == 0 and (lj.get("node") or {}).get("id") == src_ids[0] and "out_edges" in lj,
             out + err)

        rc, out, err = run_cli(root, ["export", "--ns", "project:A", "--json",
                                      "--out", str(root / "export2.md")])
        ej = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("export --json 输出可解析", rc == 0 and ej.get("lines", 0) > 0, out + err)

        rc, out, err = run_cli(root, ["consolidate", "--from", ",".join(src_ids),
                                      "--text", "JSON 探测用的第二张语义卡", "--force", "--json"])
        sj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("consolidate --json 输出可解析", rc == 0 and bool(sj.get("id")), out + err)

        rc, out, err = run_cli(root, ["correct", "--target", src_ids[0],
                                      "--text", "再次更正（JSON 探测）", "--json"])
        corj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("correct --json 输出可解析", rc == 0 and corj.get("target") == src_ids[0], out + err)

        rc, out1, err = run_cli(root, ["recall", "--ns", "project:A", "--query", "记忆 内核", "--dry-run", "--json"])
        s1 = strength_snapshot(root)
        rc, out, err = run_cli(root, ["rebuild"])
        step("rebuild 退出码 0", rc == 0, err)
        s2 = strength_snapshot(root)
        rc, out2, err = run_cli(root, ["recall", "--ns", "project:A", "--query", "记忆 内核", "--dry-run", "--json"])
        order1 = [it["id"] for it in (json.loads(out1).get("items", []) if out1.strip() else [])]
        order2 = [it["id"] for it in (json.loads(out2).get("items", []) if out2.strip() else [])]
        step("rebuild 后强度完全复原", bool(s1) and s1 == s2)
        step("rebuild 后检索顺序一致", bool(order1) and order1 == order2)

        rc, out, err = run_cli(root, ["rebuild", "--json"])
        rj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("rebuild --json 输出可解析", rc == 0 and rj.get("nodes", 0) >= 5, out + err)

        rc, out, err = run_cli(root, ["status", "--json"])
        st = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("status 统计正确", st.get("events_lines", 0) >= 5 and st.get("nodes", 0) >= 5)

        rc, out, err = run_cli(root, ["doctor"])
        step("doctor 在临时根通过", rc == 0, err)

    real_after = fingerprint(store.root)
    step("真实数据根零改动", real_before == real_after,
         "前 " + str(len(real_before)) + " 个文件 → 后 " + str(len(real_after)) + " 个文件")
    step("库默认 _Data 零改动", default_before == fingerprint(DEFAULT_DATA_ROOT),
         "默认 _Data 前 " + str(len(default_before)) + " 个文件 → 后 " +
         str(len(fingerprint(DEFAULT_DATA_ROOT))) + " 个文件")

    passed = sum(1 for _n, ok_flag, _d in results if ok_flag)
    print("记忆内核自检 selftest（临时数据根，不碰真实库）")
    for name, ok_flag, detail in results:
        line = "  [" + ("通过" if ok_flag else "失败") + "] " + name
        if detail and not ok_flag:
            line += "  ← " + summarize(detail, 140)
        print(line)
    print("- 合计 " + str(passed) + "/" + str(len(results)) + " 项通过")
    return 0 if passed == len(results) else 1


# ------------------------------------------------------------------ 入口


def build_parser():
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--data-root", metavar="DIR", default=argparse.SUPPRESS,
                        help="数据根（优先于环境变量 MEM_DATA 与库内 _Data）")
    common.add_argument("--json", action="store_true", default=argparse.SUPPRESS,
                        help="机器可读输出")
    common.add_argument("--quiet", action="store_true", default=argparse.SUPPRESS,
                        help="静默（不打印正常输出）")
    common.add_argument("--vector", action="store_true", default=argparse.SUPPRESS,
                        help="向量通道（暂不使用；计划 2300+ 条后再引入；仅测试用 MEM_VECTOR_FORCE=1）")
    parser = argparse.ArgumentParser(
        prog="mem.py", parents=[common],
        description="记忆内核 CLI：事件流为唯一真相源，SQLite 为可重建投影。")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("add", parents=[common], help="写入一条记忆事件")
    p.add_argument("--ns", help="命名空间（默认 project:<库名>）")
    p.add_argument("--kind", default="episode",
                   help="记忆类型（episode / note / feel / prefer / decide）")
    p.add_argument("--text", required=True, help="正文；传 - 表示从标准输入读")
    p.add_argument("--tags", help="标签，逗号分隔")
    p.add_argument("--entities", help="实体，逗号分隔")
    p.add_argument("--refs", help="关联节点 id，逗号分隔")
    p.add_argument("--disclosure", choices=DISCLOSURES, help="披露等级（默认 public）")
    p.add_argument("--source", choices=SOURCES, help="来源（默认 user）")
    for key in ("S", "D", "E", "C", "I"):
        p.add_argument("--" + key, type=float, help="六变量 " + key + "（0~1）")
    p.add_argument("--dry-run", action="store_true", help="只打印将写入的事件")

    p = sub.add_parser("recall", parents=[common], help="检索并输出结构化 briefing")
    p.add_argument("--query", required=True)
    p.add_argument("--ns")
    p.add_argument("--scope", help="检索范围，逗号分隔；all 表示全部命名空间")
    p.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    p.add_argument("--include-ask", action="store_true", help="包含披露等级为 ask 的记忆")
    p.add_argument("--include-archived", action="store_true")
    p.add_argument("--dry-run", action="store_true", help="不写 access 事件（只读检索）")

    p = sub.add_parser("search", parents=[common], help="原始命中（调试用，不做 briefing）")
    p.add_argument("--query", required=True)
    p.add_argument("--ns")
    p.add_argument("--scope")
    p.add_argument("--limit", type=int, default=20)
    p.add_argument("--include-ask", action="store_true")
    p.add_argument("--include-archived", action="store_true")

    p = sub.add_parser("links", parents=[common], help="查看某节点的边与邻居（按 id 精确访问）")
    p.add_argument("--id", required=True)

    p = sub.add_parser("cycle", parents=[common], help="一次维护：遗忘扫描 + 待巩固 + 待熏习")
    p.add_argument("--ns")
    p.add_argument("--kinds", help="待巩固来源类型，逗号分隔（默认 episode；可写 episode,note）")
    p.add_argument("--dry-run", action="store_true")

    p = sub.add_parser("consolidate", parents=[common], help="把一批 episode 巩固成语义卡")
    p.add_argument("--from", dest="from_ids", required=True, help="来源 id，逗号分隔")
    p.add_argument("--text", required=True)
    p.add_argument("--kind", default="semantic", choices=CONSOLIDATE_KINDS)
    p.add_argument("--ns")
    p.add_argument("--tags")
    p.add_argument("--disclosure", choices=DISCLOSURES)
    p.add_argument("--force", action="store_true", help="允许重复巩固已巩固来源")
    p.add_argument("--dry-run", action="store_true")

    p = sub.add_parser("correct", parents=[common], help="追加更正事件（不改历史）")
    p.add_argument("--target", required=True, help="被更正的节点 id")
    p.add_argument("--text", required=True)
    p.add_argument("--ns")
    p.add_argument("--tags")
    p.add_argument("--disclosure", choices=DISCLOSURES)
    p.add_argument("--dry-run", action="store_true")

    p = sub.add_parser("rebuild", parents=[common], help="从事件流全量重建投影")

    p = sub.add_parser("status", parents=[common], help="统计与健康摘要")

    p = sub.add_parser("export", parents=[common], help="导出给人看的视图（可按条件过滤）")
    p.add_argument("--out", help="输出路径（默认 <数据根>/memory.md）")
    p.add_argument("--ns", help="只导出这些命名空间，逗号分隔")
    p.add_argument("--kind", help="只导出这些类型，逗号分隔（如 note,decide）")
    p.add_argument("--tag", help="只导出带这些标签的条目，逗号分隔")
    p.add_argument("--exclude-tag", help="排除带这些标签的条目，逗号分隔（如看板排除「状态」）")
    p.add_argument("--title", help="文档标题（默认「记忆导出视图」）")
    p.add_argument("--order", choices=("asc", "desc", "section"),
                   help="按时间正序 / 倒序（默认 desc）；section＝按「第0-1节」这类标签的章/节顺序排")
    p.add_argument("--lean", action="store_true", help="精简：只留时间 / 类型 / 正文，适合当笔记读")
    p.add_argument("--no-meta", action="store_true",
                   help="去掉「时间 ｜ 类型 ｜ 摘要」抬头与标签行，只输出正文（配合正文自带标题的笔记使用）")
    p.add_argument("--toc", action="store_true", help="按正文里的 ## / ### 标题生成目录")
    p.add_argument("--format", choices=("md", "obsidian"), help="md（默认）或 obsidian（加 frontmatter 与标签/双链）")
    p.add_argument("--include-private", action="store_true",
                   help="导出 disclosure=private 的条目（默认排除）")
    p.add_argument("--include-ask", action="store_true",
                   help="导出 disclosure=ask 的条目（默认排除）")

    p = sub.add_parser("doctor", parents=[common], help="只读体检")

    p = sub.add_parser("selftest", parents=[common], help="在临时数据根跑完整闭环，不碰真实库")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))
    args.json = bool(getattr(args, "json", False))
    args.quiet = bool(getattr(args, "quiet", False))
    if getattr(args, "vector", False):
        os.environ["MEM_VECTOR"] = "1"
    data_root = getattr(args, "data_root", None) or os.environ.get("MEM_DATA") or str(DEFAULT_DATA_ROOT)
    store = Store(Path(data_root))
    handlers = {
        "add": cmd_add, "recall": cmd_recall, "search": cmd_search, "links": cmd_links,
        "cycle": cmd_cycle, "consolidate": cmd_consolidate, "correct": cmd_correct,
        "rebuild": cmd_rebuild, "status": cmd_status, "export": cmd_export,
        "doctor": cmd_doctor, "selftest": cmd_selftest,
    }
    handler = handlers.get(args.cmd)
    if handler is None:
        return fail("未知命令：" + str(args.cmd))
    if args.json and args.cmd not in JSON_SUPPORTED:
        print("警告：命令 " + str(args.cmd) + " 暂不支持 --json，输出为文本", file=sys.stderr)
    requested = (bool(getattr(args, "vector", False)) or
                 str(os.environ.get("MEM_VECTOR", "")).strip().lower() in ("1", "true", "yes", "on"))
    if (requested and args.cmd in ("add", "recall", "search", "rebuild", "consolidate") and
            not vector_allowed(store)):
        print("向量通道暂不启用：当前节点 " + str(projected_node_count(store)) + " < " +
              str(VECTOR_MIN_NODES) + "（计划 2300+ 后再引入）；如确需测试，设 MEM_VECTOR_FORCE=1。",
              file=sys.stderr)
        return 2
    try:
        if args.quiet:
            import contextlib
            import io
            with contextlib.redirect_stdout(io.StringIO()):
                code = handler(args, store)
        else:
            code = handler(args, store)
    except KeyboardInterrupt:
        return fail("已中断")
    except sqlite3.DatabaseError as exc:
        return fail("投影数据库错误：" + str(exc) + "；可执行 rebuild 重建")
    return code


if __name__ == "__main__":
    sys.exit(main())
