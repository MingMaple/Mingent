package com.zmf.agent.uikit.theme

import androidx.compose.ui.unit.Density

/** 固定基准页使用的字号倍率：1.0 档，即设计字号。 */
const val DESIGN_BASELINE_FONT_SCALE = 1f

/**
 * 固定设计基准的 density：只把 `fontScale` 换回 1，保留原 density。
 *
 * 仅供「视觉锁定在设计字号」的页面使用（当前只有洞察页），不是通用工具：
 * 普通页面必须继承 [AgentTheme] 提供的 density，才能跟随全局基准字号。
 */
fun baselineTextDensity(density: Density): Density =
    Density(density = density.density, fontScale = DESIGN_BASELINE_FONT_SCALE)
