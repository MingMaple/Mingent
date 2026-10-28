"""向量通道：对稠密后端（`mingmem.dense`）的薄适配。

口径（0.3.0 起）：
- **默认启用**：只要 onnxruntime + 模型在，就参与检索（缺任一则静默降级为 BM25 + 图扩散）；
- `MEM_VECTOR=0` 显式关闭（评测对比、排障用），不再有"节点数不到 2300 就不许用"的门槛；
- 向量以 float32 存 `.sqlite` 的 `vectors` 表（`id / dim / vec`），与投影一起可重建。

为什么存 float32 而不是 int8：包线是 10 万条 —— 512 维 × 4B × 10 万 = 200MB，仍在 300MB 预算内；
float32 让"同机同版本逐位一致"这条最干净。将来真到 30 万条以上，再把 `VECTOR_PRECISION`
切换成 int8 是局部改动（`pack_vec/unpack_vec` 两个函数 + 一次 rebuild）。
"""

import os
import sqlite3
import struct

from . import dense
from .settings import VECTOR_PRECISION

_PACK = {"fp32": "<%df"}


def vector_enabled():
    """是否允许使用稠密通道。默认开；`MEM_VECTOR=0/false/no/off` 显式关闭。"""
    flag = str(os.environ.get("MEM_VECTOR", "")).strip().lower()
    return flag not in ("0", "false", "no", "off")


def dense_available():
    return dense.available()


def dense_reason():
    return dense.reason()


def vector_installed():
    """依赖 + 模型是否齐（只读判定）。"""
    return dense.available()


def vector_available():
    return vector_enabled() and dense.available()


def vector_loaded():
    return vector_available()


def vector_allowed(store=None):
    """旧接口兼容：现在"允许"只取决于依赖与模型，不再看节点数。"""
    return vector_available()


def projected_node_count(store):
    """只读估算投影里的节点数（给 doctor/status 显示；不触发重建）。"""
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


def status():
    """给 doctor / status 用的一行式状态。"""
    info = dense.info()
    enabled = vector_enabled()
    return {"enabled": enabled, "available": bool(enabled and info["available"]),
            "model": info["model"], "dim": info["dim"], "runtime": info["runtime"],
            "precision": VECTOR_PRECISION,
            "reason": info["reason"] if not info["available"] else
            ("" if enabled else "MEM_VECTOR 显式关闭")}


def pack_vec(vec):
    return struct.pack(_PACK[VECTOR_PRECISION] % len(vec), *[float(x) for x in vec])


def unpack_vec(blob):
    return list(struct.unpack(_PACK[VECTOR_PRECISION] % (len(blob) // 4), blob))


def embed_texts(texts, is_query=False):
    """返回 numpy 数组 [n, dim]（已 L2 归一化）；通道不可用时返回 None。"""
    if not vector_enabled():
        return None
    return dense.embed(texts, is_query=is_query)


def embed_missing(conn, ids=None, batch=32):
    """把还没向量化的节点补齐（`ids` 给定时只处理这些）。返回本次写入条数。"""
    if not vector_available():
        return 0
    if ids:
        ph = ",".join(["?"] * len(ids))
        rows = conn.execute("SELECT id, text FROM nodes WHERE id IN (" + ph + ")",
                            list(ids)).fetchall()
    else:
        rows = conn.execute("SELECT n.id AS id, n.text AS text FROM nodes n "
                            "LEFT JOIN vectors v ON v.id = n.id WHERE v.id IS NULL").fetchall()
    rows = [r for r in rows if (r["text"] or "").strip()]
    if not rows:
        return 0
    written = 0
    for start in range(0, len(rows), batch):
        chunk = rows[start:start + batch]
        vecs = embed_texts([r["text"] for r in chunk], is_query=False)
        if vecs is None or len(vecs) != len(chunk):
            break
        for row, vec in zip(chunk, vecs):
            payload = pack_vec(vec)
            conn.execute("INSERT OR REPLACE INTO vectors (id, dim, vec) VALUES (?,?,?)",
                         (row["id"], len(payload) // 4, payload))
            written += 1
    if written:
        # 必须提交：写入路径（add/consolidate/correct）在 apply_event 之后没有统一的
        # commit 点，conn.close() 会把未提交的向量回滚掉（实测 doctor 显示 0/N 条）。
        conn.commit()
    return written
