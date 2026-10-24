# 第三方组件与许可声明

Mingent 以 MIT 许可发布（见 [LICENSE](LICENSE)）。本文件列出的内容不属于 Mingent 原创部分，
其版权与许可归各自权利人所有；再分发本仓库或其构建产物时，须同时保留本文件及相关许可全文。

本文件与源码同步维护。若实际代码与本文件描述不一致，以代码及其目录下的许可文件为准，并请提
Issue 指出。

---

## 一、Operit AI 终端环境（GNU LGPL-3.0）

Mingent 的内置终端（PRoot + Ubuntu 24 环境）来自 Operit AI 项目，随本仓库以 GNU 宽通用公共
许可证第 3 版（LGPL-3.0）提供。

| 项 | 内容 |
|---|---|
| 上游项目 | Operit AI（https://github.com/AAswordman/Operit） |
| 版权 | Copyright (C) 2023-2024 Operit AI 项目团队 |
| 许可证 | GNU Lesser General Public License v3.0 |
| 许可证全文 | [terminal/LICENSE](terminal/LICENSE) |
| 本仓库中的对应位置 | `terminal/` 模块（46 个 Kotlin 源文件，包名 `com.ai.assistance.operit.terminal` 原样保留） |

### 1.1 包含范围

除 Kotlin 源码外，以下二进制与资源同样来自该上游项目，一并适用 LGPL-3.0：

- `terminal/src/main/jniLibs/arm64-v8a/liboperit_proot.so`（PRoot 运行时）
- `terminal/src/main/jniLibs/arm64-v8a/liboperit_loader.so`（加载器）
- `terminal/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz`（Ubuntu 24.04 根文件系统）
- `terminal/src/main/assets/setup_fake_sysdata.sh`

以下二进制同样随该上游项目分发，其许可遵循各自上游项目：

- `libbusybox.so`：BusyBox，GNU GPL v2
- `libbash.so`：GNU Bash，GNU GPL v3

（这两个二进制为上游项目的构建产物，本仓库未作修改，仅保留原文件名以便安装到 Ubuntu 环境；
其具体版本与许可声明以上游项目发布说明为准。）

### 1.2 修改说明

本仓库中 `terminal/` 模块的源代码即 Mingent 实际编译使用的版本。Mingent 对其中部分代码做过
本地调整（例如与宿主应用的数据目录、启动参数对接），但未保留与上游的逐文件差异记录。因此：
**本仓库 `terminal/` 目录下的代码即为修改后的完整对应源码，同样以 LGPL-3.0 提供。**
如需对照，可下载上方上游项目相应版本进行比对。

### 1.3 合规承诺

- 上述 LGPL 部分的完整源码随本仓库公开，获取方式即本仓库的 `terminal/` 目录；
- 任何再分发（包括发布 APK）须一并提供该源码或其获取途径，并保留本文件与 `terminal/LICENSE`；
- 使用者有权为调试目的修改并重新链接该库；LGPL-3.0 全文见 `terminal/LICENSE`；
- 该部分代码与 Mingent 自有代码通过 Android 模块边界（`:terminal` 库模块）分隔，
  自有代码仍按 MIT 授权。

---

## 二、SaltUI（Apache-2.0）

界面组件库，经 Maven 依赖引入，未复制其源码。

| 项 | 内容 |
|---|---|
| 组件 | `io.github.moriafly:salt-ui:3.0.0-beta02` |
| 上游项目 | SaltUI（https://github.com/Moriafly/SaltUI） |
| 作者 | Moriafly |
| 许可证 | Apache License 2.0（全文：https://www.apache.org/licenses/LICENSE-2.0 ） |

---

## 三、Shizuku（Apache-2.0）

用于在无 Root 环境下通过 ADB 权限调用系统接口，经 Maven 依赖引入。

| 项 | 内容 |
|---|---|
| 组件 | `dev.rikka.shizuku:api`、`dev.rikka.shizuku:provider`（13.1.5） |
| 上游项目 | Shizuku（https://github.com/RikkaApps/Shizuku） |
| 作者 | RikkaApps |
| 许可证 | Apache License 2.0（全文：https://www.apache.org/licenses/LICENSE-2.0 ） |

---

## 四、AndroidX / Jetpack Compose / OkHttp / Kotlin Coroutines（Apache-2.0）

以上为通用基础库，均经 Maven 依赖引入（版本见 `gradle/libs.versions.toml`），
遵循 Apache License 2.0。其许可证与 NOTICE 随各自构件分发。

---

## 五、架构与思路参考（不含代码）

以下项目未向本仓库贡献代码，仅在架构设计与实现思路层面提供参考，特此致谢：

- **DeepSeek Harness（dsh）**（https://github.com/deepseek-ai/deepseek-harness ，MIT）：Agent 循环、
  上下文滚动压缩与工具分层的设计参考，本仓库未包含其代码；
- **dsh-context**（https://github.com/bowenliang123/dsh-context ，Apache-2.0）：上下文洞察与管理的
  功能逻辑参考，本仓库未包含其代码。

如后续引入其中任何代码，将在此登记并补充相应许可。

---

## 附：如何报告许可问题

若您认为本仓库包含应标注而未标注的第三方内容，请提交 Issue 或联系仓库所有者，
我们会在核实后补充声明或移除相关内容。
