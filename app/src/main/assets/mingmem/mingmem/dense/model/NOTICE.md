# 第三方资产说明（稠密通道）

本目录下的模型文件**不是本项目原创**，按上游许可（MIT）原样内置；本项目未修改权重。

## bge-small-zh-v1.5

- 上游模型：`BAAI/bge-small-zh-v1.5`（BAAI / FlagEmbedding），许可 **MIT**
- 结构：BERT-base 量级，24M 参数，输出 512 维，最大 512 token，CLS 池化 + L2 归一化
- 转换来源：`optimum-cli export onnx`（FP32）后做动态 int8 量化，由第三方 ONNX 仓库再分发
  （ModelScope `Maiteka/bge-small-zh-v1.5-onnx`）；本项目只取其中 int8 文件，未重新量化、未改动权重
- 检索用法：**查询侧**需加指令前缀「为这个句子生成表示以用于检索相关文章：」，**文档侧不加**

## 内置文件的 SHA-256（发布前请核对）

| 文件 | 字节 | SHA-256 |
|---|---|---|
| `model_q8.onnx` | 23,942,540 | `bcb1f7d6cf43a0ddf3775439da04d4eef5f0e60dcb05796ff1218e9ab9dc23c4` |
| `vocab.txt` | 109,540 | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` |

`tokenizer.json`（**未内置**，仅用于对齐验证）的 SHA-256 为
`48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26`，
与 HuggingFace `Xenova/bge-small-zh-v1.5` ONNX 端口所记录的值**逐字节一致**，
可作为"词表与分词口径来自上游、未被我们改动"的旁证。

## 运行时依赖（不内置、不修改）

| 依赖 | 许可 | 说明 |
|---|---|---|
| `onnxruntime` | MIT | 推理运行时，作为可选依赖安装 |
| `numpy` | BSD-3-Clause | 矩阵运算（onnxruntime 的依赖） |

`tokenizers`（Apache-2.0）只用于开发期对齐验证，**运行时不依赖**：分词器是本项目
`mingmem/dense/wordpiece.py` 的纯 Python 实现（见该文件头部说明）。
