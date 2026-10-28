"""接口规范表（单一来源）＋自描述能力。

**为什么要这张表**：接口有三层（CLI / `serve` / `MemoryCore`），未来还会有壳、插件、
Agent 工具表。如果每层各写一遍"有哪些命令、参数叫什么"，必然漂移。所以：

- **参数的唯一来源是 argparse 定义**（`describe()` 直接从 parser 抽），所以"文档里的参数"
  与"真正能用的参数"在构造上不可能不一致；
- **语义的单一来源是本文件的 `COMMANDS`**（一句话说明 / 是否写数据 / 是否可远端调用 /
  示例 / 备注），`capabilities()` 与 `describe()` 都从它生成。

版本策略：`PROTOCOL` 是**信封与语义**的版本，`SCHEMA_VERSION` 是**数据投影**的版本，
`VERSION` 是**软件**版本。三者独立递增，调用方用 `hello` 协商 `PROTOCOL`。
"""

import argparse

from .settings import JSON_SUPPORTED, SCHEMA_VERSION, VERSION

PROTOCOL = "mmp/1"

# 错误分类（kind）是**稳定 API**：调用方只认这些字符串，不解析中文文本。
ERROR_KINDS = {
    "usage":       {"exit": 2, "retryable": False, "meaning": "参数拼错/缺参数/非法枚举（argparse 层）"},
    "invalid":     {"exit": 2, "retryable": False, "meaning": "参数合法但被业务规则拒绝（空正文、来源不足等）"},
    "config":      {"exit": 2, "retryable": False, "meaning": "数据根/配置缺失或冲突，需要调用方先 init 或 --force"},
    "not_found":   {"exit": 2, "retryable": False, "meaning": "id/文件不存在（可能已被真删或 id 拼错）"},
    "conflict":    {"exit": 2, "retryable": False, "meaning": "状态冲突（未软删就真删、来源已巩固等）"},
    "busy":        {"exit": 2, "retryable": True,  "meaning": "数据根被别的进程占着，稍后重试"},
    "storage":     {"exit": 2, "retryable": True,  "meaning": "投影损坏/IO 问题，可 rebuild 后重试"},
    "findings":    {"exit": 1, "retryable": False, "meaning": "体检发现问题（doctor/verify），输出里有明细"},
    "unsupported": {"exit": 2, "retryable": False, "meaning": "协议不认识的 op / 未实现的模式"},
    "failed":      {"exit": 2, "retryable": False, "meaning": "其它未分类失败（实现缺陷或未覆盖分支）"},
}

# 命令语义表。参数**不写在这里**（从 argparse 抽），这里只放"人读/机器读都需要"的语义。
COMMANDS = {
    "init": {
        "summary": "初始化：把数据根写进用户级配置（之后可省略 --data-root）",
        "writes": True, "remote": True, "examples": [{"data_root": "<DIR>", "default_ns": "project:d"}],
        "notes": ["只写配置与 events/ 目录，不写记忆", "重复 init 会被拒（conflict），换根要 force"],
    },
    "add": {
        "summary": "写入一条记忆事件",
        "writes": True, "remote": True,
        "examples": [{"text": "决定用事件流做真相源", "kind": "decide", "tags": ["架构"]}],
        "notes": ["text 传 - 表示从标准输入读", "client_key 相同的重复调用不会写第二条（幂等）"],
    },
    "recall": {
        "summary": "检索并输出六段结构化 briefing",
        "writes": True, "remote": True,
        "examples": [{"query": "记忆内核 设计", "limit": 5}],
        "notes": ["默认会写一条 access 事件（访问强化）；dry_run=true 则不写",
                  "返回值条数常常少于 limit（MMR + 硬簇上限），**不得假设恒返回 5 条**",
                  "0.2.1 起有最低相关分门槛（min_score，默认取 settings.MIN_SCORE）："
                  "零词面证据的条目一律不返回，因此常见词/2 字查询可能只给 0~1 条",
                  "items 里的 evidence 是检索证据（IDF 加权覆盖率），filler=true 表示"
                  "该条是图扩散补位的线索、不是词面命中"],
    },
    "search": {
        "summary": "原始命中（调试用，不做 briefing、不去冗余）",
        "writes": False, "remote": True,
        "examples": [{"query": "投影", "limit": 10}],
        "notes": ["排查「为什么没召回」用它；给用户看用 recall"],
    },
    "links": {
        "summary": "按 id 精确访问节点、边、双时态与回收站状态",
        "writes": False, "remote": True, "examples": [{"id": "<id>"}],
        "notes": ["回收站里的条目仍可被精确访问，会标注状态"],
    },
    "forget": {
        "summary": "软删：进回收站（事件流原文仍在）",
        "writes": True, "remote": True, "examples": [{"ids": ["<id>"], "reason": "重复内容"}],
        "notes": ["软删后没有任何检索旁路；bin 是唯一列表入口"],
    },
    "bin": {
        "summary": "回收站：列出已软删的记忆",
        "writes": False, "remote": True, "examples": [{"limit": 20, "offset": 0}],
        "notes": ["唯一能看到已删项列表的命令"],
    },
    "restore": {
        "summary": "从回收站恢复",
        "writes": True, "remote": True, "examples": [{"ids": ["<id>"]}],
        "notes": [],
    },
    "purge": {
        "summary": "真删：物理重写事件流（不可逆）",
        "writes": True, "remote": True, "examples": [{"ids": ["<id>"], "yes": True}],
        "notes": ["必须先软删；不带 yes 只干跑；重写后自动重串哈希链"],
    },
    "cycle": {
        "summary": "一次维护：遗忘扫描 + 待巩固/待熏习批次",
        "writes": True, "remote": True, "examples": [{"dry_run": True}],
        "notes": ["只报「该巩固了」，不替你写语义卡"],
    },
    "consolidate": {
        "summary": "把 ≥2 条来源巩固成一张语义卡",
        "writes": True, "remote": True,
        "examples": [{"from": ["<id1>", "<id2>"], "text": "阈值取 0.3 更稳", "kind": "semantic"}],
        "notes": ["来源必须同 ns；原始 episode 永不删除（结论是加一层，不是替换证据）"],
    },
    "correct": {
        "summary": "追加更正事件（不改历史，双时态）",
        "writes": True, "remote": True, "examples": [{"target": "<id>", "text": "更正后的事实"}],
        "notes": ["被更正者置 superseded 且不再参与默认检索，原文永久保留"],
    },
    "rebuild": {
        "summary": "从事件流全量重建投影",
        "writes": True, "remote": True, "examples": [{}],
        "notes": ["投影随时可丢：重建后检索顺序与强度必须复原"],
    },
    "status": {
        "summary": "统计与健康摘要",
        "writes": False, "remote": True, "examples": [{}],
        "notes": [],
    },
    "export": {
        "summary": "导出人读视图（md / obsidian）",
        "writes": False, "remote": True,
        "examples": [{"out": "<FILE>", "lean": True, "toc": True}],
        "notes": ["默认排除 private 与 ask；include_ask 会写 consent 审计", "导出是可重建视图，不是真相源"],
    },
    "verify": {
        "summary": "校验事件哈希链（改/删/插都能指认到 文件:行号）",
        "writes": False, "remote": True, "examples": [{"pin": "<数据根之外的文件>"}],
        "notes": ["pin 文件不存在则写入、存在则比对；末行改写与整链重算只能靠 pin 抓"],
    },
    "doctor": {
        "summary": "只读体检：环境、事件流、哈希链、投影、对账",
        "writes": False, "remote": True, "examples": [{}],
        "notes": ["退出码 1 = 发现问题（明细在输出里）"],
    },
    "serve": {
        "summary": "第二层：常驻 JSON Lines 服务（本协议的服务端本体）",
        "writes": False, "remote": False, "examples": [],
        "notes": ["不通过 call 分发（它就是分发本身）"],
    },
    "import": {
        "summary": "从 v1 旧库（SQLite-only）一次性导入：行→事件→重放",
        "writes": True, "remote": True,
        "examples": [{"legacy": "<旧库数据根>", "dry_run": True}],
        "notes": ["强度/状态/边权保真；幂等（事件 id 已存在就跳过）"],
    },
    "selftest": {
        "summary": "在临时数据根跑完整闭环，不碰真实库",
        "writes": False, "remote": True, "examples": [{}],
        "notes": ["不需要数据根、不需要配置就能跑；末行是 N/N 项通过"],
    },
    "capabilities": {
        "summary": "列出协议、能力、限额与错误分类（机器读）",
        "writes": False, "remote": True, "examples": [{}],
        "notes": ["壳/插件/Agent 应当先调它，再按返回的 commands 构造调用"],
    },
    "describe": {
        "summary": "输出命令与参数的机器可读描述（参数从 argparse 抽，因此永不漂移）",
        "writes": False, "remote": True, "examples": [{"command": "recall"}],
        "notes": ["可直接据此自动生成表单、工具定义或参数校验"],
    },
}

# 本协议里的 op（serve 的请求类型）
OPS = ("hello", "ping", "capabilities", "describe", "call", "batch", "exit")


def _action_kind(action):
    if isinstance(action, (argparse._StoreTrueAction, argparse._StoreFalseAction)):
        return "boolean"
    if action.choices:
        return "enum"
    if action.type is int:
        return "integer"
    if action.type is float:
        return "number"
    if action.nargs in ("?", "*", "+"):
        return "string"
    return "string"


def _action_param(action):
    cli = list(action.option_strings) or [str(action.dest)]
    required = bool(action.required)
    default = action.default
    if default is argparse.SUPPRESS:
        default = None
    if not isinstance(default, (str, int, float, bool, type(None))):
        default = None
    param = {
        "name": action.dest,
        "kind": _action_kind(action),
        "cli": cli,
        "positional": not bool(action.option_strings),
        "required": required,
        "default": default,
    }
    if action.choices:
        param["choices"] = [str(c) for c in action.choices]
    if action.help:
        param["help"] = str(action.help)
    if param["kind"] == "string" and param["positional"] is False and action.nargs is None:
        # 所有字符串参数都接受 JSON 数组（内核按逗号拼接），因此调用方不必自己 join
        param["accepts_list"] = True
    return param


def describe(command=None):
    """从 parser 抽参数 + 从 COMMANDS 抽语义，产出机器可读描述。"""
    from .cli import build_parser
    parser = build_parser()
    sub = None
    for action in parser._actions:
        if hasattr(action, "choices") and isinstance(action.choices, dict):
            sub = action.choices
            break
    if sub is None:                      # pragma: no cover - argparse 结构变了才会到这里
        raise RuntimeError("取不到子命令表")
    names = [command] if command else sorted(sub.keys())
    out = {}
    for name in names:
        if name not in sub:
            raise KeyError(name)
        p = sub[name]
        semantics = COMMANDS.get(name, {})
        params = []
        for action in p._actions:
            if action.dest in ("help",):
                continue
            params.append(_action_param(action))
        item = {
            "summary": semantics.get("summary", ""),
            "writes": bool(semantics.get("writes", False)),
            "remote": bool(semantics.get("remote", True)),
            "json": name in JSON_SUPPORTED,
            "params": params,
            "notes": list(semantics.get("notes") or []),
            "examples": list(semantics.get("examples") or []),
        }
        out[name] = item
    return out


def capabilities():
    """能力探测：壳/插件/Agent 的第一步。"""
    described = describe()
    return {
        "protocol": PROTOCOL,
        "version": VERSION,
        "schema_version": SCHEMA_VERSION,
        "data_model": "append-only-jsonl-events + rebuildable-sqlite-projection",
        "layers": {
            "cli": {"entry": "python -X utf8 -m mingmem <command>", "json_flag": "--json"},
            "serve": {"entry": "python -X utf8 -m mingmem serve --stdio",
                      "transport": "json-lines-over-stdio",
                      "ops": list(OPS)},
            "python": {"entry": "from mingmem.api import MemoryCore",
                       "generic": "MemoryCore.call(command, **params)",
                       "sugar": "MemoryCore.add/recall/search/... （糖，等价于 call）"},
        },
        "commands": dict((name, {"summary": info["summary"], "writes": info["writes"],
                                 "remote": info["remote"], "json": info["json"]})
                         for name, info in described.items()),
        "errors": ERROR_KINDS,
        "limits": {
            "max_limit": 50,
            "default_limit": 5,
            "max_batch": 50,
            "default_timeout_ms": None,
        },
        "contracts": [
            "参数对象里的键名 = argparse 的 dest（describe 里 name 字段）",
            "字符串参数也接受 JSON 数组（内核按逗号拼接）",
            "写命令支持 client_key（add）用于重试幂等",
            "recall/search 不得假设返回条数 == limit",
            "recall 有最低相关分门槛（min_score，0.2.1+）：零词面证据的条目不返回，"
            "「的」「怎么」这类查询就是 0 条（正确结果，不是故障）",
            "recall 的 items[].evidence 是检索证据（0=纯图扩散补位），filler=true 表示补位线索；"
            "gate 给出 candidates/lexical/dropped_no_evidence/dropped_score/filled",
            "必须显式给 ns（或数据根配置里的 default_ns），内核不猜项目名",
            "响应里的 kind 是稳定枚举；不要解析中文错误文本",
            "hello 的协议大版本按 mmp/<数字> 里的数字判：mmp/1、mmp/1.3 兼容，mmp/9 会被拒",
            "稠密通道（0.3.0+）是可选能力：装了 onnxruntime 与内置模型即启用，否则自动降级为"
            " BM25 + 图扩散；recall 的 items[].dense_sim 给出余弦，可用于自行设阈值",
        ],
    }
