# mingmem

本地优先的记忆内核：append-only JSONL 事件流是唯一真相源，SQLite 投影可随时重建。
纯标准库，零第三方依赖；向量通道可选（缺依赖自动降级为 BM25 + 图扩散）。

## 跑

在仓根（本文件所在目录）执行：

    python -X utf8 -m mingmem <子命令>

22 个子命令：init add recall search links forget bin restore purge cycle consolidate correct rebuild status export verify doctor serve import capabilities describe selftest

（Windows 上务必带 -X utf8 或设 PYTHONUTF8=1，否则中文输出与 selftest 会出错。）

## 首次使用：初始化数据根

    python -X utf8 -m mingmem init --data-root <目录> [--default-ns project:<名>]

它把数据根写进用户级配置（Windows `%APPDATA%\mingmem\config.json`，POSIX
`$XDG_CONFIG_HOME/mingmem/config.json` 或 `~/.config/mingmem/config.json`；可用
`MINGMEM_CONFIG` 指向别的文件）。之后所有命令都不用再带 `--data-root`。

**没有配置就不工作**（退出码 2，并打印修复命令）——「记忆跑丢」是最坏故障，所以不设隐式默认。

数据根优先级：`--data-root` > `MINGMEM_DATA` > `MEM_DATA`（兼容旧名）> 配置文件。
命名空间优先级：`--ns` > `MINGMEM_NS` > 配置里的 `default_ns` > 按**当前目录名**反推 `project:<名>` > `global`。
取向：**项目层做默认，`global` 必须显式写**（防某个项目的噪声自动升级成跨项目画像）。

## 三层接口

| 层 | 形态 | 入口 |
|---|---|---|
| 1 | CLI（一等公民） | `python -X utf8 -m mingmem <子命令>` |
| 2 | 常驻服务（JSON Lines over stdio） | `python -X utf8 -m mingmem serve --stdio` |
| 3 | Python API | `from mingmem.api import MemoryCore` |

后两层是**薄适配**（都转调同一个 `cli.main`，不复制业务逻辑），所以三层行为永远一致。

```
$ python -X utf8 -m mingmem serve --stdio
{"id":1,"op":"ping"}
{"id":1,"ok":true,"code":0,"pong":true}
{"id":2,"args":["add","--text","常驻进程写的一条","--json"]}
{"id":2,"ok":true,"code":0,"out":"{...}","data":{"id":"epis-…","ns":"project:demo",…}}
{"id":9,"op":"exit"}
```@

Protocol：请求 `{"id","op"|"args","data_root"?}`，响应 `{"id","ok","code","out","data"?,"err"?}`；
`args` 就是 CLI 参数；`ok` 表示退出码是否为 0，`code` 是原始退出码。
（`op` 支持 `ping` / `exit`；读到 EOF 或 `exit` 就退出。）

```python
from mingmem.api import MemoryCore, MingmemError

core = MemoryCore(data_root=r"D:\MemoryData", ns="project:demo")
core.add("决定把内核做成插件内核", kind="decide", tags=["架构"])
briefing = core.recall("插件内核", limit=5)
try:
    core.links("nope-000000-0000")
except MingmemError as exc:
    print(exc.code, exc.message)      # 2 错误：找不到节点：nope-000000-0000
```@

约定：**失败抛 `MingmemError(message, code)`**，不返回空值装成功；每个方法对应一条 CLI 命令，
返回值是那条命令的 `--json` 结构（`selftest` 返回文本）。

## 自包含与版本（artifact 形态）

本项目按 **artifact（工程型 / 洁净件）** 登记，硬要求只有三条：自包含可构建、依赖登记、版本留档。

- **一条命令从零跑通**（不需要数据根、不需要配置、不碰真实库）：
  `python -X utf8 -m mingmem selftest` → 末行应是 `合计 66/66 项通过`。
- **运行与构建都不引用工作区路径**：代码里没有任何工作区绝对路径（只有自相对的 `LIB_ROOT`）；
  把整棵仓拷到别处（含系统临时目录）后跑上面那条命令仍然全绿 —— 这是自包含的验收方式。
- **无构建步骤**：纯 Python 源码，无编译产物、无 `dist/`；`pip install -e .` 属可选项（未实测）。
- **数据根必须显式**（`mingmem init --data-root`），无隐式默认 —— 见下节。
- **外部依赖登记**：`_Project_Meta/记忆内核/Workspace_Dependencies.md`（默认零第三方依赖）。
- **版本留档**：`_Archives/记忆内核/Backups/mingmem_V0.1.0/`（含 `版本说明.md` 与逐文件 `SHA256SUMS.txt`）。

## 接口规范（mmp/1）

**这是一份契约，不是建议。** 壳、插件、Agent、别的项目要依赖内核，就按 `docs/接口规范.md` 接 ——
内核不替任何调用方写它自己的适配层。

三层的共同入口是"命令表 + 参数表"：

`@
python -X utf8 -m mingmem capabilities      # 有哪些命令、限额、错误分类、接口层
python -X utf8 -m mingmem describe recall   # 某个命令的参数（从 argparse 抽，不会漂移）
`@

- **参数名的唯一来源是 argparse**：`describe` 直接从 parser 抽参数，所以"文档写的"和"实际能用的"不会分叉（自检有专项盯着）。
- **L2/L3 不需要为每个命令写代码**：`MemoryCore.call(command, **params)` 是通用调用，内核加参数不用改接口层。
- **错误分类是稳定枚举**（`usage/invalid/config/not_found/conflict/busy/storage/findings/unsupported/failed`），
  调用方只认它，**不解析中文文本**。
- **幂等**：`add` 支持 `client_key`，重试不会写出第二条。
- **服务端强制 UTF-8**：`serve` 的 stdin/stdout 与平台代码页无关；CLI 文本输出则跟随控制台 locale。

完整规则（信封、批量、分页、并发、生命周期、各类调用方接入清单、明确禁止）见
`docs/接口规范.md`；命令级用法见 `docs/操作手册.md`。

## 给调用方（其他项目/模块）

内核不自带安装器，两种接法二选一：
1. 把仓根加进 `PYTHONPATH`，再 `python -X utf8 -m mingmem ...`；
2. `pip install -e .` 后直接用 `mingmem ...`（`[project.scripts]` 已配好）。

调用方负责：显式给 `--ns`（或设 `MINGMEM_NS`）、按需给 `--data-root`、不要假设 `recall` 一定返回 5 条（去冗余会少给）。
0.3.0 起有**可选稠密通道**（内置 24MB int8 模型；`pip install mingmem[dense]` 即可启用，不装则自动降级，行为与 0.2.x 一致）。
0.2.1 起还有**最低相关分门槛**：零词面证据的条目不返回，所以「的」「怎么」这类查询就是 0 条；
要解释「为什么少了」，看 `items[].evidence` 与 `gate`（详见 `docs/接口规范.md` §7 与该命令的 `describe`）。

## 数据模型（schema_version = 5）

- 事件流 events/<ns>.jsonl：唯一真相源，只追加、不改写。
- 投影 index.sqlite：nodes / edges / nodes_fts / meta，签名不符就自动全量重建。
- 双时态：correct 会给被更正节点置 superseded=1、superseded_at、valid_to，并写一条 correction
  事件；被更正节点默认不再参与检索，但原文永久保留，可用 links --id 精确追溯。
- 披露等级：public / ask / private。private 默认不检索、不导出；ask 必须显式 --include-ask，
  且每次展示都会追加一条 consent 审计事件（含 query 与被展示的 id），出现在输出与 events 里。
- 幂等：`add --client-key` 相同 key 不重复写入（投影 `client_key` 列 + 索引）。
- 写入原子性：单条事件一次 O_APPEND 写入 + fsync；命令级持数据根锁（目录原子创建，可重入），
  持锁期间后台心跳刷新锁目录 mtime（长命令不会被误判成残留锁），崩溃残留锁超过 5 秒自动清理。
- doctor 对账：事件流推出的应有节点 ↔ 投影实际节点，逐 id 比对；缺失时退出码 1 并指认
  「缺失事件：<id> ｜ <文件>:<行号>」。
- 事件哈希链：每条事件带 prev_hash（上一条事件原始字节的 sha256；首条为 GENESIS 或历史行锚点）。
  verify 逐文件校验，改/删/插/剥离字段都会在断点处报 file:line；purge 重写文件后自动重串链。
  本地链抓不到「最后一行被改」与「整条链被重算」—— 这两类要把链头锚点写到数据根之外（verify --pin）。

## 删除与回收站（两层）

    python -X utf8 -m mingmem forget --ids <id[,id]> [--reason ...]   # 软删 → 进回收站
    python -X utf8 -m mingmem bin [--json] [--limit N] [--offset N]     # 回收站：只有这条路能看见已删项
    python -X utf8 -m mingmem restore --ids <id[,id]>                  # 恢复
    python -X utf8 -m mingmem purge --ids <id[,id]> --yes              # 真删（物理重写事件流，不可逆）

**软删后没有任何检索旁路**：`recall` / `search` / `export` 一律看不到它，`--scope all`、
`--include-archived`、`--include-ask` 都不行（否则删掉的东西又混进检索就是污染）。
唯一能看见的路径是 `bin`，和按 id 精确访问的 `links --id`（会标注「在回收站」）。

**真删必须先软删**（没进回收站直接 purge 会被拒），并且：
- 物理重写事件文件：删掉该 id 的事件行，清掉别处 `targets`/`refs`/`src` 里的引用；
- 留一条 `purge` 审计事件，但**只留条数与 id 的 sha256 前 16 位**，不留 id 本身 ——
  所以事件流里再也查不到被删对象，同时仍可核对「某条是否曾被真删」；
- 先写临时文件 + `fsync` + `os.replace`，单文件原子替换；失败自动清临时件并提示 rebuild。

## 自检与体检

    python -X utf8 -m mingmem selftest   # 81 项，临时数据根，不碰真实库（未配置数据根也能跑）
    python -X utf8 -m mingmem doctor     # 只读体检；不一致时退出码 1

## 目录

    mingmem/      包，21 个模块：
                    settings  常量/版本          config   数据根配置
                    text      分词/时间/id          decay    六变量与衰减
                    chain     prev_hash 与校验      legacy   v1 旧库迁移
                    spec      接口规范表 + describe   rpc     协议信封/分发/错误分类
                    store     事件流与锁           projection SQLite 投影
                    vector    向量（可选）          retrieve  检索与 MMR
                    briefing  六段简报             core      对账/批次/真删重写
                    commands  命令实现             render    导出
                    cli       参数与分发            api       MemoryCore/serve
                    tests     自检                 __init__/__main__
    evalset/      离线评测集（G40：30 条用例 + 指标 + 门禁）
    pyproject.toml
