package com.zmf.agent.uikit.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效规格统一置于此处，与 Salt 的交互手感对齐：
 * 抽屉、长按小窗、选择文本页与工具卡展开均使用同一套弹簧参数，避免各处自行定义。
 */
object Motion {

    /** 通用弹簧：略微欠阻尼，释放时有轻微回弹。 */
    fun <T> spring() = spring<T>(dampingRatio = 0.86f, stiffness = 420f)

    /** 更软的弹簧，用于小窗这种小元素。 */
    fun <T> softSpring() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /**
     * 只给**高度**用的弹簧：临界阻尼，不冲过头。
     *
     * 通用 spring() 为欠阻尼（0.86），高度缩小时会先低于目标值再回弹，
     * 表现为「收起动画结束后再次突然缩短」。
     * 位移与缩放类动画仍使用 spring()，其轻微回弹为预期效果。
     */
    fun <T> heightSpring() = spring<T>(dampingRatio = 1.0f, stiffness = 420f)

    /** 淡入规格：140ms，略长于淡出，避免出现时显得急促。 */
    fun fadeIn() = tween<Float>(durationMillis = 140)

    /** 淡出规格：120ms，比淡入短，使内容退场更干净。 */
    fun fadeOut() = tween<Float>(durationMillis = 120)
}
