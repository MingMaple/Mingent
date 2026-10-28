"""记忆内核（mem core）——通用、可检索、会衰减、能巩固、可重建的长期记忆。

单一真相源：<data-root>/events/*.jsonl   append-only，一行一个 JSON 事件
可弃投影：  <data-root>/index.sqlite     FTS5 全文 + 图边 + 强度，可随时 rebuild

只用 Python 标准库；向量通道可选（缺依赖时自动降级为 BM25 + 图扩散）。
脚本自定位，不依赖当前工作目录。
"""

__version__ = "0.1.0"
