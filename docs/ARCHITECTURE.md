# 架构地图（Android AI Agent）

> 目的：**新增一个功能时，先在这里找到它的位置，再动手写。**
> 这份文档只描述「东西该放哪、为什么」，不描述具体实现细节（细节看代码注释）。

## 0 一句话

单 Activity + Jetpack Compose，MVVM 变体：`ui` 只画、`ui/chat/ChatViewModel` 持状态、`agent` 是领域与核心循环、`tools` 是可被模型调用的工具、`core` 是与 Android 打交道的基础设施、`service`/`schedule` 是常驻能力。

```text
com.zmf.agent
├── MainActivity.kt / TerminalActivity.kt   # 入口
├── ui/           表现层（纯 Compose）
│   ├── chat/     ChatScreen（页面与浮层总接线）、ChatViewModel（状态容器）
│   │             Composer（输入框与它的浮窗）、ComposerPanel、TracePage、ImageViewer
│   │             SubAgentPage、ContextPage、StatsPage、ThinkingPage、PermissionTierPage
│   ├── settings/ 设置首页、通用设置、模型、上下文、数据、包管理、归档、日志、定时、提示词、权限
│   ├── shell/    SaltDrawer（侧边栏壳）、SaltFullPane（轨迹全窗面板）、AppDrawer（抽屉内容）
│   │             SaltSwitch（开关）
│   ├── float/    悬浮球与小窗（SiriBall / FloatingChatWindow / ChatBridge）
│   ├── common/   SaltGestures（长按）、TapBlocker（吞点击）
│   └── theme/    AgentTheme、Palette、Motion、ThemeStore、UiScaleStore
├── agent/        领域层（不依赖 UI）
│   ├── loop/     AgentLoop：一轮请求、工具调用、流式回调
│   ├── provider/ DeepSeekProvider、ProviderStore（多提供商）、ApiConfig（Key/模型/端点）
│   ├── context/  ContextPolicy、ContextSummarizer、ContextCompactor（压缩与估算）
│   ├── session/  SessionStore（会话落盘）、TodoStore、PlanStore
│   ├── workspace/ WorkSpaceStore、UploadStore（附件落盘）
│   ├── prompt/   PromptBuilder / PromptStore / DefaultPrompts（系统提示词拼装）
│   ├── subagent/ SubAgent、SubAgentRegistry（子智能体）
│   ├── mcp/      MCP 客户端与服务端
│   ├── memory/   MemoryKernel（记忆内核 CLI）
│   ├── skill/    SkillStore
│   ├── stats/    UsageLedger（全局用量总账）
│   └── permission/ PermissionStore
├── tools/        模型可调用的工具（每个工具一个类，实现 Tool 接口）
│   ├── file/ web/ terminal/ memory/ skill/ task/ agent/ system/
│   ├── ToolRegistry（注册与按权限裁剪）、ToolPolicy（确定性闸门）、Tool（接口）
│   └── spill/    SpillStore（超长输出落盘后只回摘要）
├── core/         与 Android 打交道的底座
│   ├── model/    ChatMessage / ToolCall / PermissionLevel
│   ├── api/      BalanceClient
│   ├── backup/   BackupArchive（导出/导入 zip）
│   ├── capability/ ShizukuBridge、AndroidPermissions
│   ├── log/      FileLog、notify/Notifier、AppPaths、AppForeground
├── service/      前台保活、悬浮球服务
└── schedule/     定时任务（Store / Receiver / Runner）
```

## 1 新增功能放哪（对照表）

| 想做的事 | 放这里 | 还要动哪 |
|---|---|---|
| 给模型加一个新工具 | `tools/<分组>/XxxTool.kt` 实现 `Tool` | 在 `ToolRegistry.defaultTools()` 注册；需要权限就在 `minPermission` 声明 |
| 加一个设置项（开关/输入） | 对应 `ui/settings/*Page.kt` 加一行 | 状态放 `ChatViewModel` 或 `app_prefs`（`GeneralPrefs` 这类 Store） |
| 加一个全屏页面 | `ui/chat/` 或 `ui/settings/` 新建 XxxPage.kt | `ChatScreen` 的 `AppPage` 加枚举值 + 一层 `AnimatedVisibility` + 一条 `BackHandler` + 父层 `visible` 纳入 |
| 换/加模型提供商 | `agent/provider/ProviderStore.catalog()` 加模板 | `ProviderConfig`（OpenAI 兼容端点的才能直接用） |
| 改上下文压缩 | `agent/context/` | 界面在 `ui/settings/ContextHubPage.kt`（入口在设置首页） |
| 改系统提示词 | `agent/prompt/PromptBuilder.kt` | 追加/覆盖走 `PromptStore`，界面上在上下文页 |
| 加一种消息类型（气泡） | `ChatScreen` 的 `AssistantRow`/`UserRow` | 落盘字段加到 `ChatMessage` + `SessionStore.messageToJson/FromJson`（新字段必须有默认值） |
| 常驻后台能力 | `service/` | Manifest 加权限与 service；设置里的开关记得接线 |
| 定时任务 | `schedule/` | 界面在 `ui/settings/SchedulePage.kt` |

## 2 几条硬规矩（写代码时别破）

1. **新字段一律带默认值**：`ChatMessage` / `ChatSession` 的 JSON 是用户数据的唯一载体，读旧文件必须不炸。
2. **备份白名单**：新增落在 `filesDir/appdata/` 的 JSON 要加进 `BackupArchive.DATA_FILES`，否则换机丢数据（`providers.json` 就是这么补的）。
3. **页面层必须在 `SaltDrawer` 的 content 子树里**：和外壳平级的话，横滑拉不出抽屉。
4. **手势分层**：抽屉与轨迹的整屏接管都在 **Main** 阶段且逐帧查 `change.isConsumed`；横向可滚动的区域用 `.swallowHorizontalAtEdge()` 把越界量就地吃掉，否则外层会把「滑到头继续滑」当成拉抽屉。
5. **动效只用 `Motion.spring()/fadeIn()/fadeOut()`**，不要各写一套 tween。
6. **界面与动效由用户点验**：agent 只负责构建、装机、读日志，不做点按/截图验证。
7. **版本号**：每次装新版 `versionCode` +1，`versionName` 同步；keystore 绝不能丢。

## 3 已知待收敛（记账，不急）

- `ChatScreen.kt` 仍是单体大文件（页面接线 + 十来个私有浮层/行组件）；再涨就该拆成 `ChatScreen(Messages|Layers|Overlays).kt`。
- `ui/chat` 与 `ui/settings` 里的页面文件是历史分布（上下文/统计/思考/权限档 在 chat 下但属于设置），有心时归并。
- `ChatViewModel` 同时管会话、工作区、附件、上下文、用量、备份、子智能体，已接近该拆成几个 State Holder 的体量。
