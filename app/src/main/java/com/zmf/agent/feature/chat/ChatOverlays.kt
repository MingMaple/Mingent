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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.uikit.theme.Motion
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 子任务面板：透明遮罩铺满全屏并优先消费「点击外部」的点击，卡片浮于其上。
 *
 * 仅读取 SubAgentRegistry 的快照，不触碰 ViewModel 的任何状态；其为全屏页之下的浮层，
 * 因此单独组合并在页面层之前调用，层序与拆分前一致。
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
            // 清理后无需手动刷新：clearFinished 会将版本号 +1，上方订阅自行取回新快照
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
 * 均为 BoxScope 的直接子节点，层序即代码顺序（后写者在上），因此必须保留在同一 Box 内，
 * 这也是它们此前集中在主函数末尾的原因。拆分后由调用方以 `ChatOverlays(...)` 放在原位置。
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

    // 「本轮用量」伴随面板：贴附被点击的用量按钮弹出，点击面板以外任何位置关闭。
    // 不使用弹窗（Dialog）：弹窗会将视线从「这条回复消耗多少」引向屏幕中央，且不跟随按钮。
    turnUsage?.let { target ->
        SaltPopover(
            anchor = target.anchor,
            // 宽度取「屏宽减两侧留白」的量级：用量面板要装下「提供方 / 模型」这种长值，
            // 过窄会贴附气泡右缘而显示不全
            widthDp = 300.dp,
            onDismiss = { turnUsage = null }
        ) {
            TurnUsagePanel(usage = target.usage)
        }
    }

    // 长按已发送图片：仅提供「保存这一张」与「回滚到这条提问」两项。
    // 与上述文字菜单分开，是因为图片没有可复制的正文；锚点为按住图片的坐标。
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
        // 退场由 TextSelectionOverlay 自行播放完毕后再回调，此处不可再叠加淡出
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
    // 图片查看器位于所有页面之上：整条消息的图片一并传入，详情中方可按缩略图顺序左右切换
    viewer?.let { target ->
        ImageViewer(
            paths = target.paths,
            startIndex = target.startIndex,
            origin = target.origin,
            root = imageRoot,
            onDismiss = { viewer = null },
            // 背景虚化与图片缩放同一条进度：写回界面状态，由聊天列按图层半径读取
            onProgress = { ui.viewerProgress.floatValue = it }
        )
    }
}
