# Mingent

**Mingent** 是一个运行在 Android 上的本地 AI Agent 客户端。填写模型 API Key 后，它可调用工具完成文件读写、命令执行、联网检索等操作；会话记录、密钥、记忆与附件均保存在本机应用私有目录内。

## 能力

- **模型接入**：支持 DeepSeek 与任意 OpenAI 兼容接口，可同时配置多个提供商，同一厂商可并存多份独立配置。
- **工具调用**：文件读写、内置终端命令、联网搜索、MCP 服务与 Skill，共 15 个内置工具。
- **权限控制**：只读 / 工作区 / 完全三档，路径判定在工具内部完成；删除操作另设一层安全链路——工作区外与通配删除要求**一次一码**授权，默认先进入回收站，关键目录永久拒绝，全过程写入审计日志。
- **任务编排**：长会话自动摘要与上下文压缩；任务可拆成待办或计划，计划须由用户在专门页面授权后执行；支持定时任务（按计划时刻锚定，周期不漂移）。
- **长期记忆**：独立内核 `mingmem`，跨会话保存用户偏好与项目约定，按工作区隔离，可随备份导出。详见下节。
- **长消息阅读**：用户发送的长消息按轮次折叠——最近三轮整段平铺，更早内容按整块截断并以渐隐压边，右下角提供箭头用于展开、收起；气泡内代码块整段展开，不设块内滚动，横向滑动至边界时即就地截断。悬浮小窗与主页共用相同的渲染逻辑。
- **内置终端**：以 PRoot 运行 Ubuntu 24.04（arm64），与 Agent 共用同一环境。
- **数据迁移**：会话、工作区、终端环境与设置可整体导出与导入；记忆数据需在导出时选中「包含记忆数据」，一并写入备份包（内核程序不纳入打包），导入时整体替换。
- **悬浮窗**：可在任意界面唤起对话。

## 长期记忆：mingmem

记忆不再由应用内的数百行逻辑承担，改由随安装包内置、可独立运行的 Python 内核承担。

| 项 | 内容 |
| --- | --- |
| 形态 | 核心与默认检索仅使用 Python 标准库（≥3.10），无第三方依赖；共 22 个子命令，均支持 `--json` |
| 位置 | 随 APK 打包于 `assets/mingmem/`，首次使用时部署到应用私有目录 `files/mingmem/` |
| 运行环境 | 应用自带的 Ubuntu（PRoot），可直接在终端中调用 |
| 数据 | `files/mingmem/_Data`，按工作区命名空间隔离，不进入用户可见目录；可在「数据 → 导出用户数据」中勾选打包 |
| 检索 | 默认 BM25 加图扩散；可选稠密通道（`bge-small-zh-v1.5`，int8，512 维，本地 ONNX 推理），需要自行安装 `onnxruntime` 与 `numpy` |
| 维护 | `cycle` 周期扫描与巩固、`forget`/`bin`/`restore`/`purge` 回收站、`verify` 事件链校验、`export` 导出视图，均可脚本化 |

设计上有三条取舍：

1. **内核与界面解耦**。界面只负责展示与触发，所有读写均经命令行与 JSON 回传，因此可直接在终端中复现、验证与排障，不依赖应用内部状态。
2. **降级状态告知**。稠密通道缺少 `onnxruntime` 或 `numpy` 时退回 BM25 加图扩散，并在体检结果中写明原因。
3. **数据保留在私有目录**。记忆不写入用户可见存储，但可以随备份导出（需勾选），也可用 `export` 导出可读视图。

稠密通道内置的模型权重来自 `BAAI/bge-small-zh-v1.5`（MIT），按上游许可原样内置、未作修改，详见[模型目录内的 NOTICE](app/src/main/assets/mingmem/mingmem/dense/model/NOTICE.md) 与 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

可在终端中直接使用：

```bash
export PYTHONPATH=/data/user/0/io.github.mingmaple.mingent/files/mingmem
export MINGMEM_DATA=/data/user/0/io.github.mingmaple.mingent/files/mingmem/_Data
python3 -X utf8 -m mingmem status --json
python3 -X utf8 -m mingmem recall --query 检索词 --json
```

## 构建

环境要求：JDK 21、Android SDK 37（compileSdk 37，minSdk 26）。

```bash
# Linux / macOS
./gradlew :app:assembleRelease

# Windows
gradlew.bat :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/`。发布版 APK 见 [Releases](https://github.com/MingMaple/Mingent/releases)。

`applicationId` 为 `io.github.mingmaple.mingent`；开发期可另构建 `.dev` 后缀的共存变体（`:app:assembleCoexist`），与正式包同时安装。

> `targetSdk` 固定为 28，是内置终端（PRoot）的硬性依赖，不作为兼容性疏漏处理。

### 签名

签名配置不纳入仓库。自行构建时请补全 `signing/` 下的参数（见 [signing/README.md](signing/README.md)），或改用自有密钥。未提供参数时构建会回落到调试签名，产物不可用于发布。

## 隐私

- 会话记录、模型 API Key、记忆数据、日志与附件仅存于本机应用私有目录，不上传至任何服务器。
- 模型能力由用户自备的 API Key 提供，请求直接发往用户所配置的服务商；其数据处理方式请自行评估。
- 稠密检索的向量推理在本机完成，不产生网络请求。
- 备份导出产物仅写入用户指定的本地目录，请自行妥善保管。

## 权限

- 通知：用于前台任务与悬浮窗状态提示。
- 存储访问（SAF）：仅在用户主动选择备份目录或工作区目录时使用。
- 系统接口：需调用系统级接口的功能通过 Shizuku 实现，由用户自行授权，无需 Root。

## 已知限制

- **内置终端与记忆内核仅支持 arm64 设备**。APK 本身是通用包（含 arm64-v8a / armeabi-v7a / x86 / x86_64），但 PRoot 实现与 Ubuntu 根文件系统均为 arm64 预编译，其他架构可以安装，但仅有界面与网络类功能可用。
- 定时任务的实际触发时间比计划晚 19–33 秒（`targetSdk 28` 下系统将精确闹钟降级为窗口闹钟），周期本身不漂移。

## 致谢

本项目的实现受益于以下项目与作者，谨此致谢。

**Operit AI**（https://github.com/AAswordman/Operit）
Mingent 的内置终端直接采用了 Operit AI 的 PRoot Ubuntu 环境实现（`terminal/` 模块及其二进制资源），该部分依 GNU LGPL-3.0 授权使用；对应源码随本仓库公开，详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

**SaltUI**（https://github.com/Moriafly/SaltUI）
界面组件库，以其 Apache-2.0 授权的 Compose 组件引入。Mingent 的配色与交互节奏沿用 Salt Player 的风格取向，未使用 Salt Player 主程序的任何代码。

**Shizuku**（https://github.com/RikkaApps/Shizuku）
提供免 Root 调用系统接口的方案。

**BAAI / FlagEmbedding**（https://github.com/FlagOpen/FlagEmbedding）
稠密检索内置的 `bge-small-zh-v1.5` 模型权重以其 MIT 授权原样内置，未作修改。

**DeepSeek Harness**（https://github.com/deepseek-ai/deepseek-harness）与 **dsh-context**（https://github.com/bowenliang123/dsh-context）
两者在 Agent 循环、上下文滚动压缩与工具分层等方面提供了设计与实现思路的参考。本仓库未包含两者的代码。

记忆内核 `mingmem`、Agent 内核与全部界面代码为 Mingent 原创部分，随本仓库以 MIT 授权。

各第三方组件的版本、许可证与适用范围见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 许可证

[MIT](LICENSE)
