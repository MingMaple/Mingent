# 架构地图（Android AI Agent）

> 目的：**新增功能时，先在此处确认其归属位置，再着手编写代码。**
> 本文档仅说明「东西该放哪、为什么」，不描述具体实现细节（细节参见代码注释）。

## 0 一句话

单 Activity 与 Jetpack Compose，MVVM 变体：`ui` 仅负责绘制，`ui/chat/ChatViewModel` 持有状态，`agent` 承担领域逻辑与核心循环，`tools` 提供可被模型调用的工具，`core` 是负责与 Android 交互的基础设施，`service`/`schedule` 提供常驻能力。

记忆内核是唯一「不在这个 Kotlin 包结构里」的部分：`app/src/main/assets/mingmem/` 是一份仅依赖标准库的 Python 实现，首次启动时部署到应用私有目录，App 仅通过命令行与 JSON 与之交互。因此该内核可在终端中单独复现与排障，也不受 Compose 与 Android 生命周期的影响——修改记忆相关功能时，应先确认所改的是内核侧还是桥接侧。

```text
com.zmf.agent
├── MainActivity.kt / TerminalActivity.kt   # 入口
├── ui/           表现层（纯 Compose）
│   ├── chat/     ChatScreen（页面与浮层总接线）、ChatViewModel（状态容器）
│   │             Composer（输入框与它的浮窗）、ComposerPanel、TracePage、ImageViewer
│   │             SubAgentPage、ContextPage、StatsPage、ThinkingPage、PermissionTierPage
│   ├── settings/ 设置首页、通用设置、模型、上下文、数据、包管理、归档、日志、定时、提示词、权限、回收站
│   ├── shell/    SaltDrawer（侧边栏壳）、SaltFullPane（轨迹全窗面板）、AppDrawer（抽屉内容）
│   │             SaltSwitch（开关）
│   ├── float/    悬浮球与小窗（SiriBall / FloatingChatWindow / ChatBridge）
│   ├── common/   SaltGestures（长按）、TapBlocker（吞点击）、SaltField（统一输入框）
│   └── theme/    AgentTheme、Palette、Motion、ThemeStore、UiScaleStore
├── agent/        领域层（不依赖 UI）
│   ├── loop/     AgentLoop：一轮请求、工具调用、流式回调
│   ├── provider/ DeepSeekProvider、ProviderStore（多提供商）、ApiConfig（Key/模型/端点）
│   ├── context/  ContextPolicy、ContextSummarizer、ContextCompactor（压缩与估算）
│   │             ToolResultPruner（超长工具结果裁剪）
│   ├── session/  SessionStore（会话落盘）、TodoStore、PlanStore
│   ├── workspace/ WorkSpaceStore、UploadStore（附件落盘）
│   ├── prompt/   PromptBuilder / PromptStore / DefaultPrompts（系统提示词拼装）
│   ├── subagent/ SubAgent、SubAgentRegistry（子智能体）
│   ├── mcp/      MCP 客户端与服务端
│   ├── memory/   MemoryKernel / MemoryAdmin（记忆内核 CLI 桥接）
│   ├── skill/    SkillStore
│   ├── stats/    UsageLedger（全局用量总账）
│   └── permission/ PermissionStore
├── tools/        模型可调用的工具（每个工具一个类，实现 Tool 接口）
│   ├── file/ web/ terminal/ memory/ skill/ task/ agent/ system/
│   ├── ToolRegistry（注册与按权限裁剪）、ToolPolicy（确定性闸门）、Tool（接口）
│   ├── HighRiskGate（高危删除闸门）、SandboxRegression（启动期判定自检）
│   └── spill/    SpillStore（超长输出落盘后只回摘要）
├── core/         与 Android 打交道的底座
│   ├── model/    ChatMessage / ToolCall / PermissionLevel
│   ├── api/      BalanceClient
│   ├── backup/   BackupArchive（导出/导入 zip）
│   ├── capability/ ShizukuBridge、AndroidPermissions
│   ├── log/      FileLog、AuditLog、notify/Notifier、AppPaths、AppForeground
├── service/      前台保活、悬浮球服务
└── schedule/     定时任务（Store / Receiver / Runner）
```

## 1 新增功能的归属位置（对照表）

| 需求 | 实现位置 | 还需改动 |
|---|---|---|
| 为模型新增一个工具 | 在 `tools/<分组>/XxxTool.kt` 中实现 `Tool` | 在 `ToolRegistry.defaultTools()` 中注册；如需权限，则在 `minPermission` 中声明 |
| 新增一个设置项（开关/输入） | 在对应的 `ui/settings/*Page.kt` 中增加一行 | 状态置于 `ChatViewModel` 或 `app_prefs`（如 `GeneralPrefs` 这类 Store） |
| 新增一个全屏页面 | 在 `ui/chat/` 或 `ui/settings/` 中新建 XxxPage.kt | 在 `ChatScreen` 的 `AppPage` 中新增枚举值，增加一层 `AnimatedVisibility` 与一条 `BackHandler`，并将父层 `visible` 纳入 |
| 更换或新增模型提供商 | 在 `agent/provider/ProviderStore.catalog()` 中新增模板 | `ProviderConfig`（仅 OpenAI 兼容端点可直接使用） |
| 修改上下文压缩 | `agent/context/` | 界面位于 `ui/settings/ContextHubPage.kt`（入口在设置首页） |
| 修改系统提示词 | `agent/prompt/PromptBuilder.kt` | 追加与覆盖通过 `PromptStore` 实现，界面位于上下文页 |
| 新增一种消息类型（气泡） | `ChatScreen` 的 `AssistantRow`/`UserRow` | 落盘字段需加入 `ChatMessage` 与 `SessionStore.messageToJson/FromJson`（新字段必须有默认值） |
| 常驻后台能力 | `service/` | 在 Manifest 中增加权限与 service；设置中的开关需同步接线 |
| 定时任务 | `schedule/` | 界面位于 `ui/settings/SchedulePage.kt` |
| 修改长消息折叠 / 气泡渲染 | `ui/chat/MarkdownText.kt`（折叠 Layout：`foldAt` / `foldedHeight` / `expanded` / `foldToggle` 由调用方传入） | 阈值与轮次位于 `ui/chat/ChatScreen.kt`（`buildChatItems` 计算 `roundsFromEnd`、`FoldToggle`）；小窗复用同一套逻辑，比例位于 `FloatingChatWindow.kt` |
| 修改记忆内核 / 记忆页面 | `app/src/main/assets/mingmem/`（Python 内核与文档，资产不在 Java 包内） | Kotlin 桥接位于 `agent/memory/{MemoryKernel,MemoryAdmin}.kt`，工具位于 `tools/memory/MemTool.kt`，界面位于 `ui/settings/MemoryPage.kt` |

## 2 几条硬性规则（编码时不得违反）

1. **新字段一律携带默认值**：`ChatMessage` / `ChatSession` 的 JSON 是用户数据的唯一载体，读取旧文件时不得崩溃。
2. **备份白名单**：新增的、位于 `filesDir/appdata/` 的 JSON 需加入 `BackupArchive.DATA_FILES`，否则更换设备时会丢失数据（`providers.json` 即按此方式补充）。
3. **页面层必须位于 `SaltDrawer` 的 content 子树中**：若与外壳平级，横向滑动无法拉出抽屉。
4. **手势分层**：抽屉与轨迹的整屏接管均位于 **Main** 阶段，并逐帧检查 `change.isConsumed`；横向可滚动的区域**必须自行吸收越界量**，不能依赖外层判定，且吸收动作同样在 **Main** 阶段完成 —— 拦截写得更早会让子节点判定手势被抢占，整轮手势随之取消（表现为滑到边界后手指不抬起便无法反向滑动）。
5. **动效统一使用 `Motion.spring()/fadeIn()/fadeOut()`**，不得各自实现一套 tween。
6. **高度可能到十几屏的容器，不要使用带 `shape` 的 `Surface`**：其圆角会建立一层 `graphicsLayer + clip`，容器很高时图层内的子节点无法接收点击事件（长消息展开后收起按钮无法点击即由此产生）。应改用 `Box + background(color, shape)` —— 观感一致，但不建立图层。

## 3 已知待收敛事项（记录在案，暂不紧急）

- `ChatScreen.kt` 仍为单体大文件（页面接线与十几个私有浮层、行组件）；若继续膨胀，应拆分为 `ChatScreen(Messages|Layers|Overlays).kt`。
- `ui/chat` 与 `ui/settings` 中的页面文件呈历史分布（上下文/统计/思考/权限档 位于 chat 下，但归属设置），后续可择机归并。
- `ChatViewModel` 同时负责会话、工作区、附件、上下文、用量、备份、子智能体，其体量已接近应拆分为若干 State Holder 的程度。
