# Mingent

**Mingent** 是一个运行在 Android 上的本地 AI Agent 客户端。填写模型 API Key 后，它可调用工具完成文件读写、命令执行、联网检索等操作；会话记录、密钥与附件均保存在本机应用私有目录内。

## 能力

- **模型接入**：支持 DeepSeek 与任意 OpenAI 兼容接口，可同时配置多个提供商。
- **工具调用**：文件读写、内置终端命令、联网搜索、MCP 服务与 Skill。
- **任务编排**：长会话自动摘要与上下文压缩；任务可分为待办与计划，计划须由用户在专门页面逐次授权后方可执行。
- **长期记忆**：跨会话保存用户偏好与项目约定，按工作区隔离。
- **内置终端**：以 PRoot 运行 Ubuntu 24.04，与 Agent 共用同一环境。
- **悬浮窗**：在任意界面唤起对话。

## 构建

环境要求：JDK 21、Android SDK 37（compileSdk 37，minSdk 26）。

```bash
# Linux / macOS
./gradlew :app:assembleRelease

# Windows
gradlew.bat :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/`。发布版 APK 见 [Releases](https://github.com/MingMaple/Mingent/releases)。

`applicationId` 为 `io.github.mingmaple.mingent`。

### 签名

签名配置不纳入仓库。自行构建时请补全 `signing/` 下的参数（见 [signing/README.md](signing/README.md)），或改用自有密钥。

## 隐私

- 会话记录、模型 API Key、日志与附件仅存于本机应用私有目录，不上传至任何服务器。
- 模型能力由用户自备的 API Key 提供，请求直接发往用户所配置的服务商；其数据处理方式请自行评估。
- 备份导出产物仅写入用户指定的本地目录，请自行妥善保管。

## 权限

- 通知：用于前台任务与悬浮窗状态提示。
- 存储访问（SAF）：仅在用户主动选择备份或工作区目录时使用。
- 系统接口：需调用系统级接口的功能通过 Shizuku 实现，由用户自行授权，无需 Root。

## 致谢

本项目的实现受益于以下项目与作者，谨此致谢。

**Operit AI**（https://github.com/AAswordman/Operit）
Mingent 的内置终端直接采用了 Operit AI 的 PRoot Ubuntu 环境实现（`terminal/` 模块及其二进制资源），该部分依 GNU LGPL-3.0 授权使用；对应源码随本仓库公开，详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

**SaltUI**（https://github.com/Moriafly/SaltUI）
界面组件库，以其 Apache-2.0 授权的 Compose 组件引入。Mingent 的配色与交互节奏沿用 Salt Player 的风格取向，未使用 Salt Player 主程序的任何代码。

**Shizuku**（https://github.com/RikkaApps/Shizuku）
提供免 Root 调用系统接口的方案。

**DeepSeek Harness**（https://github.com/deepseek-ai/deepseek-harness）与 **dsh-context**（https://github.com/bowenliang123/dsh-context）
两者在 Agent 循环、上下文滚动压缩与工具分层等方面提供了设计与实现思路的参考。本仓库未包含两者的代码。

各第三方组件的版本、许可证与适用范围见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 许可证

[MIT](LICENSE)
