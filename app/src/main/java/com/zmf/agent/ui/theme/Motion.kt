package com.zmf.agent.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效规格统一放这里，跟 Salt 的手感对齐：
 * 抽屉、长按小窗、选择文本页、工具卡展开都用同一套弹簧，避免各写各的。
 */
object Motion {

    /** 通用弹簧：略微欠阻尼，松手有一点回弹感。 */
    fun <T> spring() = spring<T>(dampingRatio = 0.86f, stiffness = 420f)

    /** 更软的弹簧，用于小窗这种小元素。 */
    fun <T> softSpring() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    fun fadeIn() = tween<Float>(durationMillis = 140)

    fun fadeOut() = tween<Float>(durationMillis = 120)
}
