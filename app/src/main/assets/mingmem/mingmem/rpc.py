"""协议层：统一信封、错误分类、参数对象 → argv、请求分发。

这一层是"通用覆盖"的关键：**调用方不需要为每个命令学一套 API**。
它拿 `capabilities` 看有哪些命令，拿 `describe` 看参数，然后用 `call(command, params)` 调，
参数名就是 argparse 的 dest。新加/改参数时 `describe` 自动跟着变，调用方无需改代码。
"""

import io
import json
import time
from contextlib import redirect_stderr, redirect_stdout

from .settings import JSON_SUPPORTED, NL, VERSION
from .spec import (ERROR_KINDS, OPS, PROTOCOL, SCHEMA_VERSION, capabilities,
                   describe)

MAX_BATCH = 50

# 错误分类：**只看退出码与 stderr 文本的开头特征**，不改各命令的返回方式。
# 分类结果是稳定 API（kind 字符串），调用方不要解析中文。
_CLASSIFY = (
    ("未配置数据根", "config"),
    ("已存在配置", "config"),
    ("建不了数据根", "config"),
    ("usage:", "usage"),
    ("invalid choice", "usage"),
    ("unrecognized arguments", "usage"),
    ("the following arguments are required", "usage"),
    ("invalid int value", "usage"),
    ("invalid float value", "usage"),
    ("正文为空", "invalid"),
    ("不支持的 kind", "invalid"),
    ("至少需要 2 个来源", "invalid"),
    ("跨命名空间", "invalid"),
    ("--limit", "invalid"),
    ("--kind 只能是", "invalid"),
    ("serve 目前只支持", "unsupported"),
    ("找不到节点", "not_found"),
    ("找不到被更正的节点", "not_found"),
    ("找不到旧库", "not_found"),
    ("来源不存在", "not_found"),
    ("refs 不存在", "not_found"),
    ("没有可软删的节点", "not_found"),
    ("回收站里没有这些 id", "not_found"),
    ("这些记忆还没软删", "conflict"),
    ("来源已巩固", "conflict"),
    ("无法解析的事件行", "findings"),
    ("缺失事件", "findings"),
    ("哈希链：断裂", "findings"),
    ("被其他进程占用", "busy"),
    ("投影数据库错误", "storage"),
    ("旧库不可用", "storage"),
)


def classify(code, err_text=""):
    """退出码 + stderr → (kind, retryable)。"""
    if code == 0:
        return "ok", False
    text = str(err_text or "")
    for needle, kind in _CLASSIFY:
        if needle in text:
            return kind, bool(ERROR_KINDS.get(kind, {}).get("retryable"))
    if code == 1:
        return "findings", False
    return "failed", False


class RpcError(Exception):
    """带 kind 的失败（协议层用；MemoryCore 抛它，serve 把它折成响应）。"""

    def __init__(self, message, code=2, kind="failed", retryable=False, detail=None):
        Exception.__init__(self, str(message))
        self.message = str(message)
        self.code = int(code)
        self.kind = str(kind)
        self.retryable = bool(retryable)
        self.detail = detail or {}


def run_inproc(argv):
    """在本进程里跑一条 CLI 命令，返回 (code, stdout, stderr)。"""
    out, err = io.StringIO(), io.StringIO()
    code = 0
    from .cli import main as cli_main
    try:
        with redirect_stdout(out), redirect_stderr(err):
            code = cli_main(list(argv))
    except SystemExit as exc:
        code = int(exc.code or 0)
    return code, out.getvalue(), err.getvalue()


def _cli_name(param):
    return param["cli"][0]


def argv_from_params(command, params=None, data_root=None, ns=None, json_out=None):
    """把 `{参数名: 值}` 变成命令行参数数组。

    规则（写进规范，调用方可依赖）：
    - 键名 = `describe()` 里的 `name`（即 argparse 的 dest）；未知键 → usage 错误（不静默丢弃）。
    - 布尔参数：真就加 `--flag`，假就省略。
    - 字符串参数也接受数组（内核按 `,` 拼接）。
    - 位置参数按 `describe()` 的顺序放在最后。
    - `data_root` / `ns` 由本函数注入（命令支持才注入）；`json_out` 为真且命令支持 `--json` 时追加。
    """
    params = dict(params or {})
    info = describe(command)[command]
    known = dict((p["name"], p) for p in info["params"])
    unknown = [k for k in params if k not in known]
    if unknown:
        raise RpcError("未知参数：" + "、".join(sorted(unknown)) + "（可用：" +
                       "、".join(sorted(known)) + "）", 2, "usage")
    argv = [command]
    positionals = []
    for p in info["params"]:
        name = p["name"]
        if name not in params or params[name] is None:
            continue
        value = params[name]
        if p["kind"] == "boolean":
            if bool(value):
                argv.append(_cli_name(p))
            continue
        if isinstance(value, (list, tuple)):
            value = ",".join(str(v) for v in value)
        if p["positional"]:
            positionals.append(str(value))
        else:
            argv += [_cli_name(p), str(value)]
    argv += positionals
    if data_root is not None and "data_root" in known:
        argv += ["--data-root", str(data_root)]
    if ns is not None and "ns" in known:
        argv += ["--ns", str(ns)]
    use_json = info["json"] if json_out is None else bool(json_out)
    if use_json and info["json"] and "json" in known:
        argv.append(known["json"]["cli"][0])   # 用 parser 里的真实写法，不硬编码 --json
    return argv


def _envelope(rid, code, out, err, wrote, started, extra=None):
    kind, retryable = classify(code, err)
    resp = {
        "v": PROTOCOL,
        "id": rid,
        "ok": code == 0,
        "code": code,
        "kind": kind,
        "retryable": retryable,
        "text": out,
        "meta": {"duration_ms": int((time.time() - started) * 1000), "wrote": bool(wrote)},
    }
    if err.strip():
        resp["err"] = err
    text = out.strip()
    if text.startswith("{") or text.startswith("["):
        try:
            resp["data"] = json.loads(text)
        except ValueError:
            pass
    if extra:
        resp.update(extra)
    return resp


def call_result(command, params=None, data_root=None, ns=None, json_out=None):
    """执行一条命令，返回协议信封（不抛异常 —— 供 serve/batch 用）。"""
    started = time.time()
    try:
        argv = argv_from_params(command, params, data_root=data_root, ns=ns,
                                json_out=json_out)
    except KeyError:
        return {"v": PROTOCOL, "id": None, "ok": False, "code": 2, "kind": "not_found",
                "retryable": False, "text": "",
                "err": "未知命令：" + str(command) + "（用 capabilities/describe 查可用命令）",
                "meta": {"duration_ms": 0, "wrote": False}}
    except RpcError as exc:
        return {"v": PROTOCOL, "id": None, "ok": False, "code": exc.code, "kind": exc.kind,
                "retryable": exc.retryable, "text": "", "err": exc.message,
                "meta": {"duration_ms": 0, "wrote": False}}
    wrote = bool(describe(command)[command]["writes"])
    code, out, err = run_inproc(argv)
    if data_root is not None and "--data-root" not in argv:
        # 命令不支持 --data-root（如 init 自己就带一个），交给调用方自己传
        pass
    return _envelope(None, code, out, err, wrote, started,
                     extra={"command": command, "argv": argv})


def call(command, params=None, data_root=None, ns=None, json_out=None):
    """同 `call_result`，但失败抛 `RpcError`（Python 侧更好用）。"""
    resp = call_result(command, params, data_root=data_root, ns=ns, json_out=json_out)
    if not resp["ok"]:
        raise RpcError(resp.get("err") or resp.get("text") or "命令失败",
                       resp["code"], resp["kind"], resp["retryable"], detail=resp)
    return resp


def _proto_major(value):
    """`mmp/1` → `mmp/1`；`mmp/1.3` → `mmp/1`（次版本兼容）；`mmp/9` → `mmp/9`。

    为什么不能只比 `/` 前面那段：`mmp/1` 的标签恒为 `mmp`，只比标签等于永远相等
    （实测 `mmp/9` 会被当成兼容握手通过）。协议号在 `/` 后面的整数位上。
    """
    s = str(value or "").strip()
    if "/" not in s:
        return s
    label, _sep, ver = s.partition("/")
    return label + "/" + ver.split(".")[0]


def _hello(req):
    client = str(req.get("protocol") or "")
    major_ok = (not client) or _proto_major(client) == _proto_major(PROTOCOL)
    caps = capabilities()
    resp = {"v": PROTOCOL, "id": req.get("id"), "ok": bool(major_ok),
            "code": 0 if major_ok else 2,
            "kind": "ok" if major_ok else "unsupported",
            "retryable": False, "text": "",
            "meta": {"duration_ms": 0, "wrote": False},
            "data": {"protocol": PROTOCOL, "version": VERSION,
                     "schema_version": SCHEMA_VERSION, "ops": list(OPS),
                     "client_protocol": client or None}}
    if not major_ok:
        resp["err"] = "协议不兼容：服务端 " + PROTOCOL + "，客户端 " + client
        resp["data"]["supported"] = [PROTOCOL]
    elif req.get("capabilities"):
        resp["data"]["capabilities"] = caps
    return resp


def _call_op(req, data_root):
    command = req.get("command") or req.get("cmd")
    if not command:
        return _bad(req, "call 需要 command 字段", "usage")
    if command in ("serve",):
        return _bad(req, "serve 本身就是服务端，不能用 call 调它", "unsupported")
    params = req.get("params") or {}
    if not isinstance(params, dict):
        return _bad(req, "params 必须是对象", "usage")
    root = req.get("data_root") or data_root
    started = time.time()
    resp = call_result(command, params, data_root=root, ns=req.get("ns"),
                       json_out=req.get("json", None) if isinstance(req.get("json"), bool) else None)
    resp["id"] = req.get("id")
    resp["meta"]["duration_ms"] = int((time.time() - started) * 1000)
    return resp


def _bad(req, message, kind="usage"):
    return {"v": PROTOCOL, "id": req.get("id"), "ok": False, "code": 2, "kind": kind,
            "retryable": False, "text": "", "err": message,
            "meta": {"duration_ms": 0, "wrote": False}}


def handle_request(req, data_root=None):
    """处理一条 serve 请求。兼容旧形态 `{"args": [...]}`（等同 CLI 参数数组）。"""
    if not isinstance(req, dict):
        return _bad({}, "请求必须是 JSON 对象", "usage")
    rid = req.get("id")
    op = str(req.get("op") or "").strip()
    if not op:
        # 旧形态：{"args": ["add", "--text", "..."]}
        legacy = req.get("args")
        if isinstance(legacy, list) and legacy:
            started = time.time()
            argv = [str(a) for a in legacy]
            root = req.get("data_root") or data_root
            if root:
                argv = ["--data-root", str(root)] + argv
            code, out, err = run_inproc(argv)
            resp = _envelope(rid, code, out, err, False, started, extra={"argv": argv})
            resp["id"] = rid
            return resp
        return _bad(req, "请求要带 op（hello/ping/capabilities/describe/call/batch/exit）", "usage")
    if op == "ping":
        return {"v": PROTOCOL, "id": rid, "ok": True, "code": 0, "kind": "ok",
                "retryable": False, "text": "", "pong": True,
                "meta": {"duration_ms": 0, "wrote": False}}
    if op == "exit":
        return {"v": PROTOCOL, "id": rid, "ok": True, "code": 0, "kind": "ok",
                "retryable": False, "text": "", "bye": True,
                "meta": {"duration_ms": 0, "wrote": False}}
    if op == "hello":
        return _hello(req)
    if op == "capabilities":
        resp = _bad(req, "", "ok")
        resp.update({"ok": True, "code": 0, "kind": "ok", "err": None,
                     "data": capabilities()})
        resp.pop("err", None)
        return resp
    if op == "describe":
        try:
            data = describe(req.get("command"))
        except (KeyError, TypeError):
            return _bad(req, "未知命令：" + str(req.get("command")), "not_found")
        resp = _bad(req, "", "ok")
        resp.update({"ok": True, "code": 0, "kind": "ok", "data": data})
        resp.pop("err", None)
        return resp
    if op == "call":
        return _call_op(req, data_root)
    if op == "batch":
        items = req.get("items")
        if not isinstance(items, list) or not items:
            return _bad(req, "batch 需要 items 数组", "usage")
        if len(items) > MAX_BATCH:
            return _bad(req, "batch 最多 " + str(MAX_BATCH) + " 条", "usage")
        results = []
        for item in items:
            if not isinstance(item, dict):
                results.append(_bad({"id": None}, "batch 条目必须是对象", "usage"))
                continue
            sub = dict(item)
            sub.setdefault("op", "call")
            if req.get("data_root"):
                sub.setdefault("data_root", req.get("data_root"))
            results.append(handle_request(sub, data_root=data_root))
            if req.get("stop_on_error") and not results[-1].get("ok"):
                break
        return {"v": PROTOCOL, "id": rid, "ok": all(r.get("ok") for r in results),
                "code": 0 if all(r.get("ok") for r in results) else 2,
                "kind": "ok" if all(r.get("ok") for r in results) else "batch_partial",
                "retryable": False, "text": "",
                "meta": {"duration_ms": 0, "wrote": any(r.get("meta", {}).get("wrote") for r in results)},
                "results": results}
    return _bad(req, "不认识的 op：" + op + "（支持：" + "、".join(OPS) + "）", "unsupported")


def _force_utf8(stream):
    """协议是 UTF-8 JSON Lines（RFC 8259），所以 stdin/stdout 必须按 UTF-8 解释。

    不能沿用系统地区编码：Windows 上是 cp936，客户端按 UTF-8 写过来会被解成乱码，
    甚至解出孤立代理字符（surrogate），随后写事件时抛 UnicodeEncodeError。
    **协议自己的编码由协议自己定，不跟调用方的控制台走。**
    """
    encoding = str(getattr(stream, "encoding", "") or "").lower().replace("-", "")
    reconfigure = getattr(stream, "reconfigure", None)
    if reconfigure is not None and encoding != "utf8":
        try:
            reconfigure(encoding="utf-8")
        except (ValueError, OSError):
            pass


def serve_stdio(stdin=None, stdout=None, data_root=None):
    """按行读请求、按行写响应；读到 EOF 或 op=exit 结束。"""
    import sys
    inp = stdin if stdin is not None else sys.stdin
    outp = stdout if stdout is not None else sys.stdout
    _force_utf8(inp)
    _force_utf8(outp)
    while True:
        try:
            raw = inp.readline()
        except UnicodeDecodeError as exc:
            bad = {"v": PROTOCOL, "id": None, "ok": False, "code": 2, "kind": "usage",
                   "retryable": False, "text": "",
                   "err": "请求不是合法 UTF-8：" + str(exc),
                   "meta": {"duration_ms": 0, "wrote": False}}
            outp.write(json.dumps(bad, ensure_ascii=False) + NL)
            outp.flush()
            break
        if not raw:
            break
        line = raw.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            resp = {"v": PROTOCOL, "id": None, "ok": False, "code": 2, "kind": "usage",
                    "retryable": False, "text": "", "err": "请求不是合法 JSON",
                    "meta": {"duration_ms": 0, "wrote": False}}
            req = None
        else:
            resp = handle_request(req, data_root=data_root)
        outp.write(json.dumps(resp, ensure_ascii=False) + NL)
        outp.flush()
        if isinstance(req, dict) and str(req.get("op") or "").strip() == "exit":
            break
    return 0
