import argparse
import os
import sqlite3
import sys
import tempfile
from pathlib import Path

from .config import (config_path, configure_message, load_config, resolve_data_root,
                     save_config)
from .settings import (CONSOLIDATE_KINDS, DEFAULT_LIMIT, DISCLOSURES, JSON_SUPPORTED,
                      MAX_LIMIT, NL, SOURCES)
from .store import Store
from .tests import cmd_selftest
from .text import default_ns, parse_list
from .vector import dense_reason, vector_available, vector_enabled


# 约定（写进接口规范）：**文本输出跟随控制台编码（locale）**，给人看；
# 机器接口是 serve --stdio（UTF-8 JSON Lines）与 MemoryCore（Python 对象），不刮文本。
# 因此这里不强制改 stdout 编码 —— 强制 UTF-8 会让 PowerShell 管道显示乱码。
# 子进程的编码由 core.child_env() 显式钉成 UTF-8（内核自己的内部调用，不能靠调用方记性）。


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


def cmd_init(args):
    """初始化：写用户级配置（数据根），不再靠「按包位置反推」这种隐式默认（D-02）。"""
    target = Path(os.path.expanduser(str(args.data_root))).resolve()
    path = config_path()
    if path.exists() and str(load_config().get("data_root") or "").strip() and not args.force:
        return fail("已存在配置：" + str(path) + "；要改数据根请加 --force")
    try:
        target.mkdir(parents=True, exist_ok=True)
        (target / "events").mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        return fail("建不了数据根：" + str(target) + "（" + str(exc) + "）")
    data = {"schema": 1, "data_root": str(target)}
    ns = (getattr(args, "default_ns", None) or "").strip()
    if ns:
        data["default_ns"] = ns
    saved = save_config(data)
    emit("已初始化 mingmem", getattr(args, "quiet", False))
    emit("- 数据根：" + str(target) + "（已建 events/）", getattr(args, "quiet", False))
    emit("- 配置文件：" + str(saved), getattr(args, "quiet", False))
    emit("- 默认命名空间：" + (ns or "按当前目录名反推 project:<目录名>；global 需显式写"),
         getattr(args, "quiet", False))
    emit("- 下一步：mingmem doctor ｜ mingmem add --text \"第一条记忆\"",
         getattr(args, "quiet", False))
    return 0


def build_parser():
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--data-root", metavar="DIR", default=argparse.SUPPRESS,
                        help="数据根（优先于 MINGMEM_DATA / MEM_DATA 与配置文件）")
    common.add_argument("--json", action="store_true", default=argparse.SUPPRESS,
                        help="机器可读输出")
    common.add_argument("--quiet", action="store_true", default=argparse.SUPPRESS,
                        help="静默（不打印正常输出）")
    common.add_argument("--vector", action="store_true", default=argparse.SUPPRESS,
                        help="启用稠密通道（0.3.0 起默认已启用；本开关用于显式要求，缺依赖时报错而不是静默降级）")
    parser = argparse.ArgumentParser(
        prog="mingmem", parents=[common],
        description="记忆内核 CLI：事件流为唯一真相源，SQLite 为可重建投影。")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("init", help="初始化：把数据根写进用户级配置（之后可省略 --data-root）")
    p.add_argument("--data-root", required=True, metavar="DIR", help="记忆数据的存放目录")
    p.add_argument("--default-ns", metavar="NS", help="默认命名空间（如 project:x）；不给则按当前目录名反推")
    p.add_argument("--force", action="store_true", help="覆盖已有配置")
    p.add_argument("--quiet", action="store_true", help="静默（不打印正常输出）")

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
    p.add_argument("--client-key", dest="client_key",
                   help="幂等键：同 key 重复调用不会写第二条（重试安全）")
    p.add_argument("--dry-run", action="store_true", help="只打印将写入的事件")

    p = sub.add_parser("recall", parents=[common], help="检索并输出结构化 briefing")
    p.add_argument("--query", required=True)
    p.add_argument("--ns")
    p.add_argument("--scope", help="检索范围，逗号分隔；all 表示全部命名空间")
    p.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    p.add_argument("--include-ask", action="store_true", help="包含披露等级为 ask 的记忆")
    p.add_argument("--include-archived", action="store_true")
    p.add_argument("--min-score", type=float,
                   help="检索证据门槛 0~1（默认取 settings.MIN_SCORE；0 只关分数地板，零证据条目仍不返回）")
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

    p = sub.add_parser("forget", parents=[common], help="软删：进回收站（事件流原文仍在）")
    p.add_argument("--ids", required=True, help="要软删的 id，逗号分隔")
    p.add_argument("--ns")
    p.add_argument("--reason", help="删除理由（记入事件）")
    p.add_argument("--dry-run", action="store_true")

    p = sub.add_parser("bin", parents=[common], help="回收站：列出已软删的记忆")
    p.add_argument("--limit", type=int, help="本页最多几条（默认全部）")
    p.add_argument("--offset", type=int, default=0, help="跳过前 N 条（分页用）")

    p = sub.add_parser("restore", parents=[common], help="从回收站恢复")
    p.add_argument("--ids", required=True)
    p.add_argument("--ns")
    p.add_argument("--dry-run", action="store_true")

    p = sub.add_parser("purge", parents=[common], help="真删：物理重写事件流（不可逆）")
    p.add_argument("--ids", required=True)
    p.add_argument("--yes", action="store_true", help="确认真删")
    p.add_argument("--dry-run", action="store_true")

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

    p = sub.add_parser("import", parents=[common],
                       help="从 v1 旧库（SQLite-only）一次性导入：把行翻译成事件再重放")
    p.add_argument("legacy", metavar="旧库路径", help="旧库数据根（含 index.sqlite）或直接给 index.sqlite")
    p.add_argument("--ns-map", help="命名空间映射，如 project:旧=project:新（逗号分隔）")
    p.add_argument("--limit", type=int, help="只导入前 N 个节点（试跑用）")
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

    p = sub.add_parser("verify", parents=[common], help="校验事件链（发现事件被删/被插/被改）")
    p.add_argument("--ns", help="只校验这个命名空间的文件")
    p.add_argument("--pin", metavar="FILE", help="链头锚点文件：不存在则写入，存在则比对（放数据根之外才有意义）")

    p = sub.add_parser("doctor", parents=[common], help="只读体检")

    p = sub.add_parser("capabilities", parents=[common],
                       help="能力探测：协议/版本/命令/限额/错误分类（壳与插件的第一步）")
    p = sub.add_parser("describe", parents=[common],
                       help="机器可读的命令与参数描述（可直接生成表单/工具定义）")
    p.add_argument("command", nargs="?", help="只看某个命令；不给则输出全部")

    p = sub.add_parser("serve", parents=[common],
                       help="第二层接口：从 stdin 按行读 JSON 请求，向 stdout 按行写响应")
    p.add_argument("--stdio", action="store_true", help="JSON Lines over stdio（当前唯一模式）")

    p = sub.add_parser("selftest", parents=[common], help="在临时数据根跑完整闭环，不碰真实库")
    return parser


def main(argv=None):
    from .commands import (cmd_add, cmd_bin, cmd_consolidate, cmd_correct, cmd_cycle,
                          cmd_doctor, cmd_forget, cmd_links, cmd_purge, cmd_rebuild,
                          cmd_capabilities, cmd_describe, cmd_import, cmd_recall,
                          cmd_restore, cmd_search, cmd_status, cmd_verify)
    from .api import cmd_serve
    from .render import cmd_export
    parser = build_parser()
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))
    args.json = bool(getattr(args, "json", False))
    args.quiet = bool(getattr(args, "quiet", False))
    if getattr(args, "vector", False):
        os.environ["MEM_VECTOR"] = "1"
    if args.cmd == "init":
        return cmd_init(args)
    if args.cmd in ("capabilities", "describe"):
        # 纯自描述：**没有数据根也必须能回答** —— 壳/插件/Agent 在配置数据根之前
        # 就得先知道内核能做什么、每个命令要什么参数。
        from .commands import cmd_capabilities, cmd_describe
        return (cmd_capabilities if args.cmd == "capabilities" else cmd_describe)(args, None)
    root, source = resolve_data_root(getattr(args, "data_root", None))
    if root is None and args.cmd == "selftest":
        # selftest 是「装好没有」的自检，不该要求先 init；用一个不存在的探针目录做
        # 「真实数据根零改动」的比对对象（不创建、不写入）。
        root, source = Path(tempfile.gettempdir()) / "mingmem-noroot-probe", "临时探针（尚未配置数据根）"
    if root is None:
        return fail(configure_message())
    store = Store(Path(root))
    store.root_source = source
    handlers = {
        "add": cmd_add, "recall": cmd_recall, "search": cmd_search, "links": cmd_links,
        "cycle": cmd_cycle, "consolidate": cmd_consolidate, "correct": cmd_correct,
        "rebuild": cmd_rebuild, "status": cmd_status, "export": cmd_export,
        "doctor": cmd_doctor, "verify": cmd_verify, "selftest": cmd_selftest,
        "serve": cmd_serve, "import": cmd_import,
        "capabilities": cmd_capabilities, "describe": cmd_describe,
        "forget": cmd_forget, "bin": cmd_bin, "restore": cmd_restore, "purge": cmd_purge,
    }
    handler = handlers.get(args.cmd)
    if handler is None:
        return fail("未知命令：" + str(args.cmd))
    if args.json and args.cmd not in JSON_SUPPORTED:
        print("警告：命令 " + str(args.cmd) + " 暂不支持 --json，输出为文本", file=sys.stderr)
    if getattr(args, "vector", False) and not vector_enabled():
        print("稠密通道已被 MEM_VECTOR 显式关闭，不能再加 --vector；请先取消该环境变量。", file=sys.stderr)
        return 2
    if getattr(args, "vector", False) and not vector_available():
        print("稠密通道不可用：" + (dense_reason() or "未知原因") +
              "。不装依赖也能跑（自动降级为 BM25 + 图扩散），去掉 --vector 即可。", file=sys.stderr)
        return 2
    def _run():
        if args.quiet:
            import contextlib
            import io
            with contextlib.redirect_stdout(io.StringIO()):
                return handler(args, store)
        return handler(args, store)

    try:
        if args.cmd in ("selftest", "serve"):
            # selftest 会起子进程，父进程持锁会与子进程互锁；
            # serve 是长驻进程，每条请求由内层 cli.main 各自加锁。
            code = _run()
        else:
            # G27：整条命令（含投影创建/重建）串行化，避免并发进程互相拆 index.sqlite。
            with store.lock():
                code = _run()
    except TimeoutError as exc:
        return fail(str(exc))
    except KeyboardInterrupt:
        return fail("已中断")
    except sqlite3.DatabaseError as exc:
        return fail("投影数据库错误：" + str(exc) + "；可执行 rebuild 重建")
    return code


def entry():
    raise SystemExit(main())
