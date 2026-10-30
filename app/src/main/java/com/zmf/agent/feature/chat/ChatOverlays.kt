package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.uikit.theme.Motion
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 子任务面板：透明遮罩铺满整屏先吃掉「点外部」的点击，卡片浮在它上面。
 *
 * 只读 SubAgentRegistry 的快照，不碰 ViewModel 的任何状态；它是全屏页之下的一层浮层，
 * 所以单独一个组合、并且在页面层之前调用，层序与拆分前一致。
 */
@Composable
internal fun BoxScope.ChatSubAgentPanel(
    ui: ChatUiState,
    viewModel: ChatViewModel,
    subAgentEntries: List<SubAgentRegistry.Entry>
) {
    var subAgentPanelOpen by ui.subAgentPanelOpen
    var subAgentDetailId by ui.subAgentDetailId
    var page by ui.page
    var topBarHeightPx by ui.topBarHeightPx

    if (subAgentPanelOpen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { subAgentPanelOpen = false }
        )
        SubAgentPanel(
            entries = subAgentEntries,
            onOpen = { id ->
                subAgentPanelOpen = false
                subAgentDetailId = id
                page = AppPage.SUBAGENT
            },
            // 清完不用手动刷新：clearFinished 会 +1 版本号，上面的订阅自己把新快照取回来
            onClearFinished = { SubAgentRegistry.clearFinished() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = with(LocalDensity.current) { topBarHeightPx.toDp() }, end = 12.dp)
        )
    }
}

/**
 * 压在聊天页与页面层之上的浮层：本轮用量、图片长按菜单、选择文本、图片查看器。
 *
 * 都是 BoxScope 的直接子节点，层序就是代码顺序（后写的在上），所以必须留在同一个 Box 里，
 * 这也是它们当初挤在主函数最下面的原因。搬出来后由调用方用 `ChatOverlays(...)` 放在原位。
 */
@Composable
internal fun BoxScope.ChatOverlays(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    scope: CoroutineScope,
    imageRoot: File,
    subAgentEntries: List<SubAgentRegistry.Entry>
) {
    var subAgentDetailId by ui.subAgentDetailId
    var page by ui.page
    var turnUsage by ui.turnUsage
    var imageMenuVisible by ui.imageMenuVisible
    var imageMenuTarget by ui.imageMenuTarget
    var rollbackUserIndex by ui.rollbackUserIndex
    var selectionVisible by ui.selectionVisible
    var selectionTarget by ui.selectionTarget
    var viewer by ui.viewer

    // 「本轮用量」伴随面板：贴着被点的用量按钮弹出，点面板以外任何地方关闭。
    // 不用弹窗（Dialog）：那会把视线从「这条回复花了多少」拉到屏幕中央，也不跟按钮走。
    turnUsage?.let { target ->
        SaltPopover(
            anchor = target.anchor,
            // 宽度取「屏宽减两侧留白」的量级：用量面板要装下「提供方 / 模型」这种长值，
            // 太窄会贴着气泡右缘被看成一截
            widthDp = 300.dp,
            onDismiss = { turnUsage = null }
        ) {
            TurnUsagePanel(usage = target.usage)
        }
    }

    // 长按已发送图片：只有「存这一张」和「回滚到这条提问」两项。
    // 和上面那套文字菜单分开，是因为图片没有可复制的正文；锚点是按住的那一张的坐标。
    AnimatedVisibility(
        visible = imageMenuVisible,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(140)),
        modifier = Modifier.fillMaxSize()
    ) {
        imageMenuTarget?.let { target ->
            val imageActions = listOf(
                MenuAction("保存图片") {
                    imageMenuVisible = false
                    saveImageFromChat(context, scope, imageRoot, target.relPath)
                },
                MenuAction("回滚到此处") {
                    rollbackUserIndex = target.userIndex
                    imageMenuVisible = false
                }
            )
            MessageContextMenu(
                anchor = target.anchor,
                actions = imageActions,
                onDismiss = { imageMenuVisible = false }
            )
        }
    }

    // 选择文本整页：从下方弹出，可下滑关闭
    AnimatedVisibility(
        visible = selectionVisible,
        enter = fadeIn(Motion.fadeIn()) + slideInVertically(Motion.spring()) { it / 4 },
        // 退场由 TextSelectionOverlay 自己播完再回调，这里不能再叠一层淡出
        exit = ExitTransition.None
    ) {
        selectionTarget?.let { target ->
            TextSelectionOverlay(
                title = target.title,
                text = target.text,
                reasoning = target.reasoning,
                onDismiss = { selectionVisible = false }
            )
        }
    }
    // 图片查看器压在所有页面之上：整条消息的图片一起传进去，详情里才能按缩略图顺序左右切
    viewer?.let { target ->
        ImageViewer(
            paths = target.paths,
            startIndex = target.startIndex,
            root = imageRoot,
            onDismiss = { viewer = null }
        )
    }
}
