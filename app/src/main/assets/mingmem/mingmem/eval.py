"""离线评测与门禁：在临时数据根重建固定语料，跑 30 条用例。

用法：
    python -X utf8 -m mingmem.eval [--gate] [--verbose]

指标：recall@5 / mrr@5 / redundancy@5 / leak_count / section_fill /
current_rate / negative_pass / avg_hits（定义见 evalset/README.md）。
真实数据根只读：语料在本进程用 tempfile 建的临时根里重建，跑完即删。
"""
import argparse
import json
import os
import re
import sys
import tempfile
from pathlib import Path

from .briefing import build_briefing
from .projection import open_projection
from .retrieve import retrieve
from .config import load_config
from .settings import LEGACY_DATA_ROOT, LIB_ROOT
from .store import Store


EVAL_DIR = Path(__file__).resolve().parent / "evalset"
BASELINE_DENSE_PATH = EVAL_DIR / "baseline_dense.json"
SEED_PATH = EVAL_DIR / "seed.jsonl"
CASES_PATH = EVAL_DIR / "cases.jsonl"
BASELINE_PATH = EVAL_DIR / "baseline.json"
REPORT_PATH = EVAL_DIR / "report.json"

TOP_K = 5
REDUNDANCY_JACCARD = 0.6
MIN_SEED, MAX_SEED = 40, 600          # 语料规模：40~60 是初版，扩容后到几百条
MIN_CASES, MAX_CASES = 30, 500        # 用例规模：core 30 条 + 扩容桶
GATE_MARGIN = 0.05
SECTIONS = ("①", "②", "③", "④", "⑤", "⑥")
HEAD_RE = re.compile(r"^([①②③④⑤⑥])\s*(.*)$")
REQUIRED_EVENT_KEYS = ("id", "ts", "ns", "kind", "text", "tags", "entities", "refs",
                       "disclosure", "source")


def _read_jsonl(path):
    rows = []
    with open(str(path), "r", encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except ValueError as exc:
                raise SystemExit("无法解析 " + str(path) + " 第 " + str(lineno) + " 行：" + str(exc))
    return rows


def load_seed():
    seed = _read_jsonl(SEED_PATH)
    if not MIN_SEED <= len(seed) <= MAX_SEED:
        raise SystemExit("seed.jsonl 应在 %d~%d 条之间，实际 %d" % (MIN_SEED, MAX_SEED, len(seed)))
    seen = set()
    for ev in seed:
        missing = [k for k in REQUIRED_EVENT_KEYS if k not in ev]
        if missing:
            raise SystemExit("事件 " + str(ev.get("id")) + " 缺字段：" + "、".join(missing))
        if not str(ev.get("id") or "").strip():
            raise SystemExit("seed.jsonl 存在空 id")
        if ev["id"] in seen:
            raise SystemExit("seed.jsonl 存在重复 id：" + str(ev["id"]))
        seen.add(ev["id"])
    return seed


def load_cases(seed):
    """读全部用例文件：`cases.jsonl`（历史核心 30 条，一字不动）+ `cases_ext.jsonl`（扩容桶）。

    拆成两个文件的原因：核心集是历史回归基准，扩容会改变难度但不应改动它的每一条；
    分开存才能一眼看出「哪部分是新增的度量口径」。
    """
    cases = []
    for path in (CASES_PATH, CASES_PATH.with_name("cases_ext.jsonl")):
        if Path(path).exists():
            cases.extend(_read_jsonl(path))
    if not MIN_CASES <= len(cases) <= MAX_CASES:
        raise SystemExit("用例总数应在 %d~%d 条之间，实际 %d" % (MIN_CASES, MAX_CASES, len(cases)))
    ids = set(ev["id"] for ev in seed)
    seen = set()
    for case in cases:
        for key in ("id", "ns", "query", "gold", "must_not", "note"):
            if key not in case:
                raise SystemExit("用例 " + str(case.get("id")) + " 缺字段：" + key)
        case.setdefault("cat", "core")
        if case["id"] in seen:
            raise SystemExit("用例文件存在重复 id：" + str(case["id"]))
        seen.add(case["id"])
        for gid in list(case["gold"]) + list(case["must_not"]):
            if gid not in ids:
                raise SystemExit("用例 " + case["id"] + " 引用了不存在的事件：" + str(gid))
    return cases


def scopes_for(ns):
    """与 CLI 口径一致：global 只查 global；project:X 查 X + global；all 查全部。"""
    ns = (ns or "").strip()
    if ns in ("", "all", "*"):
        return []
    if ns == "global":
        return ["global"]
    return [ns, "global"]


def bigrams(text):
    t = re.sub(r"\s+", "", str(text or "")).lower()
    if len(t) < 2:
        return set()
    return set(t[i:i + 2] for i in range(len(t) - 1))


def jaccard(text_a, text_b):
    a = bigrams(text_a)
    b = bigrams(text_b)
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return len(a & b) / float(len(a | b))


def section_stats(briefing):
    """逐段判断有没有真内容；「（无…）」占位算空。① 的标题行本身就是内容。"""
    filled = dict((s, False) for s in SECTIONS)
    current = None
    for line in str(briefing or "").split("\n"):
        match = HEAD_RE.match(line)
        if match:
            current = match.group(1)
            if current == "①":
                filled[current] = True
            continue
        if current is None:
            continue
        body = line.strip()
        if not body or body.startswith("（无"):
            continue
        filled[current] = True
    return {"count": sum(1 for s in SECTIONS if filled[s]),
            "current": bool(filled["③"]),
            "filled": [s for s in SECTIONS if filled[s]]}


def _assert_isolated(root):
    root = Path(root).resolve()
    guards = [Path(LIB_ROOT).resolve(), Path(LEGACY_DATA_ROOT).resolve()]
    configured = str(load_config().get("data_root") or "").strip()
    if configured:
        guards.append(Path(configured).resolve())
    for real in guards:
        try:
            root.relative_to(real)
        except ValueError:
            continue
        raise SystemExit("拒绝在库内或真实数据根下建临时库：" + str(root))


def near_dup_cluster(ids, texts, threshold=REDUNDANCY_JACCARD):
    """top5 内最大的近重复簇：正文 2-gram Jaccard≥阈值连边，取连通分量。"""
    n = len(texts)
    parent = list(range(n))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    for i in range(n):
        for j in range(i + 1, n):
            if jaccard(texts[i], texts[j]) >= threshold:
                union(i, j)
    groups = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(i)
    best = []
    for indices in groups.values():
        if len(indices) > len(best):
            best = indices
    if len(best) < 2:
        return []
    return [ids[i] for i in best]


def run_case(conn, case):
    scopes = scopes_for(case["ns"])
    items, _debug = retrieve(conn, case["query"], scopes, TOP_K)
    ids = [it["node"]["id"] for it in items]
    texts = [it["node"].get("text") or "" for it in items]
    briefing = build_briefing(case["query"], scopes, items, conn)
    section = section_stats(briefing)

    gold = list(case.get("gold") or [])
    must_not = list(case.get("must_not") or [])
    first_rank = 0
    for idx, nid in enumerate(ids, 1):
        if nid in gold:
            first_rank = idx
            break
    cluster = near_dup_cluster(ids, texts)
    return {
        "id": case["id"],
        "cat": case.get("cat") or "core",
        "ns": case["ns"],
        "query": case["query"],
        "gold": gold,
        "must_not": must_not,
        "top5": ids,
        "hit": bool(first_rank) if gold else None,
        "first_rank": first_rank,
        "reciprocal": round(1.0 / first_rank, 4) if first_rank else 0.0,
        "redundant": len(cluster) >= 3,
        "redundancy_cluster": cluster,
        "leaked": [mid for mid in must_not if mid in ids],
        "sections_filled": section["count"],
        "current_filled": section["current"],
    }


def aggregate(details):
    gold_rows = [d for d in details if d["gold"]]
    neg_rows = [d for d in details if not d["gold"]]
    eligible = [d for d in details if len(d["top5"]) >= 3]
    metrics = {
        "recall@5": round(sum(1 for d in gold_rows if d["hit"]) / float(len(gold_rows)), 4),
        "mrr@5": round(sum(d["reciprocal"] for d in gold_rows) / float(len(gold_rows)), 4),
        "redundancy@5": round(sum(1 for d in eligible if d["redundant"]) / float(len(eligible)), 4)
        if eligible else 0.0,
        "leak_count": sum(len(d["leaked"]) for d in details),
        "section_fill": round(sum(d["sections_filled"] / 6.0 for d in details) / float(len(details)), 4),
        "current_rate": round(sum(1 for d in details if d["current_filled"]) / float(len(details)), 4),
        "negative_pass": round(sum(1 for d in neg_rows if not d["leaked"]) / float(len(neg_rows)), 4)
        if neg_rows else 0.0,
        "avg_hits": round(sum(len(d["top5"]) for d in details) / float(len(details)), 4),
        # gold 全部进 top5 的占比：multi 这类"一次问两件事"的用例，只看第一条是不够的
        "gold_all_rate": round(sum(1 for d in gold_rows
                                   if all(g in d["top5"] for g in d["gold"])) / float(len(gold_rows)), 4)
        if gold_rows else 0.0,
        # 负向用例的平均返回条数 / 空结果占比：语料里没有答案时，正确行为是"少给"
        "neg_avg_hits": round(sum(len(d["top5"]) for d in neg_rows) / float(len(neg_rows)), 4)
        if neg_rows else 0.0,
        "neg_empty_rate": round(sum(1 for d in neg_rows if not d["top5"]) / float(len(neg_rows)), 4)
        if neg_rows else 0.0,
    }
    by_cat = {}
    for name in sorted(set(d["cat"] for d in details)):
        rows = [d for d in details if d["cat"] == name]
        g = [d for d in rows if d["gold"]]
        by_cat[name] = {
            "cases": len(rows),
            "recall@5": round(sum(1 for d in g if d["hit"]) / float(len(g)), 4) if g else None,
            "mrr@5": round(sum(d["reciprocal"] for d in g) / float(len(g)), 4) if g else None,
            "leak": sum(len(d["leaked"]) for d in rows),
        }
    extra = {
        "by_cat": by_cat,
        "gold_cases": len(gold_rows),
        "negative_cases": len(neg_rows),
        "redundancy_eligible": len(eligible),
        "redundant_cases": sum(1 for d in eligible if d["redundant"]),
    }
    return metrics, extra


def run_all(seed, cases):
    with tempfile.TemporaryDirectory(prefix="mingmem_eval_") as tmp:
        root = Path(tmp) / "data"
        _assert_isolated(root)
        store = Store(root)
        store.ensure_dirs()
        for ev in seed:
            store.append(ev)
        conn, _fresh = open_projection(store)
        try:
            details = [run_case(conn, case) for case in cases]
        finally:
            conn.close()
    return details


def print_metrics(metrics, extra, details):
    from .vector import status as _vstatus
    _v = _vstatus()
    print("== mingmem 离线评测 ==")
    print("稠密通道：" + ("已启用（" + str(_v["model"]) + "，dim " + str(_v["dim"]) + "）"
                        if _v["available"] else
                        "未启用（" + (_v["reason"] or "未知原因") + "）"))
    print("语料 " + str(extra["seed_events"]) + " 条事件；用例 " + str(extra["case_count"]) +
          " 条（gold 非空 " + str(extra["gold_cases"]) + "，负向 " + str(extra["negative_cases"]) +
          "）；top" + str(TOP_K))
    print("")
    rows = [
        ("recall@5", metrics["recall@5"], "top5 命中任一 gold 的金标用例占比"),
        ("mrr@5", metrics["mrr@5"], "首条 gold 倒数排名均值（未命中 0）"),
        ("redundancy@5", metrics["redundancy@5"],
         "top5 内同簇近重复 ≥3 条的用例占比（簇＝2-gram Jaccard≥0.6 连通）"),
        ("leak_count", metrics["leak_count"], "must_not 出现的总次数（要求 0）"),
        ("section_fill", metrics["section_fill"], "briefing 六段平均非空段数 / 6"),
        ("current_rate", metrics["current_rate"], "③ 现状段非空的用例占比"),
        ("negative_pass", metrics["negative_pass"], "负向用例未泄漏 must_not 的占比"),
        ("avg_hits", metrics["avg_hits"], "每条用例平均返回条数"),
        ("gold_all_rate", metrics["gold_all_rate"], "gold 全部进 top5 的占比（多目标用例的硬指标）"),
        ("neg_avg_hits", metrics["neg_avg_hits"], "负向用例平均返回条数（越少越好）"),
        ("neg_empty_rate", metrics["neg_empty_rate"], "负向用例返回 0 条的占比"),
    ]
    for name, value, desc in rows:
        shown = str(value)
        print("  %-14s %-8s  %s" % (name, shown, desc))
    print("")
    print("  冗余分母：至少返回 3 条的用例 " + str(extra["redundancy_eligible"]) +
          " 条，其中同簇 ≥3 的 " + str(extra["redundant_cases"]) + " 条")
    print("")
    if extra.get("by_cat"):
        print("  分桶（诊断用，门禁只看总体）：")
        print("    %-11s %5s %9s %8s %5s" % ("cat", "cases", "recall@5", "mrr@5", "leak"))
        for name, row in extra["by_cat"].items():
            print("    %-11s %5d %9s %8s %5d" % (
                name, row["cases"],
                "-" if row["recall@5"] is None else "%.4f" % row["recall@5"],
                "-" if row["mrr@5"] is None else "%.4f" % row["mrr@5"],
                row["leak"]))
        print("")


def print_failures(details):
    failed = []
    for d in details:
        if d["gold"] and not d["hit"]:
            failed.append("  [召回] " + d["id"] + "  query=「" + d["query"] + "」 top5=" +
                          ",".join(d["top5"]) + " gold=" + ",".join(d["gold"]))
        if d["leaked"]:
            failed.append("  [泄漏] " + d["id"] + "  must_not 出现：" + ",".join(d["leaked"]) +
                          "  top5=" + ",".join(d["top5"]))
        if d["redundant"]:
            failed.append("  [冗余] " + d["id"] + "  同簇 " + str(len(d["redundancy_cluster"])) +
                          " 条：" + ",".join(d["redundancy_cluster"]))
    print("失败明细（共 " + str(len(failed)) + " 条）：")
    if not failed:
        print("  （无）")
    else:
        print("\n".join(failed))
    print("")


def print_verbose(details):
    print("逐条明细：")
    for d in details:
        status = "命中" if d["hit"] else ("负向" if not d["gold"] else "未命中")
        print("  " + d["id"] + " [" + d["ns"] + "] " + status + "  query=「" + d["query"] + "」")
        print("      top5=" + (",".join(d["top5"]) or "（空）"))
        print("      gold=" + (",".join(d["gold"]) or "（空）") +
              "  must_not=" + (",".join(d["must_not"]) or "（空）") +
              "  段=" + str(d["sections_filled"]) + "/6")


def write_report(seed, cases, metrics, extra, details):
    payload = {
        "schema": 1,
        "seed_events": len(seed),
        "cases": len(cases),
        "top_k": TOP_K,
        "redundancy_jaccard": REDUNDANCY_JACCARD,
        "metrics": metrics,
        "counts": extra,
        "details": details,
    }
    with open(str(REPORT_PATH), "w", encoding="utf-8", newline="") as fh:
        fh.write(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def write_baseline(metrics, extra):
    """把本次指标写成新基线。

    规则：只在**有意改动引擎**并复核过指标变化之后才这么做，不能为了过门禁而调，
    否则门禁就失去意义（基线必须来自真实运行）。
    """
    payload = {"schema": 1, "top_k": globals().get("TOP_K", 5),
               "cases": extra.get("case_count"), "seed_events": extra.get("seed_events"),
               "metrics": metrics, "counts": extra}
    path = current_baseline_path() if (BASELINE_PATH.exists() or BASELINE_DENSE_PATH.exists()) \
        else BASELINE_PATH
    with open(str(path), "w", encoding="utf-8", newline="") as fh:
        fh.write(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print("基线已更新：" + str(path))
    print("（只有在有意改动引擎并复核指标后才该更新基线；不要用它绕门禁。）")


def current_baseline_path():
    """按**当前环境能力**选基线：装了稠密通道就比稠密基线，否则比词面基线。

    为什么要两套：同一份评测集在"有稠密/无稠密"下是两个不同的测量对象
    （0.9535 vs 0.8992）。用一套基线会让没装依赖的机器误报门禁失败，
    或者让装了依赖的机器看着虚高。
    """
    from .vector import status as _vs
    st = _vs()
    if st["available"] and BASELINE_DENSE_PATH.exists():
        return BASELINE_DENSE_PATH
    return BASELINE_PATH


def gate(metrics):
    path = current_baseline_path()
    if not path.exists():
        print("门禁失败：找不到基线 " + str(path), file=sys.stderr)
        return 2
    baseline = json.loads(path.read_text(encoding="utf-8"))
    print("（基线文件：" + path.name + "）")
    base = baseline.get("metrics") or {}
    checks = [
        ("leak_count", metrics["leak_count"], 0, metrics["leak_count"] == 0,
         "必须为 0"),
        ("recall@5", metrics["recall@5"], base.get("recall@5"),
         metrics["recall@5"] >= float(base.get("recall@5", 0.0)) - GATE_MARGIN - 1e-9,
         "不得低于基线 - " + str(GATE_MARGIN)),
        ("mrr@5", metrics["mrr@5"], base.get("mrr@5"),
         metrics["mrr@5"] >= float(base.get("mrr@5", 0.0)) - GATE_MARGIN - 1e-9,
         "不得低于基线 - " + str(GATE_MARGIN)),
        ("redundancy@5", metrics["redundancy@5"], base.get("redundancy@5"),
         metrics["redundancy@5"] <= float(base.get("redundancy@5", 0.0)) + GATE_MARGIN + 1e-9,
         "不得高于基线 + " + str(GATE_MARGIN)),
    ]
    print("门禁对比（基线 → 本次）：")
    fails = []
    for name, current, base_value, ok, rule in checks:
        print("  [%s] %-14s 基线 %-8s 本次 %-8s  规则：%s" %
              ("通过" if ok else "越线", name, str(base_value), str(current), rule))
        if not ok:
            fails.append(name)
    print("")
    if fails:
        print("门禁失败：越线指标 " + "、".join(fails))
        return 1
    print("门禁通过。")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="mingmem.eval",
                                     description="记忆内核离线评测集与门禁")
    parser.add_argument("--gate", action="store_true", help="与 baseline.json 比较并按门禁退出")
    parser.add_argument("--verbose", action="store_true", help="逐条打印 query / top5 / gold")
    parser.add_argument("--save-baseline", action="store_true",
                        help="把本次指标写成新基线（仅在有意改动引擎后使用）")
    parser.add_argument("--no-vector", action="store_true",
                        help="强制只用词面通道（对比用；默认按环境决定是否启用稠密通道）")
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))

    # 0.3.0 起稠密通道默认参与检索；评测按"当前环境实际能力"跑，并在输出里标明，
    # 避免"装了依赖"和"没装依赖"两种环境的数字被混为一谈。`--no-vector` 可强制关掉做对比。
    os.environ.pop("MEM_VECTOR_FORCE", None)
    if args.no_vector:
        os.environ["MEM_VECTOR"] = "0"
    else:
        os.environ.pop("MEM_VECTOR", None)

    seed = load_seed()
    cases = load_cases(seed)
    details = run_all(seed, cases)
    metrics, extra = aggregate(details)
    extra["seed_events"] = len(seed)
    extra["case_count"] = len(cases)
    print_metrics(metrics, extra, details)
    print_failures(details)
    if args.verbose:
        print_verbose(details)
    write_report(seed, cases, metrics, extra, details)
    print("报告已写入 " + str(REPORT_PATH))
    if args.save_baseline:
        write_baseline(metrics, extra)
    print("")
    if args.gate:
        return gate(metrics)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
