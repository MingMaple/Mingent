package com.zmf.agent.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 面板开关的对外把手。
 *
 * 面板是哪一层（＋ / 权限 / 上下文 / 模型）仍然关在 [ChatComposer] 里——外面不需要知道，
 * 只需要回答两个问题：「现在有没有面板占着地方」（[open]）和「把它收掉」（[dismiss]）。
 *
 * 用法：父层 remember 一份，同一个对象同时喂给 [ChatComposer] 的 panelHost 参数和 [ComposerScrim]。
 * 单向：open 只由 ChatComposer 写（每次组合时 bind 一次），外面只读；dismiss 转调它内部的 close。
 */
@Stable
class ComposerPanelHost {

    /** 当前有没有面板开着。只由 [ChatComposer] 写入。 */
    var open by mutableStateOf(false)
        private set

    /** 真正关面板的动作：ChatComposer 每次组合时把自家 close 挂到这里。 */
    private var close: () -> Unit = {}

    /** ChatComposer 专用：同步开关状态与关闭动作。 */
    internal fun bind(open: Boolean, close: () -> Unit) {
        this.open = open
        this.close = close
    }

    /** 遮罩点到空白时调它。没有面板开着时是空操作。 */
    fun dismiss() {
        close()
    }
}

/** 左缘这条带子留给抽屉：从这里起手一律不吃，横滑照旧落到父级抽屉的手势上。 */
private val DrawerEdge = 24.dp

/**
 * 面板之外的透明遮罩层：铺满父容器，只干两件事——吃掉点击不让它穿透，点了空白把面板收掉。
 *
 * 父层（ChatScreen）把它放在消息区之上、输入条之下（z 序）：盖住消息列表，但别盖输入条，
 * 否则连输入框和标签一起点不动。因此把它放在「消息区的 Box 里、LazyColumn 之后」就是对的，
 * 面板浮层由 ChatComposer 画在输入条上方，z 序本来就压在遮罩之上，点面板不会走到这里。
 *
 * 手势口径（与 ui/common 里的 blockTapsOnly 同族，但方向相反：那边只吞点击放行拖动，
 * 这里要吞拖动、只放行左缘那一种）：
 * - DOWN 在 Initial 阶段就吃掉，父级抽屉那套整屏 draggable 不会因为一次点击被带起来；
 * - 抬手就收面板，不管是轻点还是拖了一段——「面板以外任何一下都该让它走」；
 * - 只有左缘 [DrawerEdge] 那一条带子例外，整段手势一律放行给抽屉；
 * - 从左侧 [DrawerEdge] 内起手的，一个事件都不消费，横滑直接交给父级抽屉。
 *
 * @param visible 只在有面板开着时为 true；false 时不进组合，连节点都不存在，不会挡住任何东西。
 * @param onDismiss 点空白时调用，一般是 [ComposerPanelHost.dismiss]。
 */
@Composable
fun ComposerScrim(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val edgePx = with(LocalDensity.current) { DrawerEdge.toPx() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 左缘内起手：DOWN 也不吃，抽屉的整屏手势要能照常起来
                    val fromEdge = down.position.x <= edgePx
                    if (!fromEdge) down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // 抬手就收：轻点、按住拖一段、在列表上划一下，都算「在面板以外摸了一把」。
                            // 以前只有「没超过 touchSlop 的轻点」才收，用户按下去再一划松手，面板就赖着不走。
                            if (!fromEdge) {
                                // 抬手的这一下也吃掉，下面那层不会收到一次「补发的点击」
                                change.consume()
                                onDismiss()
                            }
                            break
                        }
                        if (!fromEdge) change.consume()
                    }
                }
            }
    )
}
