import re
from pathlib import Path


SCHEMA_VERSION = 5        # 5：nodes 增 client_key（写操作幂等键）
VERSION = "0.3.3"        # 软件版本（与协议 mmp/1、投影 schema 版本独立）
NL = chr(10)

SCRIPT_PATH = Path(__file__).resolve()
LIB_ROOT = SCRIPT_PATH.parent.parent
# 旧版「按包位置反推的默认数据根」。D-02 起不再作为数据根使用，
# 只作为「绝不写入」的保护名单（doctor 对账、eval 隔离检查会引用）。
# 仓根在 记忆内核库/mingmem 下，旧库的数据根是上一级。
LEGACY_DATA_ROOT = LIB_ROOT.parent / "_Data"

# ------------------------------------------------------------------ 常量与默认值

EVENT_KINDS = ("episode", "note", "feel", "prefer", "decide", "consolidate",
               "correction", "access", "sweep", "archive", "link", "consent",
               "forget", "restore", "purge")
NODE_KINDS = ("episode", "note", "feel", "prefer", "decide",
              "semantic", "preference", "skill", "correction")
CONSOLIDATE_KINDS = ("semantic", "preference", "skill")
DISCLOSURES = ("public", "ask", "private")
SOURCES = ("user", "ai", "mixed")

DEFAULT_VARS = {
    "episode":    {"S": 0.5, "D": 0.7, "E": 0.2, "C": 0.1, "I": 0.1},
    "note":       {"S": 0.5, "D": 0.6, "E": 0.1, "C": 0.1, "I": 0.1},
    "feel":       {"S": 0.6, "D": 0.6, "E": 0.7, "C": 0.1, "I": 0.1},
    "prefer":     {"S": 0.7, "D": 0.5, "E": 0.2, "C": 0.3, "I": 0.1},
    "decide":     {"S": 0.8, "D": 0.6, "E": 0.1, "C": 0.3, "I": 0.1},
    "semantic":   {"S": 0.8, "D": 0.5, "E": 0.1, "C": 0.8, "I": 0.1},
    "preference": {"S": 0.8, "D": 0.5, "E": 0.2, "C": 0.8, "I": 0.1},
    "skill":      {"S": 0.8, "D": 0.6, "E": 0.1, "C": 0.7, "I": 0.1},
    "correction": {"S": 0.8, "D": 0.6, "E": 0.1, "C": 0.4, "I": 0.1},
}

# 三条衰减（详见设计文档）：细节 D、意义 S、情绪 E；λE < λS < λD
DECAY_BASE = {"D": 0.02, "S": 0.005, "E": 0.0015}
ALPHA, BETA, GAMMA = 0.6, 0.5, 0.4

STORAGE_INC = 0.05          # 访问：storage += 0.05 * (1 - storage)
RETRIEVAL_SWEEP = 0.95      # 遗忘扫描：retrieval *= 0.95
CO_RETRIEVAL_INC = 0.1      # 共检索强化（LTP）
ARCHIVE_STORAGE = 0.1       # 归档阈值
ARCHIVE_RETRIEVAL = 0.05

RRF_K = 60
# 重排权重：RRF 管「跨通道共识」，W_CH 管「单通道幅度」（RRF 会丢掉通道内分差）
W_RRF, W_CH, W_REC, W_E, W_S, W_R = 0.30, 0.20, 0.20, 0.15, 0.10, 0.05
RECENCY_TAU_DAYS = 30.0
HOP_DECAY = 0.6
GRAPH_HOPS = 2

BRIEFING_MAX_LINES = 24
DEFAULT_LIMIT = 5
MAX_LIMIT = 50
CONSOLIDATE_MIN = 3
# --json 支持矩阵（未列出的命令给 stderr 警告，仍走文本输出）
JSON_SUPPORTED = ("recall", "search", "status", "doctor",
                  "add", "consolidate", "correct", "cycle", "rebuild", "export", "links",
                  "forget", "bin", "restore", "purge", "verify", "import",
                  "capabilities", "describe")
VECTOR_MODEL = "bge-small-zh-v1.5 (int8 onnx)"
# 稠密通道（0.3.0）：默认启用，缺 onnxruntime/模型时自动降级为 BM25 + 图扩散。
# 不再有"节点数不到 2300 不许用"的门槛 —— P0 实测在 236 条语料上 alias 桶就从 0.381 涨到 0.952。
VECTOR_DIM = 512
VECTOR_PRECISION = "fp32"   # fp32 让"同机逐版本逐位一致"最干净；200MB@10 万条仍在预算内
VECTOR_BATCH = 32           # 向量化批大小（写库时）

# 最低相关分（检索证据门槛，0.2.1 引入）
# 为什么门槛不挂在融合后总分上：融合分含 recency/E/S 偏置，图扩散邻居经
# 「逐通道归一化」也能拿到 norm=1，实测噪声 0.81~0.83 与真命中 0.83 无法区分。
# 所以门槛只看**检索证据**：IDF 加权的查询词覆盖率 + 是否落在词边界上。
MIN_SCORE = 0.28            # 绝对地板：证据低于它直接判不相关（0 表示关闭过滤）
REL_KEEP_RATIO = 0.40       # 相对地板：还要达到本次最佳证据的这个比例，压掉长尾弱命中
SEAM_CREDIT = 0.45
# 稠密通道录取门槛（0.3.0）
# 实测：bge-small-zh 上"无关但同领域"的句子余弦也在 0.45~0.55，而 alias 用例的
# 正确答案最低只有 0.349 ⇒ **绝对余弦阈值不可用**（会把答案和噪声一起切掉）。
# 所以只用相对门槛：与本次最高分相差不超过 DENSE_ADMIT_REL 的候选才进入结果集。
DENSE_ADMIT_REL = 0.08      # 相对窗口：sim >= top_sim - 0.08
DENSE_ADMIT_ABS = 0.30      # 绝对下限（防止"全场都很低"时把噪声当答案）
DENSE_ADMIT_MAX = 5         # 稠密通道单独最多录取几条（与 --limit 取小）
# 重排时给稠密通道的独立权重（0.3.0 新增）。原来的 W_RRF/W_CH 是在"没有向量"时调的，
# 那时向量通道恒为空；接上稠密后，同义命中的 rrf 名次与词面命中混在一起会被压住。
DENSE_W = 0.45
          # 查询词只落在「接缝」（两侧都是实词字）时给几折证据
GRAPH_FILL = 1              # 词面证据为零时，图扩散最多补几条位（0 = 完全不用图扩散补位）

# 写入原子性（G27）与投影签名（G28）
SIG_TAIL_BYTES = 8192       # 投影签名只读事件文件尾部这么多字节
LOCK_TIMEOUT = 10.0         # 抢数据根写锁的超时（秒）
LOCK_STALE = 5.0            # 锁目录超过这么久视为残留并清理（临界区只有一次 append，秒级足够）

CJK_RE = re.compile(r"[\u4e00-\u9fff]")
