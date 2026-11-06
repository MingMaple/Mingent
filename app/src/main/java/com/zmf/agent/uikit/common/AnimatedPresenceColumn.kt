package com.zmf.agent.uikit.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.zmf.agent.uikit.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 退场动画的兜底时长。
 *
 * 动画结束后才真正从列表里移除条目。高度弹簧（阻尼 1.0、刚度 420）实际收敛约 300ms，
 * 取 320ms 留一档余量：提前移除会使下方条目先发生一次回跳。
 */
private const val EXIT_MS = 320L

/**
 * 带增删动画的纵向列表（非 Lazy）。
 *
 * 抽屉里的会话树与工作区树不是 LazyColumn（外层可滚动、分组可折叠），
 * 直接 forEach 时新建/归档/移动会让行瞬间出现或消失，下面的条目跟着一跳。
 * 这里为每个 key 保留一份「在场」状态：
 *
 * - 新增条目：从 0 高度展开并淡入，把后面的行平滑推下去；
 * - 移除条目：先收缩淡出，动画结束后才真正移除，后面的行随之平滑补位；
 * - 首帧已存在的条目直接以可见状态挂载，不播放入场动画（避免打开抽屉时整列闪动）。
 *
 * 仅适用于元素数量为「用户手工创建」量级的列表；大量条目请用 LazyColumn + animateItem。
 */
@Composable
fun <T> AnimatedPresenceColumn(
    items: List<T>,
    keyOf: (T) -> String,
    modifier: Modifier = Modifier,
    enter: EnterTransition = expandVertically(animationSpec = Motion.heightSpring()) +
        fadeIn(Motion.fadeIn()),
    exit: ExitTransition = shrinkVertically(animationSpec = Motion.heightSpring()) +
        fadeOut(Motion.fadeOut()),
    content: @Composable (T) -> Unit
) {
    // 首次组合直接用当前数据初始化：这些条目已经「在场」，不应播放入场动画
    val shown = remember { mutableStateListOf<T>().apply { addAll(items) } }
    val visible = remember {
        mutableStateMapOf<String, Boolean>().apply { items.forEach { put(keyOf(it), true) } }
    }
    val scope = rememberCoroutineScope()
    // 正在退场的条目：若它又被加回目标列表（例如改错工作区后立刻改回），取消删除
    val pending = remember { mutableMapOf<String, Job>() }

    LaunchedEffect(items) {
        val targetKeys = items.map(keyOf).toSet()
        // 按目标顺序更新并在缺失时插入。插入位置紧跟在上一个目标条目之后：
        // 退场中的旧条目仍占着原位，其余条目依次补位，不会打乱视觉顺序
        var anchor = -1
        items.forEach { item ->
            val key = keyOf(item)
            val at = shown.indexOfFirst { keyOf(it) == key }
            if (at < 0) {
                val pos = (anchor + 1).coerceIn(0, shown.size)
                shown.add(pos, item)
                anchor = pos
            } else {
                shown[at] = item
                anchor = at
            }
            visible[key] = true
            pending.remove(key)?.cancel()
        }
        // 目标里已经不存在的条目：先标记退场，等动画跑完再移除
        shown.filter { keyOf(it) !in targetKeys }.forEach { item ->
            val key = keyOf(item)
            if (pending[key] != null) return@forEach
            visible[key] = false
            pending[key] = scope.launch {
                delay(EXIT_MS)
                val index = shown.indexOfFirst { keyOf(it) == key }
                if (index >= 0) shown.removeAt(index)
                visible.remove(key)
                pending.remove(key)
            }
        }
    }

    Column(modifier = modifier) {
        shown.forEach { item ->
            val key = keyOf(item)
            // 必须按 key 区分每一行：条目被移除后若只按位置复用组合状态，紧随其后的那一行
            // 会继承上一行的退场进度并从 0 高度重新展开，表现为「下方条目先向上窜过头、
            // 再落回原位」。key() 让每行的进场/退场状态跟着自己的 key 走。
            key(key) {
                AnimatedVisibility(
                    visible = visible[key] ?: true,
                    enter = enter,
                    exit = exit
                ) {
                    // AnimatedVisibility 的内部布局会把多个子节点叠在同一个原点；
                    // 工作区分组本身就是「标题行 + 会话列 + 展开其余」多段内容，
                    // 必须收进一个 Column 再交给它，否则组标题会和第一条会话重叠。
                    // 单行内容（如一条会话）套一层 Column 只是一层无副作用的布局。
                    Column { content(item) }
                }
            }
        }
    }
}
