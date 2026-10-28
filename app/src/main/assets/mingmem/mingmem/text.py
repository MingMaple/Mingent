import json
import os
import random
import re
from datetime import datetime

from .config import load_config
from .settings import CJK_RE


def now_iso():
    """写入时间戳。保留毫秒：跨文件的事件只靠 ts 排序，秒级精度会让同一秒内的事件乱序。"""
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def parse_ts(text):
    try:
        return datetime.fromisoformat(str(text))
    except Exception:
        return None


def event_sort_key(ts):
    """重放排序键：解析成时间戳，避免不同时区偏移下按字符串排错。"""
    dt = parse_ts(ts)
    if dt is None:
        return 0.0
    try:
        return dt.timestamp()
    except Exception:
        return 0.0


def clamp01(x):
    return min(1.0, max(0.0, float(x)))


def parse_list(text):
    if text is None:
        return []
    if isinstance(text, (list, tuple)):
        return [str(x).strip() for x in text if str(x).strip()]
    return [p.strip() for p in re.split(r"[,，;；]", str(text)) if p.strip()]


def json_list(text):
    if not text:
        return []
    try:
        v = json.loads(text)
    except Exception:
        return []
    if isinstance(v, list):
        return [str(x) for x in v]
    return []


def tokenize(text):
    """中文 bigram + ASCII 词。FTS5 默认分词器会把整段中文当一个 token。"""
    out = []
    if not text:
        return out
    for seg in re.findall(r"[\u4e00-\u9fff]+|[0-9A-Za-z_.+\-]+", str(text)):
        if CJK_RE.match(seg):
            if len(seg) == 1:
                out.append(seg)
            else:
                for i in range(len(seg) - 1):
                    out.append(seg[i:i + 2])
        else:
            t = seg.lower()
            if re.search(r"[0-9a-z]", t):
                out.append(t)
    return out


def index_tokens(text):
    """索引侧 token：查询用的 token + 中文单字。

    为什么索引侧要额外放单字：查询侧是 bigram，单个汉字根本不在索引里，
    于是「内」这种单字查询 0 命中（E2）。索引侧同时存单字后，单字查询走同一套
    BM25 就能命中，不需要 LIKE 全表扫。查询侧分词口径保持不变。
    """
    toks = tokenize(text)
    extra = []
    for seg in re.findall("[" + chr(0x4E00) + "-" + chr(0x9FFF) + "]+", str(text or "")):
        if len(seg) >= 2:
            extra.extend(list(seg))
    return toks + extra


def new_id(kind):
    stamp = datetime.now().strftime("%y%m%d%H%M%S")
    return "%s-%s-%s" % (str(kind)[:4], stamp, "%04x" % random.getrandbits(16))


def ns_filename(ns):
    name = str(ns).replace(":", "-")
    name = re.sub(r"[^0-9A-Za-z_\-\u4e00-\u9fff]", "-", name)
    name = re.sub(r"-{2,}", "-", name).strip("-")
    return (name or "unnamed") + ".jsonl"


def short_ts(ts):
    s = str(ts or "")
    if len(s) >= 16 and s[10] == "T":
        return s[5:10] + " " + s[11:16]
    return s[:16]


def summarize(text, n=42):
    t = re.sub(r"\s+", " ", str(text or "")).strip()
    if len(t) <= n:
        return t
    return t[:max(1, n - 1)] + "…"


def default_ns():
    """默认命名空间（D-02 口径）：环境变量 > 配置 default_ns > 当前目录名 > global。

    取向：**项目层做默认，global 必须显式写** —— 防止某个项目的噪声被自动升级成
    跨项目画像。模块接入时建议显式给 --ns 或设 MINGMEM_NS。
    """
    for key in ("MINGMEM_NS", "MEM_NS"):
        val = (os.environ.get(key) or "").strip()
        if val:
            return val
    cfg_ns = str(load_config().get("default_ns") or "").strip()
    if cfg_ns:
        return cfg_ns
    try:
        name = os.path.basename(os.getcwd()).strip()
    except OSError:
        name = ""
    if name.endswith("库") and len(name) > 1:
        name = name[:-1]
    if name:
        return "project:" + name
    return "global"


# ---------------------------------------------------------------- 词边界信号
# 目的：中文没有空格，bigram 会把「记忆内核」切成 记忆｜忆内｜内核，于是查「忆内」
# 也能命中——因为这个 bigram 真的出现在字符串里。纯字符层面无法区分「忆内是词」
# 与「忆内只是两个词的接缝」，除非有词典。
# 本内核不引入词典（洁净件、零数据文件），改用**边界信号**近似：一次出现如果
# 左/右邻是「非汉字」（标点、空白、ASCII）、或位于串首串尾、或是虚词/功能字，
# 就认为它落在词边界上；两侧都是实词字则视为可疑接缝。
# 代价（已知、写进文档）：长词内部的真匹配也会被判成接缝，如「记忆内核重构」查
# 「内核」。这是无词典的必然代价，缓解办法是查询写长一点或调低 --min-score。
FUNC_CHARS = set(
    "的了着过是在和与及或就都也才又很更最不没别要有会能可需把被给从对向于而并等"
    "呢吗吧啊嘛呀个这那我你他她它们其之以为还只但所以则如若且则上下中里外时后前"
    "一二三四五六七八九十百千多少几每各些么怎谁何"
)


def is_cjk_char(ch):
    return bool(ch) and bool(CJK_RE.match(ch))


def term_occurrences(text, term):
    """返回 [(起点下标, 左右是否都落在实词字上)]；后者 True 表示「可疑接缝」。"""
    out = []
    if not term:
        return out
    start = 0
    while True:
        i = str(text).find(term, start)
        if i < 0:
            return out
        j = i + len(term)
        left_cjk = i > 0 and is_cjk_char(text[i - 1]) and text[i - 1] not in FUNC_CHARS
        right_cjk = j < len(text) and is_cjk_char(text[j]) and text[j] not in FUNC_CHARS
        out.append((i, left_cjk and right_cjk))
        start = i + 1


def term_boundary_ok(term):
    """多长的词才要求边界：只有 2 字中文 bigram 会被跨词误伤，其余不判。"""
    return len(term) == 2 and bool(CJK_RE.match(term[0])) and bool(CJK_RE.match(term[1]))


def term_match(text, term):
    """(是否出现, 是否有一次出现落在词边界上)。"""
    occ = term_occurrences(text, term)
    if not occ:
        return False, False
    if not term_boundary_ok(term):
        return True, True
    return True, any(not seam for _i, seam in occ)


def bigram_set(text):
    """字符 2-gram 集合（中文按字切、英文数字按字符），用于近似重复检测。

    向量通道停用时，这是唯一可用的「语义近似」度量；它不是语义相似度，
    只衡量字面重合，因此只用于去冗余，不参与召回排序打分。
    """
    s = re.sub("[^0-9A-Za-z" + chr(0x4E00) + "-" + chr(0x9FFF) + "]+", "", str(text or ""))
    if len(s) < 2:
        return set([s]) if s else set()
    return set(s[i:i + 2] for i in range(len(s) - 1))


def jaccard_bigrams(a, b):
    A, B = bigram_set(a), bigram_set(b)
    if not A or not B:
        return 0.0
    return len(A & B) / float(len(A | B))
