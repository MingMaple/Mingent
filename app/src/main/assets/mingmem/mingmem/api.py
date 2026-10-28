"""第三层：Python 接口（薄适配）。

设计口径（用户裁定）：**内核只提供接口与规则，不替调用方写它自己的适配层**。
所以这一层只有两种形态：

1. **通用调用**（推荐、覆盖一切）：`MemoryCore.call(command, **params)`。
   参数名 = `describe()` 里的 `name`（即 argparse 的 dest），所以**任意命令、任意参数**
   都能调，内核加参数不需要改这里的代码。
2. **便捷糖**：`add / recall / search / ...` 每个命令一个方法，内部就是 `call`。

失败一律抛 `MingmemError`（= `rpc.RpcError`，带 `code / kind / retryable`），
**不返回空值装成功**。协议与服务端（`serve --stdio`）也在本模块导出，方便宿主直接用。
"""

from .rpc import (MAX_BATCH, PROTOCOL, RpcError, argv_from_params, call as rpc_call,
                  call_result as rpc_call_result, serve_stdio, handle_request)
from .settings import VERSION  # noqa: F401  （对外导出）

MingmemError = RpcError


class MemoryCore(object):
    """给人/给别的项目用的 Python 接口：一个通用 call + 一组糖。"""

    def __init__(self, data_root=None, ns=None):
        self.data_root = str(data_root) if data_root else None
        self.ns = ns

    # ---------------------------------------------------------------- 通用
    def capabilities(self):
        from .spec import capabilities
        return capabilities()

    def describe(self, command=None):
        from .spec import describe
        return describe(command)

    def call_result(self, command, **params):
        """完整信封（不抛异常）：`{ok, code, kind, retryable, data, text, err, meta}`。"""
        ns = params.pop("ns", None) or self.ns
        return rpc_call_result(command, params, data_root=self.data_root, ns=ns)

    def call(self, command, **params):
        """通用调用：成功返回 `data`（无 JSON 时返回文本），失败抛 `MingmemError`。"""
        ns = params.pop("ns", None) or self.ns
        resp = rpc_call(command, params, data_root=self.data_root, ns=ns)
        if "data" in resp:
            return resp["data"]
        return resp.get("text", "")

    # ---------------------------------------------------------------- 便捷糖
    def add(self, text, kind="episode", tags=None, entities=None, refs=None,
            disclosure=None, source=None, variables=None, client_key=None,
            ns=None, dry_run=False):
        params = {"text": text, "kind": kind or "episode", "ns": ns}
        for key, val in (("tags", tags), ("entities", entities), ("refs", refs),
                         ("disclosure", disclosure), ("source", source),
                         ("client_key", client_key)):
            if val is not None:
                params[key] = val
        for key, val in (variables or {}).items():
            if key in ("S", "D", "E", "C", "I") and val is not None:
                params[key] = val
        if dry_run:
            params["dry_run"] = True
        return self.call("add", **params)

    def consolidate(self, from_ids, text, kind="semantic", tags=None, ns=None, force=False):
        params = {"from_ids": from_ids, "text": text, "kind": kind or "semantic", "ns": ns}
        if tags is not None:
            params["tags"] = tags
        if force:
            params["force"] = True
        return self.call("consolidate", **params)

    def correct(self, target, text, tags=None, ns=None):
        params = {"target": target, "text": text, "ns": ns}
        if tags is not None:
            params["tags"] = tags
        return self.call("correct", **params)

    def forget(self, ids, reason=None, ns=None):
        params = {"ids": ids, "ns": ns}
        if reason:
            params["reason"] = reason
        return self.call("forget", **params)

    def restore(self, ids, ns=None):
        return self.call("restore", ids=ids, ns=ns)

    def purge(self, ids):
        return self.call("purge", ids=ids, yes=True)

    def recall(self, query, limit=5, scope=None, ns=None, include_ask=False,
               include_archived=False, dry_run=False):
        params = {"query": query, "limit": int(limit), "ns": ns}
        for key, val in (("scope", scope), ("include_ask", include_ask),
                         ("include_archived", include_archived), ("dry_run", dry_run)):
            if val:
                params[key] = val
        return self.call("recall", **params)

    def search(self, query, limit=20, scope=None, ns=None, include_ask=False,
               include_archived=False):
        params = {"query": query, "limit": int(limit), "ns": ns}
        for key, val in (("scope", scope), ("include_ask", include_ask),
                         ("include_archived", include_archived)):
            if val:
                params[key] = val
        return self.call("search", **params)

    def links(self, node_id):
        return self.call("links", id=node_id)

    def status(self):
        return self.call("status")

    def bin(self, limit=None, offset=None):
        return self.call("bin", limit=limit, offset=offset)

    def verify(self, pin=None, ns=None):
        return self.call("verify", pin=pin, ns=ns)

    def cycle(self, kinds=None, ns=None, dry_run=False):
        params = {"ns": ns}
        if kinds:
            params["kinds"] = kinds
        if dry_run:
            params["dry_run"] = True
        return self.call("cycle", **params)

    def rebuild(self):
        return self.call("rebuild")

    def doctor(self):
        return self.call("doctor")

    def export(self, out=None, ns=None, kinds=None, tags=None, exclude_tags=None,
               order=None, lean=False, toc=False, fmt=None, include_private=False,
               include_ask=False):
        params = {"out": out, "ns": ns, "kind": kinds, "tag": tags,
                  "exclude_tag": exclude_tags, "order": order, "format": fmt}
        for key, val in (("lean", lean), ("toc", toc), ("include_private", include_private),
                         ("include_ask", include_ask)):
            if val:
                params[key] = True
        return self.call("export", **params)

    def selftest(self):
        return self.call("selftest")


def cmd_serve(args, store):
    if not getattr(args, "stdio", False):
        import sys
        sys.stderr.write("错误：serve 目前只支持 --stdio（JSON Lines）" + chr(10))
        return 2
    return serve_stdio(data_root=str(store.root))
