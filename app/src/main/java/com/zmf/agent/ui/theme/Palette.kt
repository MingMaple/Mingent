package com.zmf.agent.ui.theme

import androidx.compose.ui.graphics.Color

/** 取自 Salt Player 的浅/深色截图。 */
object Palette {
    // 浅色
    val LightBackground = Color(0xFFF9F8FE)
    val LightSurface = Color(0xFFEDEBF6)
    val LightSurfaceHigh = Color(0xFFFFFFFF)
    val LightText = Color(0xFF16161A)
    val LightSubText = Color(0xFF6E6E78)
    val LightStroke = Color(0x14000000)

    // 深色
    val DarkBackground = Color(0xFF111217)
    val DarkSurface = Color(0xFF1F222B)
    val DarkSurfaceHigh = Color(0xFF262A34)
    val DarkText = Color(0xFFF2F2F5)
    val DarkSubText = Color(0xFF9A9AA5)
    val DarkStroke = Color(0x1FFFFFFF)

    /**
     * 伴随面板的底色：比页面底色、比助手气泡都亮一档。
     *
     * 单独定而不是复用 `surface`，因为 `surface` 恰好等于助手气泡色（#EDEBF6），
     * 面板弹出来和气泡融成一片，就看不出这是一层浮在上面的东西。
     */
    val LightPanel = Color(0xFFF5F5F7)
    val DarkPanel = Color(0xFF2B2B31)

    // 对话气泡的层次：用户气泡（最亮）> AI 气泡 > 思考窗口 > 页面底色
    val BubbleUserLight = Color(0xFFFFFFFF)
    val BubbleAiLight = Color(0xFFEDEBF6)
    val BubbleThinkLight = Color(0xFFE3DFF1)
    val BubbleCardLight = Color(0xFFFFFFFF)

    val BubbleUserDark = Color(0xFF2B303B)
    val BubbleAiDark = Color(0xFF1F222B)
    val BubbleThinkDark = Color(0xFF181B22)
    val BubbleCardDark = Color(0xFF262A34)

    /**
     * 强调色（原名品牌绿，全局去绿后留下了这个名字）。
     *
     * 现在是比 [Neutral] 深一档的中性灰，全 App 只用这两级灰：
     * 图标与次要文字 → [Neutral]；需要「更重」的地方（发送键、开关「开」态、勾选、强调字）→ 这一支。
     * 保留这个名字只为不动 100+ 处引用；想恢复绿色改回 `Color(0xFF8BCF00)`。
     */
    val AccentGreen = Color(0xFF6E6E78)

    /** 品牌蓝：仅作为 Material colorScheme 的 primary 与 Salt 的 highlight 使用。 */
    val AccentBlue = Color(0xFF0078FF)

    /** 紫色强调：Salt 配色里承担 warning 语义，另用于上下文页的注入内容色。 */
    val AccentPurple = Color(0xFF8B7CF6)

    /** Shizuku 可用时的状态点青色：和「正常绿」区分开，但不抢眼。 */
    val AccentCyan = Color(0xFF22D3EE)

    /**
     * 状态点四态：忙=蓝、错=红（error）、就绪=青（AccentCyan）、空闲=绿。
     * 原来的「绿」是 0xFF6E6E78 灰（全局去绿留下的），四态在 8dp 圆点上分不出来，因此单开这两支。
     */
    val StatusBusy = Color(0xFF4C8DFF)
    val StatusIdle = Color(0xFF3FCF7F)

    /**
     * 图标与次要文字的统一色（中性灰）。
     *
     * 全 App 图标只用一种颜色、不要绿灰混用，所以图标和设置页里的值文本
     * 一律用这一支；强调绿只留给选中态 / 开关 / 主操作按钮这类「状态」。
     */
    val Neutral = Color(0xFF9A9AA5)

    // ── 上下文洞察页色板 ──
    // 对齐 DSH「上下文洞察」插件的取色（Tailwind 500），只给这一页的成分条 / 图例 / 水位档用。
    // 别处不要复用这几支：全 App 的图标与强调仍然只走 AccentGreen / Neutral 两级灰。

    /** 系统提示词：indigo-500。 */
    val CtxSystem = Color(0xFF6366F1)

    /** 工具定义：amber-500。 */
    val CtxTools = Color(0xFFF59E0B)

    /** 用户消息：green-500。 */
    val CtxUser = Color(0xFF22C55E)

    /** 注入内容（会话摘要这类拼进系统提示词的内容）：purple-500。 */
    val CtxInject = Color(0xFFA855F7)

    /** 技能：orange-500（本页暂无对应数据字段，先留色位）。 */
    val CtxSkill = Color(0xFFF97316)

    /** 助手消息：blue-500。 */
    val CtxAssistant = Color(0xFF3B82F6)

    /** 工具结果：teal-500。 */
    val CtxToolResult = Color(0xFF14B8A6)

    /** 水位 ≥ 90%：red-500，告警。 */
    val LevelHigh = Color(0xFFEF4444)

    /** 水位 ≥ 70%：amber-500，接近上限。 */
    val LevelMid = Color(0xFFF59E0B)

    /** 水位 < 70%：green-500，宽裕。 */
    val LevelLow = Color(0xFF22C55E)
}
