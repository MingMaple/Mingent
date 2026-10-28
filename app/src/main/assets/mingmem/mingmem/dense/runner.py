"""稠密通道执行器：ONNX 推理 + CLS 池化 + L2 归一化。

依赖是可选的：没有 `onnxruntime` / `numpy`，或模型文件缺失时，
`available()` 返回 False 并给出原因，内核自动回落到"词面通道"（BM25 + 图扩散），
**不抛异常、不影响任何现有命令**。
"""

import hashlib
from pathlib import Path

_MODEL_DIR = Path(__file__).resolve().parent / "model"
_MODEL_PATH = _MODEL_DIR / "model_q8.onnx"
_VOCAB_PATH = _MODEL_DIR / "vocab.txt"

QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
MODEL_NAME = "bge-small-zh-v1.5 (int8 onnx)"
DIM = 512
MAX_TOKENS = 512

_STATE = {"tried": False, "session": None, "tokenizer": None, "np": None, "reason": ""}


def _load():
    if _STATE["tried"]:
        return _STATE
    _STATE["tried"] = True
    if not _MODEL_PATH.exists():
        _STATE["reason"] = "模型文件缺失：" + str(_MODEL_PATH)
        return _STATE
    if not _VOCAB_PATH.exists():
        _STATE["reason"] = "词表缺失：" + str(_VOCAB_PATH)
        return _STATE
    try:
        import numpy as np
    except Exception as exc:
        _STATE["reason"] = "缺 numpy（" + str(exc)[:60] + "）；装 onnxruntime 会一并带上"
        return _STATE
    try:
        import onnxruntime as ort
    except Exception as exc:
        _STATE["reason"] = "缺 onnxruntime（" + str(exc)[:60] + "）；pip install onnxruntime"
        return _STATE
    try:
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1        # 单线程：确定性优先，短句单线程已足够
        opts.inter_op_num_threads = 1
        session = ort.InferenceSession(str(_MODEL_PATH), sess_options=opts,
                                       providers=["CPUExecutionProvider"])
    except Exception as exc:
        _STATE["reason"] = "模型加载失败：" + str(exc)[:120]
        return _STATE
    from .wordpiece import WordPiece
    _STATE["session"] = session
    _STATE["tokenizer"] = WordPiece(_VOCAB_PATH)
    _STATE["np"] = np
    _STATE["reason"] = ""
    return _STATE


def available():
    return _load()["session"] is not None


def reason():
    """不可用时为什么不可用（doctor / CLI 报错用）。"""
    st = _load()
    return "" if st["session"] is not None else (st["reason"] or "未知原因")


def sha256(path=_MODEL_PATH):
    h = hashlib.sha256()
    with open(str(path), "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def info():
    st = _load()
    out = {"available": st["session"] is not None, "model": MODEL_NAME,
           "dim": DIM, "max_tokens": MAX_TOKENS,
           "runtime": "", "reason": st["reason"]}
    if st["session"] is not None:
        try:
            import onnxruntime as ort
            out["runtime"] = "onnxruntime " + ort.__version__ + " / numpy " + st["np"].__version__
        except Exception:
            pass
    return out


def embed(texts, is_query=False, batch=1):
    """返回 numpy 数组 [n, 512]（已 L2 归一化）；不可用时返回 None。

    默认 **batch=1**：实测 int8 动态量化会按批内激活范围定标，同一句话放进不同批次
    余弦只有 0.9954（逐元素差 0.013），`rebuild` 后检索顺序会漂移 ⇒ 违反"投影可重建"。
    batch=1 时逐位一致（实测差 0.0），代价是 160 条/秒而不是 1200 条/秒：
    对"写入时向量化"完全够用，对 10 万条重建是一次性 10 分钟级开销。

    查询侧加指令前缀：BGE 系列的检索口径要求 query 带
    「为这个句子生成表示以用于检索相关文章：」，文档侧不加。不加会系统性掉点。
    """
    st = _load()
    if st["session"] is None:
        return None
    np = st["np"]
    tok = st["tokenizer"]
    sess = st["session"]
    texts = [str(t or "") for t in texts]
    if not texts:
        return np.zeros((0, DIM), dtype=np.float32)
    feeds = [tok.encode((QUERY_PREFIX + t) if is_query else t, max_length=MAX_TOKENS)
             for t in texts]
    out = [None] * len(feeds)
    names = [i.name for i in sess.get_inputs()]

    def _bucket(n):
        for cap in (64, 128, 256, 512):
            if n <= cap:
                return cap
        return MAX_TOKENS

    # 按长度分桶：同一句话永远落在同一桶 ⇒ 形状固定 ⇒ **逐位可复现**。
    # 为什么不能按批内最长填充：int8 动态量化对序列长度敏感，实测同一句在不同批次里
    # 余弦只有 0.9954，rebuild 后检索顺序会变（违反"投影可重建"的不变量）。
    # 为什么不能一律填到 512：吞吐从 1200 条/秒掉到 10 条/秒。
    buckets = {}
    for i, (ids, _m) in enumerate(feeds):
        buckets.setdefault(_bucket(len(ids)), []).append(i)
    for width, idxs in sorted(buckets.items()):
        for start in range(0, len(idxs), batch):
            part = idxs[start:start + batch]
            ids = np.zeros((len(part), width), dtype=np.int64)
            mask = np.zeros((len(part), width), dtype=np.int64)
            for row, k in enumerate(part):
                seq, m = feeds[k]
                ids[row, :len(seq)] = seq
                mask[row, :len(m)] = m
            feed = {"input_ids": ids, "attention_mask": mask}
            if "token_type_ids" in names:
                feed["token_type_ids"] = np.zeros_like(ids)
            hidden = sess.run(None, feed)[0]
            cls = hidden[:, 0, :].astype(np.float32)
            norm = np.linalg.norm(cls, axis=1, keepdims=True)
            vecs = cls / np.clip(norm, 1e-9, None)
            for row, k in enumerate(part):
                out[k] = vecs[row]
    return np.vstack(out)
    return np.vstack(out)
