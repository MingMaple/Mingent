"""稠密通道（可选能力，内核主体不依赖它）。

对外只暴露四件事：`available()` / `reason()` / `info()` / `embed*()`。
没有 onnxruntime 或模型文件时全部安全降级，调用方（vector.py）不需要 try/except。
"""

from .runner import (DIM, MAX_TOKENS, MODEL_NAME, QUERY_PREFIX, available, embed, info,
                     reason, sha256)

__all__ = ["DIM", "MAX_TOKENS", "MODEL_NAME", "QUERY_PREFIX", "available", "reason",
           "info", "sha256", "embed_docs", "embed_query"]


def embed_docs(texts):
    return embed(texts, is_query=False)


def embed_query(text):
    vecs = embed([text], is_query=True)
    return None if vecs is None else vecs[0]
