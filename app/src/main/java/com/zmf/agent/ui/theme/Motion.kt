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

    /**
     * 只给**高度**用的弹簧：临界阻尼，不冲过头。
     *
     * 通用 spring() 是欠阻尼（0.86），缩小高度时会先压到比目标小一点再弹回来，
     * 看到的现象就是「收起的动效已经走完，又突地缩短了一下」。
     * 位移/缩放类动画仍用 spring()，那里的一点回弹是想要的手感。
     */
    fun <T> heightSpring() = spring<T>(dampingRatio = 1.0f, stiffness = 420f)

    /** 淡入规格：140ms，略长于淡出，避免出现时显得仓促。 */
    fun fadeIn() = tween<Float>(durationMillis = 140)

    /** 淡出规格：120ms，比淡入短，让内容退场更利落。 */
    fun fadeOut() = tween<Float>(durationMillis = 120)
}
