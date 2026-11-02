package com.zmf.agent.feature.chat

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
 * 面板开关的对外入口。
 *
 * 面板所属层级（＋ / 权限 / 上下文 / 模型）仍封装在 [ChatComposer] 内，外部无需感知，
 * 仅需回答两个问题：「当前是否有面板占用空间」（[open]）与「关闭面板」（[dismiss]）。
 *
 * 用法：父层 remember 一份，同一对象同时提供给 [ChatComposer] 的 panelHost 参数与 [ComposerScrim]。
 * 单向：open 仅由 ChatComposer 写入（每次组合时 bind 一次），外部只读；dismiss 转调其内部的 close。
 */
@Stable
class ComposerPanelHost {

    /** 当前是否有面板打开。仅由 [ChatComposer] 写入。 */
    var open by mutableStateOf(false)
        private set

    /** 实际关闭面板的动作：ChatComposer 每次组合时将其 close 挂到此。 */
    private var close: () -> Unit = {}

    /** 仅供 ChatComposer 使用：同步开关状态与关闭动作。 */
    internal fun bind(open: Boolean, close: () -> Unit) {
        this.open = open
        this.close = close
    }

    /** 点击遮罩空白处时调用。没有面板打开时为空操作。 */
    fun dismiss() {
        close()
    }
}

/** 左缘这条区域留给抽屉：由此起手的事件一律不消费，横滑仍交由父级抽屉的手势处理。 */
private val DrawerEdge = 24.dp

/**
 * 面板之外的透明遮罩层：铺满父容器，仅承担两项职责：拦截点击避免穿透，点击空白处收起面板。
 *
 * 父层（ChatScreen）将其置于消息区之上、输入条之下（z 序）：覆盖消息列表但不覆盖输入条，
 * 否则输入框与标签均无法点击。因此放在「消息区的 Box 内、LazyColumn 之后」是正确的，
 * 面板浮层由 ChatComposer 绘制在输入条上方，z 序高于遮罩，点击面板不会触发此处。
 *
 * 手势约定（与 ui/common 中的 blockTapsOnly 同类，方向相反：后者仅拦截点击、放行拖动，
 * 此处拦截拖动、仅放行左缘一种）：
 * - DOWN 在 Initial 阶段即消费，父级抽屉的整屏 draggable 不会因一次点击被触发；
 * - 抬手即收起面板，无论轻点还是拖动一段：「面板以外的任何操作都应关闭面板」；
 * - 仅左缘 [DrawerEdge] 区域例外，整段手势一律放行给抽屉；
 * - 自左侧 [DrawerEdge] 内起手时不消费任何事件，横滑直接交由父级抽屉。
 *
 * @param visible 仅在面板打开时为 true；false 时不参与组合，节点不存在，不遮挡任何内容。
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
                    // 左缘内起手：DOWN 也不消费，抽屉的整屏手势需正常触发
                    val fromEdge = down.position.x <= edgePx
                    if (!fromEdge) down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // 抬手即收起：轻点、按住拖动一段、在列表上滑动，均视为「面板以外的操作」。
                            // 此前仅「未超过 touchSlop 的轻点」才收起，按下后拖动再松手，面板不会关闭。
                            if (!fromEdge) {
                                // 抬手事件同样消费，下层不会收到一次补发的点击
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
