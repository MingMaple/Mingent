import hashlib
import json
import os
import sqlite3
import subprocess
import sys
from pathlib import Path

from .chain import rechained_rows
from .settings import CONSOLIDATE_MIN, LIB_ROOT, NL
from .text import json_list, new_id, now_iso


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


NO_NODE_KINDS = ("access", "sweep", "archive", "link", "consent",
                "forget", "restore", "purge")


def expected_nodes(store):
    """只读：从事件流推出「投影里应该有哪些节点」，返回 (id → (文件名, 行号), 坏行数)。

    这是 doctor 的事件↔投影对账（G43）依据：投影是派生视图，事件流才是真相源，
    任何「事件有、投影没有」都说明投影漏应用了事件。
    """
    expect = {}
    bad = 0
    for path in store.event_files():
        try:
            fh = open(path, "r", encoding="utf-8")
        except OSError:
            continue
        with fh:
            for lineno, line in enumerate(fh, 1):
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
                if ev.get("kind") in NO_NODE_KINDS:
                    continue
                expect.setdefault(str(ev["id"]), (path.name, lineno))
    return expect, bad


def audit_ask(store, ns, query, ids, reason="include-ask"):
    """把一次「ask 级记忆被展示」写成审计事件（G20），返回该事件。

    只负责追加到事件流；回放进投影由调用方做（consent 事件不产生节点）。
    """
    ev = {"id": new_id("consent"), "ts": now_iso(), "ns": (ns or "global"),
          "kind": "consent", "text": "同意展示 ask 级记忆：" + str(len(ids)) + " 条",
          "reason": reason, "query": str(query), "targets": [str(x) for x in ids],
          "source": "user"}
    store.append(ev)
    return ev


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


def child_env(extra=None):
    """子进程环境：强制 UTF-8。

    为什么必须有：内核会起子进程并用 utf-8 解码它的输出。Windows 默认代码页是
    GBK/cp936，子进程若按地区代码页写、父进程按 utf-8 读，中文就会解码失败 ⇒
    断言莫名其妙地挂掉（表现为「普通 PowerShell 下 selftest rc=1」）。
    **调用方带不带 -X utf8 不该决定内核能不能跑。**
    """
    env = dict(os.environ)
    env["PYTHONUTF8"] = "1"
    env["PYTHONIOENCODING"] = "utf-8"
    if extra:
        env.update(extra)
    return env


def run_cli(data_root, argv):
    # R0 拆分后入口由脚本变成包模块（-m mingmem）：语义不变，只换调用形式并固定 cwd 到仓根。
    cmd = [sys.executable, "-X", "utf8", "-m", "mingmem", "--data-root", str(data_root)] + list(argv)
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          universal_newlines=True, encoding="utf-8",
                          cwd=str(LIB_ROOT), env=child_env())
    return proc.returncode, proc.stdout or "", proc.stderr or ""


PURGE_REF_KEYS = ("targets", "refs")


def purge_events(store, ids):
    """真删：物理重写事件流，删掉这些 id 的事件并清掉别处的引用。

    保留策略：事件行里其它字段原样不动；坏行原样保留（不因一次清理丢证据）；
    调用方须先写一条 purge 审计事件（它的 id 不在 ids 里，targets 为空，
    被删 id 只记在 purged 字段），所以清理过程不会连审计一起删掉。

    **重写后必须重新串链**：删掉行会让后续行的 prev_hash 全部对不上。这里把保留的
    事件行从 GENESIS 起重串一遍（坏行原样留、不入链），否则 verify 会把一次合法的
    真删报成「链断裂」。真删本身就是一次被审计的、有意的重写，重串链是它的代价。
    """
    targets = set(str(x) for x in ids)
    removed = 0
    touched = 0
    for path in store.event_files():
        kept = []
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                raw = line.strip()
                if not raw:
                    continue
                try:
                    ev = json.loads(raw)
                except ValueError:
                    kept.append(raw)
                    continue
                if not isinstance(ev, dict):
                    kept.append(raw)
                    continue
                if str(ev.get("id")) in targets:
                    removed += 1
                    continue
                changed = False
                for key in PURGE_REF_KEYS:
                    vals = ev.get(key)
                    if isinstance(vals, list):
                        left = [v for v in vals if str(v) not in targets]
                        if len(left) != len(vals):
                            ev[key] = left
                            changed = True
                if str(ev.get("src")) in targets:
                    ev["src"] = ""
                    changed = True
                if changed:
                    touched += 1
                kept.append(json.dumps(ev, ensure_ascii=False))
        tmp = Path(str(path) + ".purge-tmp")
        try:
            with open(tmp, "w", encoding="utf-8", newline="") as fh:
                for row in rechained_rows(kept):
                    fh.write(row + NL)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(str(tmp), str(path))
        except OSError:
            try:
                tmp.unlink()
            except OSError:
                pass
            raise
    return {"removed": removed, "touched": touched}


def run_cli_raw(argv, env_extra=None):
    """不带 --data-root 跑 CLI（清掉所有数据根环境变量），用于验证 D-02 的约束。"""
    cmd = [sys.executable, "-X", "utf8", "-m", "mingmem"] + list(argv)
    env = child_env()
    for key in ("MINGMEM_DATA", "MEM_DATA", "MINGMEM_NS", "MEM_NS"):
        env.pop(key, None)
    if env_extra:
        env.update(env_extra)
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          universal_newlines=True, encoding="utf-8", cwd=str(LIB_ROOT), env=env)
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
