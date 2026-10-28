"""纯 Python WordPiece 分词器（BERT 口径），只依赖标准库。

为什么自己写而不是引 `tokenizers`：这是本项目"精简 + 高定制"的唯一划算落点 ——
BERT 的分词规则只有四步（清洗 → 中文加空格 → 标点切分 → 贪心最长匹配），
200 行能写完；换掉一个 7MB 的 Rust 轮子，同时让分词行为完全可审计、可断言。

口径与 HuggingFace `BertTokenizer`（bert-base-chinese / bge-small-zh-v1.5）一致：
  normalize: 控制字符剔除、空白归一为单个空格、ASCII 小写（中文不受影响）
  pre-tokenize: 每个 CJK 汉字前后断开；标点（Unicode P* 类）逐字符断开
  wordpiece: 贪心最长匹配，续接片段加 "##" 前缀；单"词"超过 100 字符判 [UNK]

对齐验证：`_Temp/记忆内核/Scratch/verify_wordpiece.py` 与 HF tokenizers 逐 id 比对。
"""

import unicodedata

PAD, UNK, CLS, SEP, MASK = "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"
MAX_CHARS_PER_WORD = 100


def _is_cjk(ch):
    code = ord(ch)
    return (0x4E00 <= code <= 0x9FFF or 0x3400 <= code <= 0x4DBF
            or 0x20000 <= code <= 0x2A6DF or 0xF900 <= code <= 0xFAFF
            or 0x2F800 <= code <= 0x2FA1F)


def _is_punct(ch):
    if ch in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~":
        return True
    return unicodedata.category(ch).startswith("P")


def _clean(text):
    out = []
    for ch in str(text or ""):
        if ch in ("\t", "\n", "\r"):
            out.append(" ")
            continue
        cat = unicodedata.category(ch)
        if cat in ("Cc", "Cf"):
            continue
        out.append(" " if ch.isspace() else ch)
    return "".join(out)


def _split_cjk(text):
    """每个 CJK 字符单独成段（BERT 的 _tokenize_chinese_chars）。"""
    out = []
    for ch in text:
        out.append(" " + ch + " " if _is_cjk(ch) else ch)
    return "".join(out)


def basic_tokens(text, lowercase=True):
    text = _clean(text)
    if lowercase:
        text = text.lower()
    text = _split_cjk(text)
    toks = []
    for chunk in text.split():
        buf = []
        for ch in chunk:
            if _is_punct(ch):
                if buf:
                    toks.append("".join(buf))
                    buf = []
                toks.append(ch)
            else:
                buf.append(ch)
        if buf:
            toks.append("".join(buf))
    return toks


class WordPiece(object):
    """加载 vocab.txt（每行一个 token，行号即 id）。"""

    def __init__(self, vocab_path, lowercase=True):
        # lowercase 口径：上游 bert-base-chinese（bge-small-zh 的底座）是 do_lower_case=True，
        # 官方 tokenizer_config 也是 True；某些 ONNX 端口的 tokenizer.json 写成了 false，
        # 那会让 SSH/APK/BERT 全部变成 [UNK]。默认 True，实测对比见 NOTICE.md。
        self.lowercase = bool(lowercase)
        self.vocab = {}
        with open(str(vocab_path), "r", encoding="utf-8") as fh:
            for idx, line in enumerate(fh):
                self.vocab[line.rstrip("\n")] = idx
        self.pad_id = self.vocab.get(PAD, 0)
        self.unk_id = self.vocab.get(UNK, 100)
        self.cls_id = self.vocab.get(CLS, 101)
        self.sep_id = self.vocab.get(SEP, 102)
        self.max_token_chars = max(len(t) for t in self.vocab) if self.vocab else 1

    def _wordpiece(self, word):
        if len(word) > MAX_CHARS_PER_WORD:
            return [self.unk_id]
        ids, start = [], 0
        n = len(word)
        while start < n:
            end = min(n, start + self.max_token_chars)
            cur = None
            while start < end:
                piece = word[start:end]
                if start > 0:
                    piece = "##" + piece
                if piece in self.vocab:
                    cur = piece
                    break
                end -= 1
            if cur is None:
                return [self.unk_id]
            ids.append(self.vocab[cur])
            start = end
        return ids

    def encode(self, text, max_length=512):
        """返回 (input_ids, attention_mask)，已含 [CLS]/[SEP] 并截断到 max_length。"""
        ids = [self.cls_id]
        for word in basic_tokens(text, self.lowercase):
            ids.extend(self._wordpiece(word))
            if len(ids) >= max_length - 1:
                break
        ids = ids[:max_length - 1] + [self.sep_id]
        return ids, [1] * len(ids)
