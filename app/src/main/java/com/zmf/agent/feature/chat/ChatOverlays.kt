package com.zmf.agent.feature.chat
import com.zmf.agent.uikit.components.FoldToggle
import com.zmf.agent.feature.terminal.TerminalActivity
import com.zmf.agent.core.llm.ApiDefaults
import com.zmf.agent.uikit.common.BoundedScrollColumn
import com.zmf.agent.uikit.common.blockTapsOnly

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.core.store.GoalPhase
import com.zmf.agent.core.store.PlanDoc
import com.zmf.agent.core.store.TodoStore
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.infra.api.BalanceClient
import com.zmf.agent.infra.api.BalanceResult
import com.zmf.agent.infra.backup.BackupArchive
import com.zmf.agent.infra.backup.BackupOptions
import com.zmf.agent.infra.system.ShizukuBridge
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.feature.float.FloatingBallService
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.feature.float.ChatBridge
import com.zmf.agent.feature.settings.AboutPage
import com.zmf.agent.feature.settings.CreateWorkspaceDialog
import com.zmf.agent.feature.settings.LogsPage
import com.zmf.agent.feature.settings.ArchivePage
import com.zmf.agent.feature.settings.TrashPage
import com.zmf.agent.feature.settings.PackagePage
import com.zmf.agent.feature.settings.PermissionPage
import com.zmf.agent.feature.schedule.SchedulePage
import com.zmf.agent.feature.settings.ContextConfigPage
import com.zmf.agent.feature.settings.ContextHubPage
import com.zmf.agent.feature.settings.ContextInsight
import com.zmf.agent.feature.settings.MemoryPage
import com.zmf.agent.feature.settings.DataSettingsPage
import com.zmf.agent.feature.settings.GeneralSettingsPage
import com.zmf.agent.feature.settings.ModelSettingsPage
import com.zmf.agent.feature.settings.SettingsHomePage
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.capability.prompt.PromptStore
import com.zmf.agent.feature.chat.ChatComposer
import com.zmf.agent.feature.chat.ComposerCommand
import com.zmf.agent.feature.chat.ComposerStatus
import com.zmf.agent.feature.chat.ContextUsageInfo
import com.zmf.agent.feature.chat.TracePage
import com.zmf.agent.feature.shell.AppDrawerContent
import com.zmf.agent.uikit.components.salt.SaltDrawer
import com.zmf.agent.uikit.components.salt.SaltFullPane
import com.zmf.agent.uikit.components.salt.SaltSwitch
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.ThemeMode
import com.zmf.agent.uikit.theme.UiScaleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
