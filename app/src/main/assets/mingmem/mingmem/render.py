import json
import re
from pathlib import Path

from .cli import emit, fail
from .core import audit_ask
from .decay import age_days, decayed
from .projection import apply_event, open_projection, sync_signature
from .settings import NL
from .text import json_list, now_iso, parse_list, short_ts, summarize


def export_filter(args):
    """把 --ns / --kind / --tag / 披露等级变成 SQL 条件（模块用它生成笔记与看板）。"""
    conds = []
    params = []
    # 回收站里的记忆不出现在任何导出视图里。
    conds.append("COALESCE(erased, 0) = 0")
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
        lines.extend(["---", "generated: " + now_iso(), "source: mingmem export",
                      "tags:", "  - 记忆导出", "---", ""])
    lines.append("# " + title)
    lines.append("")
    lines.append("> 由 mingmem（记忆内核）export 生成；**可重建视图**，不是真相源（真相源是 events/*.jsonl）。")
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
    # G20：导出 ask 级记忆同样要留同意记录。
    consent = None
    if getattr(args, "include_ask", False):
        ask_conds = list(conds) + ["COALESCE(disclosure, 'public') = 'ask'"]
        ask_where = " WHERE " + " AND ".join(ask_conds)
        ask_ids = [row[0] for row in conn.execute(
            "SELECT id FROM nodes" + ask_where + " ORDER BY ts", list(params)).fetchall()]
        if ask_ids:
            consent = audit_ask(store, None, "export", ask_ids)
            apply_event(conn, consent)
            sync_signature(store, conn)
    conn.close()
    if args.json:
        print(json.dumps({"out": str(out_path), "lines": content.count(NL),
                          "namespaces": ns_list, "format": fmt, "order": order_raw,
                          "lean": lean, "no_meta": no_meta,
                          "ask_audit": consent["id"] if consent else None},
                         ensure_ascii=False, indent=2))
    else:
        emit("已导出 " + str(out_path) + "（" + str(content.count(NL)) + " 行，" + str(len(ns_list)) +
             " 个命名空间）", args.quiet)
    return 0
