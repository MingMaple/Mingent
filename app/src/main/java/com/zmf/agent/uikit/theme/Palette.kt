package com.zmf.agent.uikit.theme

import androidx.compose.ui.graphics.Color

/**
 * 全局色板。
 *
 * ## 色相预算（全局仅允许这四个来源，超出即视为违规）
 *
 * 1. 中性一支：灰阶，OKLCH h≈260、C ≤ 0.011，承担全部层次、文字、描边、装饰；
 * 2. 强调一支：蓝，只承担「主操作、选中、聚焦、进行中」四类语义，见下；
 * 3. 语义三支：成功绿、警告琥珀、错误红，只出现在状态点、图标与短标签上；
 * 4. 洞察页专用命名空间：`InsightColors` 的八色，仅限上下文洞察页内部。
 *
 * ## 蓝的四个合法落点（其余位置一律中性）
 *
 * | 用途 | 落点 | 取色 |
 * |---|---|---|
 * | 主操作填充 | 发送键、计划页主按钮 | [Accent] + [OnAccent] |
 * | 选中 | 页签文字与下划线、「✓」勾选标记、链接 | [LightAccentFg] / [DarkAccentFg] |
 * | 聚焦 | 输入框焦点边框、光标、聚焦环 | [LightAccentFg] / [DarkAccentFg] |
 * | 进行中 | 运行中状态点、进度条、用量条 | [LightAccentFg] / [DarkAccentFg] |
 *
 * 「列表行被选中」不属此列：行仅依靠 [LightRowSelected] / [DarkRowSelected] 的深浅与字重，
 * 不改变文字颜色，也不添加竖条。两类规则各管一类元素，不得互相挪用：
 * 行表示当前停留位置，页签表示当前查看的栏目。
 *
 * ## 深浅两套的差别（不是同一套值的两倍关系）
 *
 * 浅色以压深表达层次：底 `#F1F4F7` → 助手气泡 `#E6EAF1` → 选中 `#EBEDF1`，用户气泡与白卡最亮。
 * 深色以提亮表达层次：底 `#0F1013` → 助手气泡 `#1D2024` → 卡片 `#23262A` → 用户气泡 `#2F3237` → 选中 `#353638`。
 * 蓝色同理：浅色使用 [Accent]（对白卡 4.0:1），深色文字使用 [DarkAccentFg] 提亮至 6.8:1；
 * 否则同一色蓝在暗色背景上显得沉闷，在亮色背景上过于刺眼。
 */
object Palette {

    // ── 浅色 ──

    /** 页面底。 */
    val LightBackground = Color(0xFFF1F4F7)

    /** 下层：助手气泡，同时是 Salt 的 subBackground。 */
    val LightSurface = Color(0xFFE6EAF1)

    /** 浮层：卡片、伴随面板、用户气泡、输入条，同时是 Salt 的 popup。 */
    val LightSurfaceHigh = Color(0xFFFFFFFF)

    val LightText = Color(0xFF181A1C)

    /** 次要文字、时间戳、注释。对底色 5.74:1。 */
    val LightSubText = Color(0xFF5C6065)

    /** 描边与分割线：由次要文字降透明度派生，换底色时自动跟随。 */
    val LightStroke = Color(0x265C6065)

    // ── 深色 ──

    /** 页面底。不采用纯黑：纯黑上叠加黑色仍为纯黑，按压反馈在底色上无法体现。 */
    val DarkBackground = Color(0xFF0F1013)

    val DarkSurface = Color(0xFF1D2024)

    val DarkSurfaceHigh = Color(0xFF23262A)

    val DarkText = Color(0xFFEBEDEF)

    val DarkSubText = Color(0xFF9CA0A6)

    val DarkStroke = Color(0x1F9CA0A6)

    // ── 强调（蓝）──

    /**
     * 强调色的**线条档**：页签下划线、图表、选区边、拖动条。
     *
     * 该档为高饱和色，仅用于线条、不用于填充面：线条宽度仅 1–4dp，需足够饱和方可辨识。
     * 填充面另有 [AccentFill] / [AccentFillDark] 两支，饱和度显著更低，理由见该处说明。
     */
    val Accent = Color(0xFF4176E6)

    /**
     * 强调色的**填充档**（按钮、开关轨道、进度条）：Salt Player 的实测值。
     *
     * 与 [Accent] 分开是因为线条与填充面对饱和度的容忍度完全不同：
     * 同一蓝色用于 2dp 线条时清晰，铺展为 40dp 圆形按钮时则形成大块高饱和色面。
     *
     * 实测对比（两者差异不在色相，而在饱和度）：
     * | | 色相 | 饱和度 | 明度 |
     * |---|---|---|---|
     * | Salt 浅 `#4D5C93` | 227° | **31%** | 44% |
     * | 参考实现 `#4176E6` | 221° | **77%** | 58% |
     * | Salt 深 `#B6C4FF` | 228° | 100% | 86% |
     * | 参考实现 `#7AAAFF` | 218° | 100% | 74% |
     *
     * 浅色差异在饱和度（31% 对 77%），深色差异在明度（86% 对 74%）。
     * Salt 的浅色档为灰蓝而非鲜蓝，深色档则提升至接近白色，两者均不刺眼。
     *
     * 对比度：白压 `#4D5C93` 6.43:1，墨压 `#B6C4FF` 11.17:1，都远超正文标准。
     */
    val AccentFill = Color(0xFF4D5C93)
    val AccentFillDark = Color(0xFFB6C4FF)

    /**
     * 浅色下的强调文字、页签文字与下划线、光标、聚焦边框、链接。
     *
     * 与 [Accent] 同值：DSH 的 `state-business-primary` 在浅色就是 deepseek-500 这一支，
     * 文字与下划线使用同一颜色，不做线深字浅的区分。
     * 代价是对底色 3.84:1，未达到正文要求的 4.5:1，因此仅用于 13–15sp 的短强调文字与链接，
     * 不用于长段落；需要更高对比度的场景另行处理，不在本支中自行加深。
     */
    val LightAccentFg = Color(0xFF4176E6)

    /** 深色下的强调文字、页签文字与下划线、光标、聚焦边框（DSH 的 deepseek-400）。对底色 6.8:1。 */
    val DarkAccentFg = Color(0xFF7AAAFF)

    /** 压在 [Accent] 上的图标与文字。 */
    val OnAccent = Color(0xFFFFFFFF)

    // ── 语义三支 ──

    /**
     * 就绪、已完成、可用。取自 DSH 的 `state-success-primary` = `static-green-500`。
     *
     * 深浅同值：该支仅用于**圆点与小图标**，不用于文字。`#22C55E` 对白底仅 2.0:1，
     * 作为文字无法辨识；DSH 亦仅用于状态点，文字统一使用 label 灰。
     * 需要表示「通过 / 失败」等判定时，使用正文色与错误红，不为绿色配置文字。
     */
    val LightSuccess = Color(0xFF22C55E)
    val DarkSuccess = Color(0xFF22C55E)

    /** 待确认、降级可用。 */
    val LightWarning = Color(0xFF8A5A0B)
    val DarkWarning = Color(0xFFD9A441)

    /** 失败、不可用、危险操作。 */
    val LightError = Color(0xFFC0281F)
    val DarkError = Color(0xFFFF7A72)

    // ── 中性 ──

    /**
     * 图标与占位符的统一色。
     *
     * 单值而非深浅两支：`#83868B` 对浅底 3.4:1、对深底 5.0:1，两种模式都过非文字对比的 3:1，
     * 拆两支只会让 30 余处调用点多一层判断。
     */
    val Neutral = Color(0xFF83868B)


    // ── 聊天气泡 ──
    //
    // 气泡是「谁在说话」的角色标记，并非浮层。若将用户气泡映射至 Light/DarkSurfaceHigh，
    // 深色下用户气泡 #23262A（L* 15.01）与助手气泡 #1D2024（L* 12.10）的 ΔL* 仅 2.91，
    // 两块大面并置时无法区分角色。因此气泡各自持有独立取值：
    // 用户气泡为画布上最亮的面，助手气泡保持下层，思考块再低一档。
    //
    // 浅色用户/助手 ΔL* 7.42（100.00 → 92.58），深色取 8.58（20.68 → 12.10）：
    // 深色 L* 在暗端被压缩，相同视觉差异需要更大的 L* 差值，与 §3.2 深色层差偏大同源。
    // #2F3237 的 OKLCH 彩度 0.0099、色相 260.7°，守住中性预算 C ≤ 0.011；
    // 正文 #EBEDEF 压在它上面 10.96:1，压在助手气泡上 13.93:1。

    /** 用户气泡：对话里用户发出的那条，画布上最亮的一块面。 */
    val BubbleUserLight = Color(0xFFFFFFFF)
    val BubbleUserDark = Color(0xFF2F3237)

    /** 助手气泡：模型的回答，深色下只比画布亮一档，保持安静。 */
    val BubbleAiLight = Color(0xFFE6EAF1)
    val BubbleAiDark = Color(0xFF1D2024)

    /** 思考块：嵌在助手气泡内，比助手气泡再低一档。 */
    val BubbleThinkLight = Color(0xFFD8DCE3)
    val BubbleThinkDark = Color(0xFF18191D)

    /**
     * 列表行选中态的底色。只给深浅，不染色。
     *
     * 取值与 DSH 的 `interactive-bg-hover` 同源：浅色是 6% 的蓝墨叠在白卡上（合成 `#F2F3F4`），
     * 深色是 8% 白叠在卡片上（合成 `#35373B`）。DSH 的会话行「选中」与「悬停」用的就是同一档，
     * 不额外加重，过重则会呈现为按下后未复原的状态。
     *
     * 与页签的区别见文件头：行只给底与字重，页签给字与线。
     */
    val LightRowSelected = Color(0xFFF2F3F4)
    val DarkRowSelected = Color(0xFF35373B)

    // ── 深色填充上的前景 ──

    /**
     * 压在 [AccentFillDark] 上的图标。
     *
     * 使用墨色而非白色：`#B6C4FF` 亮度已较高，白色图标对比度仅 1.4:1，几乎不可见；
     * 墨色对比度为 11.17:1。
     */
    val OnAccentDark = Color(0xFF0F1013)

    // ── 轨迹页四支 ──
    //
    // 色相参照 DSH 的轨迹时间轴（用户=蓝、助手=蓝红混合、工具=琥珀、上下文=去饱和绿），
    // 明度不作照搬：DSH 各支在浅色下对比度仅 2.5–4.0:1，作为 11sp 标签文字无法辨识。
    // 此处按「文字压在自身 14% 淡底上仍 ≥4.5:1」逐支重新求解，四支均达标。

    val TraceUserLight = Color(0xFF3A65B8)
    val TraceUserDark = Color(0xFF8BAEEE)
    val TraceAssistantLight = Color(0xFF7955B0)
    val TraceAssistantDark = Color(0xFFB7A0E4)
    val TraceToolLight = Color(0xFFA35400)
    val TraceToolDark = Color(0xFFDC9F6A)
    val TraceContextLight = Color(0xFF00793D)
    val TraceContextDark = Color(0xFF72BB8C)

    // ── 上下文用量（DSH ContextMeter）──
    //
    // 取值来自 DSH 的 ContextMeter 组件与它的语义令牌，逐项对应如下：
    // | DSH 令牌 | 浅色 | 深色 |
    // |---|---|---|
    // | `alias-label-primary`（数值） | `neutral-bluish-1000` = `#0F1115` | `neutral-bluish-50` = `#F9FAFB` |
    // | `alias-label-secondary`（明细名） | `neutral-bluish-700` = `#61666B` | `neutral-bluish-300` = `#CFD3D6` |
    // | `alias-label-tertiary`（标题、圆环进度） | `neutral-bluish-600` = `#81858C` | `neutral-bluish-400` = `#ADB2B8` |
    // | `alias-border-l3`（圆环底槽） | `#0000001F` | `#FFFFFF29` |
    // | `alias-interactive-bg-hover`（进度条底槽） | `#2631480F` | `#FFFFFF14` |
    //
    // 三段构成色是组件内写死的固有色，深浅同值：系统提示词 `neutral-bluish-400`、
    // 工具定义 `#A78BFA`、对话消息 `static-blue-450`。此处不作语义化改写：
    // 三色并列时的辨识度依赖于彼此色相不同，换成同族色则三段无法区分。

    val LightMeterPrimary = Color(0xFF0F1115)
    val LightMeterSecondary = Color(0xFF61666B)
    val LightMeterTertiary = Color(0xFF81858C)
    val DarkMeterPrimary = Color(0xFFF9FAFB)
    val DarkMeterSecondary = Color(0xFFCFD3D6)
    val DarkMeterTertiary = Color(0xFFADB2B8)

    /** 进度条底槽：浅色为 6% 蓝墨，深色为 8% 白。 */
    val LightMeterTrack = Color(0x0F263148)
    val DarkMeterTrack = Color(0x14FFFFFF)

    /** 圆环底槽：比进度条底槽更实，环只有 2dp，过淡则读不出「还有一圈」。 */
    val LightMeterRingTrack = Color(0x1F000000)
    val DarkMeterRingTrack = Color(0x29FFFFFF)

    /** 三段构成色：系统提示词。 */
    val MeterSystem = Color(0xFFADB2B8)

    /** 三段构成色：工具定义。 */
    val MeterTools = Color(0xFFA78BFA)

    /** 三段构成色：对话消息。 */
    val MeterMessages = Color(0xFF4D93F8)

    // ── 开关关闭态轨道 ──

    /**
     * 开关关着的轨道。
     *
     * 取值参照 Salt Player 的实拍（浅 `#DDDBE6` / 深 `#30333C`），仅将色相调整为中性：
     * 需可识别为轨道但不得强于开启态，因此浅色比卡片深一档、深色比卡片亮一档。
     */
    val LightSwitchOff = Color(0xFFDCDEE4)
    val DarkSwitchOff = Color(0xFF33363D)
}
