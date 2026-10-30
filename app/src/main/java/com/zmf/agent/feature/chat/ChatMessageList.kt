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
 * 消息流与其上的两层浮层：面板遮罩、分叉时的「瞬灭」盖板。
 *
 * 是 Column 里吃剩余高度的那一格，所以必须留着 `weight(1f)`——这也是它声明成 ColumnScope
 * 扩展的原因：`Modifier.weight` 只有在这个作用域里拿得到。
 */
@Composable
internal fun ColumnScope.ChatMessageList(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    imageRoot: File,
    items: List<ChatItem>,
    listState: LazyListState
) {
    val scope = rememberCoroutineScope()
    var viewer by ui.viewer
    var selectionTarget by ui.selectionTarget
    var selectionVisible by ui.selectionVisible
    var imageMenuTarget by ui.imageMenuTarget
    var imageMenuVisible by ui.imageMenuVisible
    var rollbackUserIndex by ui.rollbackUserIndex
    var turnUsage by ui.turnUsage
    var forkReloading by ui.forkReloading
    val composerPanel = ui.composerPanel
    val forkReloadAlpha by animateFloatAsState(
        targetValue = if (forkReloading) 1f else 0f,
        animationSpec = tween(durationMillis = if (forkReloading) 70 else 140),
        label = "forkReload"
    )
    val forkWithReload: (() -> Unit) -> Unit = { action -> ui.forkWithReload(scope, action) }

    // 长按小窗只在一个前提下弹：列表此刻没在滑。
    fun longPressAllowed(): Boolean = !listState.isScrollInProgress

                    // 消息区 + 挂在上方的面板：面板是浮层，不吃列表高度（列表不会重排）
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        // 内容不满一屏时贴底（Bottom）：新消息应该从底部往上长，而不是浮在半空。
                        // 内容超过一屏后 Bottom 对齐不再起作用，滚动行为与原来的 spacedBy 完全一致。
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)
                    ) {
                        if (items.isEmpty()) {
                            item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "输入消息开始对话",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    )
                                }
                            }
                        }
                        // 必须给稳定 key：items(count = ...) 默认拿下标当 key，而流式输出时
                        // 消息条数一直在变，同一下标会指向「另一个回合」，LazyColumn 就把新内容
                        // 复用进旧槽位，槽位里残留的展开/点击状态跟着错位。
                        // UserItem 用消息下标；AssistantItem 用这一轮第一条消息的下标（一轮内不变）。
                        items(
                            count = items.size,
                            key = { position ->
                                when (val item = items[position]) {
                                    is ChatItem.UserItem -> "u" + item.index
                                    is ChatItem.AssistantItem -> "a" + item.indices.first()
                                }
                            },
                            // contentType 分开：用户气泡与助手回合结构不同，不分开会让列表
                            // 把这两种行当同一类型复用，重组时更容易带错状态。
                            contentType = { position ->
                                when (items[position]) {
                                    is ChatItem.UserItem -> "user"
                                    is ChatItem.AssistantItem -> "assistant"
                                }
                            }
                        ) { position ->
                            when (val item = items[position]) {
                                is ChatItem.UserItem -> UserRow(
                                    message = item.message,
                                    roundsFromEnd = item.roundsFromEnd,
                                    imageRoot = imageRoot,
                                    onCopy = { text -> copyToClipboard(context, text) },
                                    onSelectText = { text ->
                                        selectionTarget = SelectionTarget(
                                            title = "选择文本",
                                            text = text,
                                            reasoning = ""
                                        )
                                        selectionVisible = true
                                    },
                                    onRollback = { rollbackUserIndex = item.index },
                                    onImageClick = { paths, index -> viewer = ViewerTarget(paths, index) },
                                    onImageLongPress = { rel, anchor ->
                                        // 同 openMenu：惯性还没停就按住的那一下不算长按
                                        if (longPressAllowed()) {
                                            imageMenuTarget = ImageMenuTarget(
                                                relPath = rel,
                                                userIndex = item.index,
                                                anchor = anchor
                                            )
                                            imageMenuVisible = true
                                        }
                                    }
                                )

                                is ChatItem.AssistantItem -> AssistantRow(
                                    all = viewModel.messages,
                                    indices = item.indices,
                                    streaming = viewModel.isStreaming &&
                                        item.indices.lastOrNull() == viewModel.messages.lastIndex,
                                    toolOutcomes = viewModel.toolOutcomes,
                                    onCopy = { text -> copyToClipboard(context, text) },
                                    onSelectText = { text, reasoning ->
                                        selectionTarget = SelectionTarget(
                                            title = "选择文本",
                                            text = text,
                                            reasoning = reasoning
                                        )
                                        selectionVisible = true
                                    },
                                    onFork = { boundary -> forkWithReload { viewModel.forkSession(boundary) } },
                                    onUsage = { usage, anchor ->
                                        turnUsage = TurnUsageTarget(usage = usage, anchor = anchor)
                                    }
                                )
                            }
                        }
                    }

                    // 面板打开时铺一层透明遮罩：点/划「输入条以外的区域」就收面板。
                    // 必须是消息区 Box 的最后一个孩子位：输入条与面板浮层都在它上层，点面板不会走到这里。
                    // 早先误放进「待办条可见」的 if 分支里，没有待办的会话整个就没有遮罩：面板开着仍能
                    // 滚列表、还能点开折叠条，面板赖着不走。
                    ComposerScrim(
                        visible = composerPanel.open,
                        onDismiss = { composerPanel.dismiss() }
                    )

                    // 分叉时的「瞬灭再加载」：用页面底色盖住消息区再淡出。
                    // 放在这一层最后：要盖住列表与上面的透明遮罩；不挂手势，闪完就透明。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = forkReloadAlpha }
                            .background(MaterialTheme.colorScheme.background)
                    )
                    }
}
