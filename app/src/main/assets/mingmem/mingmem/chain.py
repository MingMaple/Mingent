import hashlib
import json
import os
from pathlib import Path

from .settings import SIG_TAIL_BYTES

GENESIS = "GENESIS"
NL_BYTES = bytes([10])


def _parse(raw):
    try:
        ev = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    return ev if isinstance(ev, dict) else None


def _chained(ev):
    return isinstance(ev, dict) and isinstance(ev.get("prev_hash"), str) and ev.get("prev_hash")


def line_hash(raw):
    """一行事件的链值：**原始字节**的 sha256（不含行尾换行）。"""
    return hashlib.sha256(raw).hexdigest()


def append_prev_hash(path):
    """算出「下一条要追加的事件」的 prev_hash。

    规则：prev_hash = 上一条**已入链**事件原始字节的 sha256；
    文件里还没有入链事件时，锚定「此前所有字节」的 sha256（覆盖升级前的历史行）；
    文件不存在或为空时用 GENESIS。坏行（非 JSON）不入链，链会跨过它们。
    """
    path = Path(path)
    try:
        size = path.stat().st_size
    except OSError:
        return GENESIS
    if size == 0:
        return GENESIS
    with open(str(path), "rb") as fh:
        fh.seek(max(0, size - SIG_TAIL_BYTES))
        chunk = fh.read()
    rows = chunk.split(NL_BYTES)
    if size > SIG_TAIL_BYTES:
        rows = rows[1:]          # 首段可能被截断，丢掉
    for raw in reversed([r for r in rows if r.strip()]):
        ev = _parse(raw)
        if _chained(ev):
            return line_hash(raw)
    data = path.read_bytes()     # 尾部没有入链行：整文件扫一遍（只发生一次）
    rows = [r for r in data.split(NL_BYTES) if r.strip()]
    for raw in reversed(rows):
        if _chained(_parse(raw)):
            return line_hash(raw)
    # 全是历史行（升级前写入的）：把「此前所有非空行」锚成一条前缀摘要
    return prefix_digest(rows)


def prefix_digest(rows):
    """历史行的前缀摘要（忽略空行，与 verify 的口径一致）。"""
    h = hashlib.sha256()
    for raw in rows:
        if raw.strip():
            h.update(raw + NL_BYTES)
    return h.hexdigest()


def verify_file(path):
    """校验单个事件文件的链；返回统计与断点（行号 1 起）。"""
    path = Path(path)
    data = path.read_bytes()
    rows = data.split(NL_BYTES)
    if rows and rows[-1] == b"":
        rows.pop()
    breaks = []
    chained = 0
    unchained = 0
    prev_chained = None
    prefix = hashlib.sha256()
    prefix_len = 0
    for idx, raw in enumerate(rows, 1):
        if not raw.strip():      # 空行不入链、也不计入前缀（编辑造成的空行不该破坏链）
            continue
        ev = _parse(raw)
        got = ev.get("prev_hash") if isinstance(ev, dict) else None
        if isinstance(got, str) and got:
            if prev_chained is not None:
                expect = prev_chained
            elif prefix_len:
                expect = prefix.hexdigest()   # 历史行锚点
            else:
                expect = GENESIS
            if got != expect:
                breaks.append({"line": idx, "expect": expect, "got": got})
            chained += 1
            prev_chained = line_hash(raw)
        elif isinstance(ev, dict) and ev.get("id"):
            unchained += 1
            if prev_chained is not None:
                # 链已开始却出现未入链的事件行 —— 有人把 prev_hash 剥掉了
                breaks.append({"line": idx, "expect": "prev_hash 字段", "got": "缺失"})
        else:
            unchained += 1
        prefix.update(raw + NL_BYTES)
        prefix_len += len(raw) + 1
    return {"file": path.name, "lines": len([r for r in rows if r.strip()]),
            "chained": chained, "unchained": unchained, "breaks": breaks,
            "ok": not breaks, "head": hashlib.sha256(data).hexdigest()}


def verify_store(store, ns=None):
    files = store.event_files()
    if ns:
        want = store.ns_path(ns)
        files = [p for p in files if p == want]
    return [verify_file(p) for p in files]


def rechained_rows(rows):
    """把一批事件行重新串成一条从 GENESIS 开始的链（purge 重写文件时用）。"""
    out = []
    prev = GENESIS
    for row in rows:
        ev = _parse(row.encode("utf-8")) if isinstance(row, str) else None
        if ev is not None:
            ev["prev_hash"] = prev
            line = json.dumps(ev, ensure_ascii=False)
        else:
            line = row
        out.append(line)
        prev = line_hash(line.encode("utf-8"))
    return out


def read_pin(path):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def write_pin(path, heads):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(str(path) + ".tmp")
    tmp.write_text(json.dumps(heads, ensure_ascii=False, indent=2, sort_keys=True) + chr(10),
                   encoding="utf-8", newline="")
    os.replace(str(tmp), str(path))
    return path
