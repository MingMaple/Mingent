import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from .core import child_env, fingerprint, run_cli, run_cli_raw, strength_snapshot
from .settings import JSON_SUPPORTED, LIB_ROOT, MAX_LIMIT
from .text import default_ns, summarize


def cmd_selftest(args, store):
    results = []
    real_before = fingerprint(store.root)

    def step(name, passed, detail=""):
        results.append((name, bool(passed), detail))

    # 协议/自描述的导入放在函数顶部：后面多处以"局部名"使用它们
    from .spec import COMMANDS, PROTOCOL, capabilities, describe

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

        # 审计/派生事件不许手写：否则调用方可以伪造 consent（同意）与 purge（真删）记录
        forge_ok = True
        for bad_kind in ("consent", "purge", "forget", "restore", "correction", "access"):
            rc, out, err = run_cli(root, ["add", "--ns", "project:A", "--kind", bad_kind,
                                          "--text", "伪造审计"])
            if rc != 2 or "不支持的 kind" not in err:
                forge_ok = False
        step("add 拒绝伪造审计/派生事件类型", forge_ok)
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

        # ---------------- A 阶段（P0 五条）回归项 ----------------
        def _events():
            found = []
            for ep in sorted((root / "events").glob("*.jsonl")):
                for line in ep.read_text(encoding="utf-8").splitlines():
                    if line.strip():
                        try:
                            found.append(json.loads(line))
                        except ValueError:
                            pass
            return found

        # G05：被更正（superseded）的节点默认不再进检索
        rc, out, err = run_cli(root, ["search", "--ns", "project:A", "--query", "记忆", "--json"])
        sup = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("G05 被 correct 的节点不再进检索",
             rc == 0 and src_ids[0] not in (sup.get("bm25") or []) and
             src_ids[0] not in (sup.get("nodes") or {}), out + err)

        # G20：展示 ask 级记忆必须留同意记录，且审计事件不产生节点
        rc, out, err = run_cli(root, ["add", "--ns", "project:A", "--kind", "episode",
                                      "--text", "ask 级：私事细节，需当次同意才可展示",
                                      "--disclosure", "ask", "--json"])
        askj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        rc, stag, err = run_cli(root, ["status", "--json"])
        nodes_before = (json.loads(stag) if stag.strip().startswith("{") else {}).get("nodes", 0)
        rc, out, err = run_cli(root, ["recall", "--ns", "project:A", "--query", "私事",
                                      "--include-ask", "--json"])
        aq = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        consent_hit = [ev.get("id") for ev in _events()
                       if ev.get("kind") == "consent" and
                       askj.get("id") in (ev.get("targets") or [])]
        step("G20 展示 ask 记忆留下同意审计",
             rc == 0 and bool(aq.get("ask_audit")) and bool(consent_hit), out + err)
        rc, st2, err = run_cli(root, ["status", "--json"])
        nodes_after = (json.loads(st2) if st2.strip().startswith("{") else {}).get("nodes", 0)
        step("G20 consent 事件不进节点表", nodes_after == nodes_before,
             str(nodes_before) + " → " + str(nodes_after))

        # G26：时间线按绝对时刻排，不按 ISO 字符串排
        # 10:00+08:00（=02:00Z）比 03:00Z 早；但字符串序会把 03:00 排前面。
        tz_path = root / "events" / "project-T.jsonl"
        early = {"id": "epis-tz-early", "ts": "2026-10-26T10:00:00+08:00", "ns": "project:T",
                 "kind": "episode", "text": "跨时区甲 较早的绝对时刻", "tags": [],
                 "entities": [], "refs": [], "disclosure": "public", "source": "user"}
        late = {"id": "epis-tz-late", "ts": "2026-10-26T03:00:00+00:00", "ns": "project:T",
                "kind": "episode", "text": "跨时区乙 较晚的绝对时刻", "tags": [],
                "entities": [], "refs": [], "disclosure": "public", "source": "user"}
        with open(tz_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(early, ensure_ascii=False) + chr(10))
            f.write(json.dumps(late, ensure_ascii=False) + chr(10))
        rc, out, err = run_cli(root, ["recall", "--ns", "project:T", "--query", "跨时区", "--json"])
        tj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        tbrief = tj.get("briefing") or ""
        pos_e = tbrief.find("epis-tz-early")
        pos_l = tbrief.find("epis-tz-late")
        step("G26 跨时区时间线按绝对时刻排序", rc == 0 and 0 <= pos_e < pos_l,
             "early@" + str(pos_e) + " late@" + str(pos_l))

        # G27：写锁不残留、每行事件完整可解析
        files_ok = True
        for ep in sorted((root / "events").glob("*.jsonl")):
            raw = ep.read_bytes()
            if raw and not raw.endswith(bytes([10])):
                files_ok = False
            for line in raw.split(bytes([10])):
                if line.strip():
                    try:
                        json.loads(line.decode("utf-8"))
                    except ValueError:
                        files_ok = False
        step("G27 无残留写锁且每行事件完整",
             (not (root / ".mingmem-lock").exists()) and files_ok)

        # G27b：持锁时间超过 LOCK_STALE 时仍要互斥（心跳刷新锁目录 mtime）。
        # 没有心跳时，第二个进程会把「还在用的锁」当残留删掉并自己抢锁 —— 互斥失效。
        # 为了不让自检多等 5 秒，这里把常量临时调小（只影响本进程的这个模块）。
        from . import store as store_mod
        old_stale, old_timeout = store_mod.LOCK_STALE, store_mod.LOCK_TIMEOUT
        probe_root = Path(tmp) / "lockprobe"
        probe_root.mkdir(parents=True, exist_ok=True)
        try:
            store_mod.LOCK_STALE = 0.4
            store_mod.LOCK_TIMEOUT = 0.35
            holder, other = store_mod.Store(probe_root), store_mod.Store(probe_root)
            blocked_while_held = False
            reacquired_after = False
            with holder.lock():
                time.sleep(0.9)   # > 2 × LOCK_STALE：无心跳时早该被判残留
                try:
                    with other.lock():
                        pass
                except TimeoutError:
                    blocked_while_held = True
            try:
                with other.lock():
                    reacquired_after = True
            except TimeoutError:
                reacquired_after = False
        finally:
            store_mod.LOCK_STALE, store_mod.LOCK_TIMEOUT = old_stale, old_timeout
        step("G27 持锁超 LOCK_STALE 仍互斥且释放后可再拿",
             blocked_while_held and reacquired_after and
             not (probe_root / ".mingmem-lock").exists(),
             "held=" + str(blocked_while_held) + " after=" + str(reacquired_after))

        # G28：等大小 + 同 mtime 的原地改写要被签名发现（旧 size+秒级 mtime 会漏）
        rc, out, err = run_cli(root, ["add", "--ns", "project:S", "--kind", "note",
                                      "--text", "签名测试甲乙", "--json"])
        sigj = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        sig_path = None
        for ep in sorted((root / "events").glob("*.jsonl")):
            if sigj.get("id") and sigj["id"] in ep.read_text(encoding="utf-8"):
                sig_path = ep
                break
        if sig_path is not None and sigj.get("id"):
            st0 = sig_path.stat()
            original = sig_path.read_text(encoding="utf-8")
            mutated = original.replace("签名测试甲乙", "签名测试甲丙", 1)
            sig_path.write_text(mutated, encoding="utf-8", newline="")
            os.utime(str(sig_path), ns=(st0.st_atime_ns, st0.st_mtime_ns))
            rc, out, err = run_cli(root, ["status", "--json"])
            db = sqlite3.connect(str(root / "index.sqlite"))
            row = db.execute("SELECT text FROM nodes WHERE id = ?", (sigj.get("id"),)).fetchone()
            db.close()
            step("G28 等大小同 mtime 的原地改写被签名发现",
                 rc == 0 and row is not None and "甲丙" in (row[0] or ""), "row=" + str(row))
            # 复原：这条改写会打断哈希链，不还原会让后面的 doctor 项误报
            sig_path.write_text(original, encoding="utf-8", newline="")
        else:
            step("G28 等大小同 mtime 的原地改写被签名发现", False, "找不到刚写入的事件文件")

        # G44：事件哈希链 —— 改/删/剥离都能被抓；purge 重写后要重新串链
        chain_root = Path(tmp) / "chaintest"
        chain_root.mkdir(parents=True, exist_ok=True)
        chain_ids = []
        for i in range(3):
            rc, out, err = run_cli(chain_root, ["add", "--ns", "project:C", "--kind", "note",
                                               "--text", "链测试第 " + str(i) + " 条", "--json"])
            if rc == 0 and out.strip().startswith("{"):
                chain_ids.append(json.loads(out)["id"])
        chain_file = chain_root / "events" / "project-C.jsonl"
        chain_raw = chain_file.read_bytes() if chain_file.exists() else b""
        chain_lines = [ln for ln in chain_raw.split(bytes([10])) if ln.strip()]
        all_chained = bool(chain_lines) and all(
            isinstance(json.loads(ln.decode("utf-8")).get("prev_hash"), str) for ln in chain_lines)
        rc, out, err = run_cli(chain_root, ["verify"])
        step("G44 事件带 prev_hash 且链完整",
             all_chained and rc == 0 and "链完整" in out and "结论：通过" in out, out + err)

        chain_file.write_bytes(chain_raw.replace("链测试第 1 条".encode("utf-8"),
                                                 "链测试第 9 条".encode("utf-8")))
        rc, out, err = run_cli(chain_root, ["verify"])
        # 断点落在被改行的**后继**行：链里存的是「上一行的哈希」，改第 2 行会让第 3 行对不上。
        # 这也是本地链的边界：改**最后一行**没有后继行可比，只有 --pin 的头部锚点能抓（见下一项）。
        step("G44 等长改写被 verify 抓出（含文件名与断点行号）",
             rc == 1 and "project-C.jsonl" in out and "断点：第 3 行" in out, out + err)
        chain_file.write_bytes(chain_raw)

        chain_file.write_bytes(bytes([10]).join(chain_lines[:1] + chain_lines[2:]) + bytes([10]))
        rc, out, err = run_cli(chain_root, ["verify"])
        step("G44 删除事件行被 verify 抓出", rc == 1 and "断裂" in out, out + err)
        chain_file.write_bytes(chain_raw)

        pin_path = Path(tmp) / "chain-pin.json"
        rc1, out1, _e1 = run_cli(chain_root, ["verify", "--pin", str(pin_path), "--json"])
        rc2, out2, _e2 = run_cli(chain_root, ["verify", "--pin", str(pin_path)])
        # 故意改**最后一行**：本地链抓不到（没有后继行引用它），只有头部锚点能抓
        chain_file.write_bytes(chain_raw.replace("链测试第 2 条".encode("utf-8"),
                                                 "链测试第 8 条".encode("utf-8")))
        rc3, out3, _e3 = run_cli(chain_root, ["verify", "--pin", str(pin_path)])
        step("G44 --pin 锚点能发现末行改写（本地链抓不到）",
             pin_path.exists() and rc1 == 0 and rc2 == 0 and rc3 == 1 and
             "不一致" in out3 and "断裂" not in out3,
             "rc=" + str((rc1, rc2, rc3)) + " | " + out3[:200])
        chain_file.write_bytes(chain_raw)

        if chain_ids:
            run_cli(chain_root, ["forget", "--ids", chain_ids[1]])
            rc, out, err = run_cli(chain_root, ["purge", "--ids", chain_ids[1], "--yes"])
            rc2, out2, _e2 = run_cli(chain_root, ["verify"])
            step("G44 purge 重写后链被重串（verify 仍通过）",
                 rc == 0 and rc2 == 0 and "结论：通过" in out2, out + " | " + out2)
        else:
            step("G44 purge 重写后链被重串（verify 仍通过）", False, "没有可用的测试 id")

        # G45：v1 旧库（SQLite-only）一次性导入，强度与状态必须保真、重复导入幂等
        legacy_root = Path(tmp) / "legacy-v1"
        legacy_root.mkdir(parents=True, exist_ok=True)
        ldb = sqlite3.connect(str(legacy_root / "index.sqlite"))
        ldb.execute("CREATE TABLE nodes (id TEXT PRIMARY KEY, ns TEXT, kind TEXT, text TEXT, ts TEXT,"
                    " S REAL, D REAL, E REAL, C REAL, I REAL, storage REAL, retrieval REAL,"
                    " last_access TEXT, disclosure TEXT, source TEXT, tags TEXT, entities TEXT,"
                    " refs TEXT, consolidated INTEGER, archived INTEGER, superseded INTEGER)")
        ldb.execute("CREATE TABLE edges (src TEXT, dst TEXT, kind TEXT, weight REAL, updated TEXT)")
        ldb.execute("INSERT INTO nodes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    ("note-legacy-1", "project:L", "note", "旧库第一条", "2026-10-01T10:00:00+08:00",
                     0.5, 0.6, 0.1, 0.1, 0.1, 0.40, 0.30, "2026-10-02T10:00:00+08:00",
                     "public", "user", '["旧"]', '[]', '[]', 0, 1, 0))
        ldb.execute("INSERT INTO nodes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    ("note-legacy-2", "project:L", "note", "旧库第二条（已更正）",
                     "2026-10-03T10:00:00+08:00", 0.5, 0.6, 0.1, 0.1, 0.1, 0.20, 0.90,
                     "2026-10-04T10:00:00+08:00", "ask", "ai", '[]', '[]', '[]', 0, 0, 1))
        ldb.execute("INSERT INTO edges VALUES (?,?,?,?,?)",
                    ("note-legacy-1", "note-legacy-2", "co", 0.4, "2026-10-05T10:00:00+08:00"))
        ldb.commit()
        ldb.close()
        rc, out, err = run_cli(root, ["import", str(legacy_root), "--json"])
        rc2, out2, _e2 = run_cli(root, ["import", str(legacy_root), "--json"])
        db = sqlite3.connect(str(root / "index.sqlite"))
        db.row_factory = sqlite3.Row
        row = db.execute("SELECT * FROM nodes WHERE id='note-legacy-1'").fetchone()
        row2 = db.execute("SELECT * FROM nodes WHERE id='note-legacy-2'").fetchone()
        edge = db.execute("SELECT * FROM edges WHERE src='note-legacy-1' AND dst='note-legacy-2'"
                          " AND kind='co'").fetchone()
        db.close()
        first = json.loads(out) if rc == 0 and out.strip().startswith("{") else {}
        second = json.loads(out2) if rc2 == 0 and out2.strip().startswith("{") else {}
        step("G45 v1 旧库导入保真（强度/状态/边权）且重复导入幂等",
             first.get("nodes") == 2 and first.get("edges") == 1 and
             second.get("nodes") == 0 and second.get("edges") == 0 and
             row is not None and abs(float(row["storage"]) - 0.40) < 1e-9 and
             abs(float(row["retrieval"]) - 0.30) < 1e-9 and int(row["archived"]) == 1 and
             row2 is not None and int(row2["superseded"]) == 1 and row2["disclosure"] == "ask" and
             edge is not None and abs(float(edge["weight"]) - 0.4) < 1e-9,
             "rc=" + str((rc, rc2)) + " | first=" + str(first)[:120] + " | edge=" + str(
                 None if edge is None else dict(edge)))

        # L2：serve --stdio（JSON Lines 薄适配层，行为必须与 CLI 一致）
        serve_root = Path(tmp) / "serve"
        serve_root.mkdir(parents=True, exist_ok=True)
        proc = subprocess.Popen([sys.executable, "-X", "utf8", "-m", "mingmem",
                                 "--data-root", str(serve_root), "serve", "--stdio"],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, encoding="utf-8",
                                cwd=str(LIB_ROOT), env=child_env())
        reqs = [{"id": 1, "op": "ping"},
                {"id": 2, "args": ["add", "--text", "服务通道写入", "--json"]},
                {"id": 3, "args": ["search", "--query", "服务通道", "--json"]},
                {"id": 4, "args": ["frobnicate"]},
                {"id": 5, "op": "exit"}]
        replies = []
        try:
            for req in reqs:
                proc.stdin.write(json.dumps(req, ensure_ascii=False) + chr(10))
            proc.stdin.flush()
            while len(replies) < len(reqs):
                line = proc.stdout.readline()
                if not line:
                    break
                replies.append(json.loads(line))
            proc.wait(timeout=60)
        finally:
            if proc.poll() is None:
                proc.kill()
        step("L2 serve --stdio 往返（ping/add/search/错误/exit）",
             len(replies) == len(reqs) and replies[0].get("pong") is True and
             replies[1].get("ok") and (replies[1].get("data") or {}).get("id") and
             replies[2].get("ok") and (replies[2].get("data") or {}).get("bm25") and
             replies[3].get("ok") is False and replies[3].get("code") == 2 and
             replies[4].get("bye") is True, str(replies)[:300])

        # P4：serve 协议 v1（hello / capabilities / describe / call / batch / 未知 op）。
        # 传输层已由上面的 L2 项覆盖，这里直接测协议语义（同一个 handle_request）。
        from .api import handle_request
        root_s = str(serve_root)
        hello = handle_request({"id": 1, "op": "hello", "protocol": PROTOCOL}, data_root=root_s)
        bad_proto = handle_request({"id": 2, "op": "hello", "protocol": "zzz/9"}, data_root=root_s)
        caps_resp = handle_request({"id": 3, "op": "capabilities"}, data_root=root_s)
        desc_resp = handle_request({"id": 4, "op": "describe", "command": "add"}, data_root=root_s)
        call_resp = handle_request({"id": 5, "op": "call", "command": "add",
                                    "params": {"text": "协议写入", "tags": ["协议"]}},
                                   data_root=root_s)
        bad_cmd = handle_request({"id": 6, "op": "call", "command": "frobnicate"}, data_root=root_s)
        batch = handle_request({"id": 7, "op": "batch", "items": [
            {"id": "a", "op": "call", "command": "status", "params": {}},
            {"id": "b", "op": "call", "command": "links", "params": {"id": "nope-1"}}]},
            data_root=root_s)
        bad_op = handle_request({"id": 8, "op": "nonsense"}, data_root=root_s)
        # P5：同标签不同版本号也必须被拒（旧实现只比 "/" 前的 "mmp"，mmp/9 会被放过）
        same_label_bad = handle_request({"id": 9, "op": "hello", "protocol": "mmp/9"},
                                       data_root=root_s)
        minor_ok = handle_request({"id": 10, "op": "hello", "protocol": "mmp/1.3"},
                                  data_root=root_s)
        step("P5 协议大版本按 / 后的数字判（mmp/9 拒、mmp/1.3 兼容）",
             same_label_bad["ok"] is False and same_label_bad["kind"] == "unsupported"
             and minor_ok["ok"] is True,
             "mmp/9=%s mmp/1.3=%s" % (same_label_bad.get("kind"), minor_ok.get("kind")))
        step("P4 serve 协议 v1（握手/能力/描述/调用/批量/未知 op）",
             hello["ok"] and hello["data"]["protocol"] == PROTOCOL and
             bad_proto["ok"] is False and bad_proto["kind"] == "unsupported" and
             caps_resp["ok"] and "commands" in (caps_resp.get("data") or {}) and
             desc_resp["ok"] and "params" in (desc_resp["data"] or {}).get("add", {}) and
             call_resp["ok"] and (call_resp.get("data") or {}).get("id") and
             bad_cmd["ok"] is False and bad_cmd["kind"] == "not_found" and
             batch["kind"] == "batch_partial" and len(batch.get("results") or []) == 2 and
             batch["results"][0]["ok"] and batch["results"][1]["ok"] is False and
             bad_op["kind"] == "unsupported",
             "hello=" + str(hello.get("data", {}).get("protocol")) + " batch=" + str(batch.get("kind")))

        # L3：MemoryCore（同进程 Python API，失败抛 MingmemError）
        from .api import MemoryCore, MingmemError
        core = MemoryCore(data_root=str(Path(tmp) / "apicore"), ns="project:api")
        core_added = core.add("第三层接口写入的一条", kind="note")
        core_rec = core.recall("第三层", dry_run=True)
        core_code = None
        try:
            core.links("nope-000000-0000")
        except MingmemError as exc:
            core_code = exc.code
        step("L3 MemoryCore 可 add/recall 且失败抛 MingmemError",
             bool(core_added.get("id")) and core_rec.get("count", 0) >= 1 and core_code == 2,
             str(core_added)[:160])

        # P1：自描述接口（capabilities / describe）—— 参数从 argparse 抽，不能漂移
        from .cli import build_parser
        from .settings import VERSION
        parser = build_parser()
        sub_actions = None
        for action in parser._actions:
            if hasattr(action, "choices") and isinstance(action.choices, dict):
                sub_actions = action.choices
                break
        caps = capabilities()
        described = describe()
        parser_commands = set(sub_actions.keys()) if sub_actions else set()
        missing_doc = sorted(parser_commands - set(COMMANDS))
        missing_parser = sorted(set(COMMANDS) - parser_commands)
        param_drift = []
        for name in sorted(parser_commands & set(described)):
            parser_params = set(a.dest for a in sub_actions[name]._actions if a.dest != "help")
            described_params = set(p["name"] for p in described[name]["params"])
            if parser_params != described_params:
                param_drift.append(name)
        step("P1 capabilities/describe 覆盖全部命令且参数零漂移",
             caps["protocol"] == PROTOCOL and caps["version"] == VERSION and
             not missing_doc and not missing_parser and not param_drift and
             len(described) == len(parser_commands),
             "missing_doc=" + str(missing_doc) + " missing_parser=" + str(missing_parser) +
             " drift=" + str(param_drift))

        rc, out, err = run_cli(root, ["describe", "recall", "--json"])
        one = json.loads(out) if rc == 0 and out.strip().startswith("{") else {}
        rc2, out2, err2 = run_cli(root, ["capabilities", "--json"])
        caps_json = json.loads(out2) if rc2 == 0 and out2.strip().startswith("{") else {}
        step("P1 describe/capabilities 可被 CLI 直接调用（--json 可解析）",
             rc == 0 and rc2 == 0 and "recall" in one and caps_json.get("protocol") == PROTOCOL and
             "errors" in caps_json and "commands" in caps_json, err + err2)

        # P2：通用调用（params 对象）与糖等价 + 错误分类稳定 + 幂等键
        proto_root = Path(tmp) / "protocol"
        proto_root.mkdir(parents=True, exist_ok=True)
        pcore = MemoryCore(data_root=str(proto_root), ns="project:P")
        via_params = pcore.call("add", text="参数对象写入", kind="note", tags=["协议"])
        via_sugar = pcore.add("糖式写入", kind="decide")
        r_params = pcore.call("recall", query="写入", limit=5, dry_run=True)
        r_sugar = pcore.recall("写入", limit=5, dry_run=True)
        step("P2 通用 call(command, params) 与糖等价",
             via_params.get("kind") == "note" and via_sugar.get("kind") == "decide" and
             r_params.get("count") == r_sugar.get("count") and r_params.get("count", 0) >= 1,
             str(via_params)[:100])

        kinds = {}
        for label, cmd, params in (("not_found", "links", {"id": "nope-000000-0000"}),
                                   ("invalid", "add", {"text": "   "}),
                                   ("usage", "add", {"text": "x", "bogus_param": 1})):
            resp = pcore.call_result(cmd, **params)
            kinds[label] = (resp["kind"], resp["code"])
        step("P2 错误分类是稳定枚举（not_found/invalid/usage）",
             kinds["not_found"] == ("not_found", 2) and kinds["invalid"] == ("invalid", 2) and
             kinds["usage"] == ("usage", 2), str(kinds))

        k1 = pcore.add("幂等键写入", client_key="selftest-key-1")
        k2 = pcore.add("幂等键写入", client_key="selftest-key-1")
        nodes_after = pcore.status()["nodes"]
        step("P2 client_key 幂等（重试不写第二条）",
             k1.get("id") == k2.get("id") and k2.get("idempotent") is True and
             nodes_after == 3, "nodes=" + str(nodes_after) + " k2=" + str(k2)[:80])

        # P2 补：consolidate 糖方法的参数名必须用 describe 的真实 dest（from_ids）。
        # 0.3.2 修：曾误写 "from"，导致 L3 层 consolidate 恒抛"未知参数 from"（serve/CLI 正常，L3 独挂）。
        src_a = pcore.add("巩固来源甲", kind="episode")
        src_b = pcore.add("巩固来源乙", kind="episode")
        cons = pcore.consolidate([src_a["id"], src_b["id"]], "语义卡：甲+乙", tags=["巩固"])
        step("P2 consolidate 糖方法用 from_ids 成功（参数名 = describe dest）",
             bool((cons or {}).get("id")) and cons.get("kind") == "semantic",
             str(cons)[:100])

        # P3：bin 分页
        pcore.call("forget", ids=[k1["id"]])
        page = pcore.call("bin", limit=1, offset=0)
        step("P3 bin 支持 limit/offset 且给出 total",
             page.get("total") == 1 and len(page.get("items") or []) == 1, str(page)[:150])

        # G43：doctor 事件↔投影对账
        run_cli(root, ["rebuild"])
        rc, out, err = run_cli(root, ["doctor"])
        step("G43 对账一致时 doctor 通过", rc == 0 and "事件↔投影对账：一致" in out, out + err)
        del_id = src_ids[1] if len(src_ids) > 1 else src_ids[0]
        db = sqlite3.connect(str(root / "index.sqlite"))
        db.execute("DELETE FROM nodes WHERE id = ?", (del_id,))
        db.commit()
        db.close()
        rc, out, err = run_cli(root, ["doctor"])
        step("G43 缺失节点时 doctor 报错并指认事件",
             rc == 1 and "缺失事件" in out and del_id in out, "rc=" + str(rc) + " | " + out[:300])

        # G17：单字查询必须能命中（索引侧有单字，查询侧仍是 bigram）
        rc, out, err = run_cli(root, ["search", "--ns", "project:A", "--query", "核", "--json"])
        single = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("G17 单字查询可命中", rc == 0 and len(single.get("bm25") or []) >= 1, out[:200])

        # ---------------- G14：回收站（软删 → bin → 恢复 → 真删） ----------------
        rb1 = json.loads(run_cli(root, ["add", "--ns", "project:A", "--kind", "note",
                                        "--text", "回收站测试甲：先软删再真删", "--json"])[1]).get("id")
        rb2 = json.loads(run_cli(root, ["add", "--ns", "project:A", "--kind", "note",
                                        "--text", "回收站测试乙：验证真删必须先软删", "--json"])[1]).get("id")
        rc, out, err = run_cli(root, ["purge", "--ids", rb2, "--yes"])
        step("G14 未软删不许真删", rc == 2 and rb2 is not None, "rc=" + str(rc) + " " + err[:160])

        rc, out, err = run_cli(root, ["forget", "--ids", rb1, "--reason", "回收站测试", "--json"])
        fj = json.loads(out) if out.strip().startswith("{") else {}
        rc2, out2, err2 = run_cli(root, ["recall", "--ns", "project:A", "--query", "回收站",
                                         "--dry-run", "--json"])
        step("G14 软删后检索不可见", rc == 0 and fj.get("erased") == [rb1] and rb1 not in out2,
             err + err2)

        rc, out, err = run_cli(root, ["recall", "--query", "回收站", "--scope", "all",
                                        "--include-archived", "--include-ask",
                                        "--dry-run", "--json"])
        rc2, out2, _e2 = run_cli(root, ["search", "--query", "回收站", "--scope", "all",
                                         "--include-ask", "--json"])
        step("G14 软删项没有任何检索旁路（scope=all 也不行）",
             rc == 0 and rc2 == 0 and rb1 not in out and rb1 not in out2,
             out[:160] + " | " + out2[:160])

        rc, out, err = run_cli(root, ["bin", "--json"])
        bj = json.loads(out) if out.strip().startswith("{") else {}
        step("G14 bin 能列出已删项",
             rc == 0 and any(it.get("id") == rb1 for it in (bj.get("items") or [])), out[:200])

        rc, out, err = run_cli(root, ["export", "--ns", "project:A", "--out", str(root / "bin.md")])
        exported = (root / "bin.md").read_text(encoding="utf-8") if (root / "bin.md").exists() else ""
        step("G14 导出视图不含回收站条目", rc == 0 and "回收站测试甲" not in exported)

        rc, out, err = run_cli(root, ["restore", "--ids", rb1, "--json"])
        rc2, out2, _e2 = run_cli(root, ["recall", "--ns", "project:A", "--query", "回收站",
                                        "--dry-run", "--json"])
        step("G14 restore 后重新可见", rc == 0 and rb1 in out2, err[:160])

        run_cli(root, ["forget", "--ids", rb1])
        rc, out, err = run_cli(root, ["purge", "--ids", rb1, "--yes", "--json"])
        pj = json.loads(out) if out.strip().startswith("{") else {}
        all_events = ""
        for ef in sorted((root / "events").glob("*.jsonl")):
            all_events += ef.read_text(encoding="utf-8")
        rc2, out2, err2 = run_cli(root, ["doctor"])
        step("G14 真删后事件流里查不到该 id",
             rc == 0 and pj.get("removed_events", 0) >= 1 and rb1 not in all_events,
             "removed=" + str(pj.get("removed_events")) + " " + err[:160])
        ok_audit = any(ev.get("kind") == "purge" and int(ev.get("purged_count") or 0) >= 1
                       for ev in _events())
        step("G14 真删留审计但只留哈希", ok_audit) 
        step("G14 真删后 doctor 仍一致", rc2 == 0 and "对账：一致" in out2, "rc=" + str(rc2) + " " + out2[-160:])

        # E-评测集：扩容后仍可加载、分桶齐全（用例数与 id 引用由 load_* 自身校验）
        from . import eval as evalmod
        try:
            _seed = evalmod.load_seed()
            _cases = evalmod.load_cases(_seed)
            _cats = set(c.get("cat") for c in _cases)
            _ok = (len(_cases) >= 150 and len(_seed) >= 200
                   and {"direct", "alias", "negative", "privacy"} <= _cats)
            _detail = "seed=%d cases=%d 分桶=%s" % (len(_seed), len(_cases), sorted(_cats))
        except SystemExit as exc:
            _ok, _detail = False, str(exc)
        step("E-评测集可加载且分桶齐全（扩容到 150+ 用例）", _ok, _detail)

        # ---------------- R01–R03：最低相关分 + 词边界（0.2.1 新增） ----------------
        rroot = Path(tmp) / "r-data"
        rdocs = [
            ("记忆内核的投影可以随时从事件流重建，事件流是唯一真相源", "core"),
            ("回忆内容时先看简报的六段结构，再决定要不要深入", ""),
            ("今天学习 Python asyncio 的调度模型，理解了事件循环", ""),
            ("笔记：Mingent 的 Gradle 构建固定用 JDK 17", "笔记"),
            ("决定：所有写操作必须带幂等键，重试不会写第二条", ""),
            ("周末去爬山，天气不错，心情很好", ""),
            ("内核调度策略先按简单优先级来，不做抢占", "调度"),
            ("设计文档里写了为什么会这样分层，别只看代码", ""),
            ("记忆力训练每天十分钟，比突击更有效", ""),
            ("内核版本记录在 CHANGELOG，不一致就是有人改了代码", ""),
        ]
        rid = {}
        for i, (text, tag) in enumerate(rdocs, 1):
            _rc, out_i, _e = run_cli(rroot, ["add", "--ns", "project:R", "--text", text,
                                             "--client-key", "r%02d" % i, "--json"])
            if tag and out_i.strip().startswith("{"):
                rid[tag] = json.loads(out_i).get("id")

        rc, out, err = run_cli(rroot, ["recall", "--ns", "project:R", "--query", "忆内", "--json"])
        rj = json.loads(out) if out.strip().startswith("{") else {}
        hit_ids = [it["id"] for it in (rj.get("items") or [])]
        # 0.3.0 起稠密通道会把"最近的几条"也返回（那是它的正常工作方式），
        # 所以这条守的是**词面通道不外溢**：记忆内核那条不许带词面证据出现。
        core_ev = [it.get("evidence") for it in (rj.get("items") or []) if it["id"] == rid.get("core")]
        step("R01 接缝 bigram 不外溢（「忆内」不给「记忆内核」任何词面证据）",
             rc == 0 and bool(rid.get("core")) and all((e or 0) <= 0 for e in core_ev),
             "记忆内核条 evidence=" + str(core_ev) + " ｜ " + json.dumps(rj.get("gate"), ensure_ascii=False))

        rc, out, err = run_cli(rroot, ["recall", "--ns", "project:R", "--query", "笔记", "--json"])
        rj2 = json.loads(out) if out.strip().startswith("{") else {}
        items2 = rj2.get("items") or []
        step("R02 单词查询不被图扩散邻居凑满；稠密补的条目必须带 dense_sim",
             rc == 0 and all(not it.get("filler") for it in items2)
             and all((it.get("evidence") or 0) > 0 or (it.get("dense_sim") or 0) >= 0.30
                     for it in items2),
             str(len(items2)) + " 条 ｜ " + json.dumps(rj2.get("gate"), ensure_ascii=False))

        rc, out, err = run_cli(rroot, ["recall", "--ns", "project:R", "--query", "的", "--json"])
        rj3 = json.loads(out) if out.strip().startswith("{") else {}
        rc2, out2, _e2 = run_cli(rroot, ["recall", "--ns", "project:R", "--query", "笔记",
                                         "--min-score", "0", "--json"])
        rj4 = json.loads(out2) if out2.strip().startswith("{") else {}
        step("R03 纯虚词查询 0 条（稠密通道也不兜底）；关掉门槛也不放行零证据条目",
             rc == 0 and rc2 == 0 and rj3.get("count") == 0 and rj4.get("count", 99) <= 5,
             "虚词 " + str(rj3.get("count")) + " 条 ｜ 门槛 0 时 " + str(rj4.get("count")) + " 条")

        # ---------------- D-02：数据根必须显式 ----------------
        cfg_missing = Path(tmp) / "no_such_config.json"
        rc, _out, err = run_cli_raw(["status", "--json"], {"MINGMEM_CONFIG": str(cfg_missing)})
        step("D-02 无配置时退出码 2", rc == 2, "rc=" + str(rc) + " " + err[:160])
        step("D-02 报错给出 init 修复命令", "init --data-root" in err, err[:160])

        cfg_path = Path(tmp) / "cfg.json"
        fresh = Path(tmp) / "fresh-data"
        rc1, _o1, err1 = run_cli_raw(["init", "--data-root", str(fresh)],
                                     {"MINGMEM_CONFIG": str(cfg_path)})
        rc2, _o2, err2 = run_cli_raw(["add", "--ns", "global", "--text", "初始化后免 --data-root",
                                      "--quiet"], {"MINGMEM_CONFIG": str(cfg_path)})
        rc3, out3, _e3 = run_cli_raw(["status", "--json"], {"MINGMEM_CONFIG": str(cfg_path)})
        step("D-02 init 后免 --data-root 可读写",
             rc1 == 0 and rc2 == 0 and rc3 == 0 and cfg_path.exists() and fresh.is_dir(),
             str(rc1) + "/" + str(rc2) + "/" + str(rc3) + " " + err1[:120] + err2[:120])
        step("D-02 status 指向配置的数据根", "fresh-data" in out3, out3[:200])

        # ---------------- D-03：空库 doctor 不许崩，且不许有写副作用 ----------------
        # 0.3.2 实测：空数据根执行 doctor，稠密通道那行引用未绑定的 nodes 抛
        # UnboundLocalError（rc=1 + traceback）；且 connect() 会凭空建出空 index.sqlite。
        empty_root = Path(tmp) / "empty-data"
        empty_root.mkdir(parents=True, exist_ok=True)
        rc, out, err = run_cli(empty_root, ["doctor", "--json"])
        ej = json.loads(out) if (rc == 0 and out.strip().startswith("{")) else {}
        step("D-03 空库 doctor 不崩（rc=0、无 traceback、ok=true）",
             rc == 0 and "Traceback" not in err and ej.get("ok") is True,
             "rc=" + str(rc) + " " + (err[:200] + out[:200]))
        step("D-03 空库 doctor 无写副作用（不生成 index.sqlite）",
             not (empty_root / "index.sqlite").exists(),
             "index.sqlite 存在=" + str((empty_root / "index.sqlite").exists()))

    real_after = fingerprint(store.root)
    step("真实数据根零改动", real_before == real_after,
         "前 " + str(len(real_before)) + " 个文件 → 后 " + str(len(real_after)) + " 个文件")
    step("默认命名空间项目层优先（不是 global）", default_ns() != "global", default_ns())

    passed = sum(1 for _n, ok_flag, _d in results if ok_flag)
    print("记忆内核自检 selftest（临时数据根，不碰真实库）")
    for name, ok_flag, detail in results:
        line = "  [" + ("通过" if ok_flag else "失败") + "] " + name
        if detail and not ok_flag:
            line += "  ← " + summarize(detail, 140)
        print(line)
    print("- 合计 " + str(passed) + "/" + str(len(results)) + " 项通过")
    return 0 if passed == len(results) else 1
