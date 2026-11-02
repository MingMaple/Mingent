package com.zmf.agent.uikit.theme

import androidx.compose.ui.graphics.Color

/**
 * 全局色板。
 *
 * ## 色相预算（全 App 只有这四个来源，超出即违规）
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
 * 「列表行被选中」不在此列：行只靠 [LightRowSelected] / [DarkRowSelected] 的深浅 + 字重，
 * 不染字也不加竖条。两条规则各管一类元素，不能互相挪用：
 * 行是「我停在哪」，页签是「我在看哪一栏」。
 *
 * ## 深浅两套的差别（不是同一套值的两倍关系）
 *
 * 浅色靠「压深」表达层次：底 `#F1F4F7` → 气泡 `#E6EAF1` → 选中 `#EBEDF1`，白卡最亮。
 * 深色靠「提亮」表达层次：底 `#0F1013` → 气泡 `#1D2024` → 选中 `#353638`，卡片 `#23262A` 居中。
 * 蓝同理：浅色用 [Accent]（对白卡 4.0:1），深色文字用 [DarkAccentFg] 提亮到 6.8:1，
 * 否则同一支蓝在暗底上会发闷、在亮底上会刺眼。
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

    /** 页面底。不取纯黑：纯黑上叠黑仍是纯黑，按压反馈在底色上会失效。 */
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
     * 这一档是「鲜」的，只画线、不铺面——线只有 1–4dp 宽，足够饱和才看得见。
     * 填充面另有 [AccentFill] / [AccentFillDark] 两支，比这一对低饱和得多，理由见那里。
     */
    val Accent = Color(0xFF4176E6)

    /**
     * 强调色的**填充档**（按钮、开关轨道、进度条）：Salt Player 的实测值。
     *
     * 与 [Accent] 分开是因为「线」和「面」对饱和度的容忍完全不同：
     * 同样的蓝色画成 2dp 的线很精神，铺成 40dp 的圆钮就是一坨色块。
     *
     * 实测对比（两个沙发的差别不在色相，在饱和度）：
     * | | 色相 | 饱和度 | 明度 |
     * |---|---|---|---|
     * | Salt 浅 `#4D5C93` | 227° | **31%** | 44% |
     * | 我们原 `#4176E6` | 221° | **77%** | 58% |
     * | Salt 深 `#B6C4FF` | 228° | 100% | 86% |
     * | 我们原 `#7AAAFF` | 218° | 100% | 74% |
     *
     * 浅色差在饱和度（31% 对 77%），深色差在明度（86% 对 74%）。
     * Salt 的浅色档是「灰蓝」而不是「鲜蓝」，深色档则提到接近白，两者都不刺眼。
     *
     * 对比度：白压 `#4D5C93` 6.43:1，墨压 `#B6C4FF` 11.17:1，都远超正文标准。
     */
    val AccentFill = Color(0xFF4D5C93)
    val AccentFillDark = Color(0xFFB6C4FF)

    /**
     * 浅色下的强调文字、页签文字与下划线、光标、聚焦边框、链接。
     *
     * 与 [Accent] 同值：DSH 的 `state-business-primary` 在浅色就是 deepseek-500 这一支，
     * 文字与下划线用同一个色，不做「线深字浅」的区分。
     * 代价是对底色 3.84:1，达不到正文的 4.5:1 —— 只用于 13–15sp 的短强调文字与链接，
     * 不用于长段落；需要更深的场合另议，不在本支里私自加深。
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
     * 深浅同值：这一支只做**圆点与小图标**，不做文字。`#22C55E` 对白底只有 2.0:1，
     * 当文字读不清——DSH 也是只拿它点状态点，文字一律走 label 灰。
     * 需要「通过 / 失败」这类判读时，用正文色 + 错误红，不要给绿色配字。
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


    // ── 层次补充 ──

    /** 思考块：嵌在助手气泡内，比助手气泡再低一档。 */
    val BubbleThinkLight = Color(0xFFD8DCE3)
    val BubbleThinkDark = Color(0xFF18191D)

    /**
     * 列表行选中态的底色。只给深浅，不染色。
     *
     * 取值与 DSH 的 `interactive-bg-hover` 同源：浅色是 6% 的蓝墨叠在白卡上（合成 `#F2F3F4`），
     * 深色是 8% 白叠在卡片上（合成 `#35373B`）。DSH 的会话行「选中」与「悬停」用的就是同一档，
     * 不额外加重——重了反而像按下去没弹起来。
     *
     * 与页签的区别见文件头：行只给底与字重，页签给字与线。
     */
    val LightRowSelected = Color(0xFFF2F3F4)
    val DarkRowSelected = Color(0xFF35373B)

    // ── 深色填充上的前景 ──

    /**
     * 压在 [AccentFillDark] 上的图标。
     *
     * 取墨色而不是白色：`#B6C4FF` 已经很亮，白图标压上去只有 1.4:1，等于消失。
     * 墨色压它是 11.17:1。
     */
    val OnAccentDark = Color(0xFF0F1013)

    // ── 轨迹页四支 ──
    //
    // 色相照 DSH 的轨迹时间轴（用户=蓝、助手=蓝红混合、工具=琥珀、上下文=去饱和绿），
    // 明度不照抄：DSH 那几支在浅色只有 2.5–4.0:1，当 11sp 标签字读不清。
    // 这里按「文字压在自身 14% 淡底上仍 ≥4.5:1」逐支重解，四支全部过线。

    val TraceUserLight = Color(0xFF3A65B8)
    val TraceUserDark = Color(0xFF8BAEEE)
    val TraceAssistantLight = Color(0xFF7955B0)
    val TraceAssistantDark = Color(0xFFB7A0E4)
    val TraceToolLight = Color(0xFFA35400)
    val TraceToolDark = Color(0xFFDC9F6A)
    val TraceContextLight = Color(0xFF00793D)
    val TraceContextDark = Color(0xFF72BB8C)

    // ── 开关关闭态轨道 ──

    /**
     * 开关关着的轨道。
     *
     * 取值参照 Salt Player 的实拍（浅 `#DDDBE6` / 深 `#30333C`），只把色相换成中性：
     * 它要「看得出是个轨道」但不能抢开启态，所以浅色比卡片深一档、深色比卡片亮一档。
     */
    val LightSwitchOff = Color(0xFFDCDEE4)
    val DarkSwitchOff = Color(0xFF33363D)
}
