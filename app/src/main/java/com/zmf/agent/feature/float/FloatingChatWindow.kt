package com.zmf.agent.feature.float

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.capability.runtime.ChatPortSession
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.components.FoldToggle
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import com.zmf.agent.uikit.common.saltCursorBrush
import com.zmf.agent.uikit.markdown.MarkdownText

/** 小窗只读展示的最近消息条数（较早消息不放入 LazyColumn，减少内存与布局开销）。 */
private const val MAX_SHOWN = 80

/**
 * 小窗折叠：正文完整高度超过可视高度的该比例时才折叠。
 *
 * 使用比例而非固定 dp：小窗宽高由用户自由拖动，同一段内容在较高的窗口中应完整显示，
 * 在较矮的窗口中才折叠。主页按屏幕 45% 折叠，此处放宽至 62%：小窗高度较小，
 * 过早折叠会仅显示标题，导致需要查看两次。
 */
private const val FLOAT_FOLD_AT_RATIO = 0.62f

/** 折叠态占可视高度的最大比例：窗口变矮时随之收缩，拉高时自动展开。 */
private const val FLOAT_FOLDED_RATIO = 0.42f

/** 小窗为阴影预留的内边距：窗口尺寸由服务提供精确像素（默认 240x320dp），阴影需绘制在窗口内部才能显示。 */
private val SHADOW_PADDING = 5.dp

/** 小窗圆角：12dp。 */
private val WINDOW_CORNER = 12.dp

/** 顶部工具条高度：48dp。 */
private val TOP_BAR_HEIGHT = 48.dp

/** 工具条圆按钮直径：30dp。 */
private val TOOL_BUTTON_SIZE = 30.dp

/** 右侧调宽手柄：命中区为 16dp 宽、满高（位于窗口内，贴合右边缘）。 */
private val RIGHT_HANDLE_HIT_W = 16.dp

/** 底部调高手柄：命中区为满宽、12dp 高（位于窗口内，贴合底边；位于输入行下方，不与输入行重叠）。 */
private val BOTTOM_HANDLE_HIT_H = 12.dp

/** 手柄视觉：长边 60dp、短边 6dp、圆角 3dp（右侧竖条与底部横条共用）。 */
private val HANDLE_LONG = 60.dp
private val HANDLE_THIN = 6.dp
private val HANDLE_CORNER = 3.dp

/** 手柄配色：空闲时底色 12%、描边 30%，拖动中加深至 22% 与 45%（提供进度反馈）。 */
private const val HANDLE_BG_IDLE = 0.12f
private const val HANDLE_BG_DRAG = 0.22f
private const val HANDLE_BORDER_IDLE = 0.30f
private const val HANDLE_BORDER_DRAG = 0.45f

/**
 * 输入框焦点变化的对外回调。
 *
 * 服务侧仅用于一项操作：输入框真正获得焦点时请求一次 IME（200ms 防抖）。
 * 窗口 flags 在展开时一次性设为可获焦，不随焦点变化；若在此反复修改 flags，
 * 窗口失焦时系统会收起键盘，代码又调用 showSoftInput，导致键盘反复闪烁。
 * Composable 形参固定，因此通过 CompositionLocal 将该事件回传给服务，
 * 默认实现为空操作（单独预览也不会崩溃）。
 */
val LocalFloatInputFocus = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * 系统小窗中的对话面板（尺寸由服务提供，默认 240x320dp，圆角 12dp，八级阴影）。
 *
 * 顶部 48dp 工具条分为两端：左侧为会话切换与折叠回球，
 * 右侧为回主页与关闭，中间为可拖动空白区域；不显示会话名。
 * 会话切换展开后，面板铺满消息区，列出未归档会话（无图标，当前会话标记「当前」）。
 * 中间只读展示当前会话消息，底部为输入行（surface 面片与圆角，无描边），
 * 回车与点击发送均调用 [onSend]。
 *
 * 图标：工程未引入 material-icons 依赖，因此四个图标使用 Canvas 绘制，
 * 不新增资源文件，在任何系统字体下均可正常渲染。
 *
 * 手势（锚点均为窗口左上角，触控区域与换算关系与参考实现一致）：
 * - 移动：仅左右按钮组之间的空白区域可拖动（[onMoveBy]，位移直接按 px 传递）；
 * - 调宽：右边缘 16dp 宽、满高的竖条（[onResizeWidth]），仅修改宽度，x 不变；
 * - 调高：底边满宽、12dp 高的横条（[onResizeHeight]），位于输入行下方，仅修改高度，y 不变；
 * - 松手：[onGestureEnd] 通知服务「本次手势结束」，服务仅在此刻持久化一次。
 *
 * 尺寸自适应：内边距固定为 8dp 与 20dp（右侧为调宽手柄留出空间），按钮组宽度固定，
 * 中间可拖动区域占用剩余宽度且无最小宽度——窗口拖至最窄时优先缩减可拖动区域，按钮始终完整可点。
 *
 * 此处仅负责绘制并将手指位移换算为增量，实际修改窗口参数、边界夹取与持久化均由
 * FloatingBallService 完成。
 */
@Composable
fun FloatingChatWindow(
    messages: List<ChatMessage>,
    sessions: List<ChatPortSession>,
    onSend: (String) -> Unit,
    onHome: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onPickSession: (String) -> Unit,
    onMoveBy: (Float, Float) -> Unit,
    onResizeWidth: (Float) -> Unit,
    onResizeHeight: (Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    val focusHook = LocalFloatInputFocus.current
    var text by remember { mutableStateOf("") }
    // 会话切换面板是否展开：覆盖于消息区之上，点击某条会话即切换并收起
    var sessionPanel by remember { mutableStateOf(false) }
    // 小窗不在 Activity 中，不使用 rememberLazyListState（其依赖 rememberSaveable，需要 SavedStateRegistryOwner）
    val listState = remember { LazyListState() }
    // 消息区当前尺寸（px）：拖动底边改变窗口高度或拖动右边改变宽度时均会变化，用于触发一次「贴底」。
    // 保存 IntSize 而非单个高度：窗口拉宽会导致长文本重新换行，最后一条的位置也会变化，
    // 两个维度都参与才能实现「窗口尺寸变化后重新贴底」。
    var messageAreaSize by remember { mutableStateOf(IntSize.Zero) }

    // 仅选取用户与 AI 两类正常气泡：工具消息与系统提示在小窗中无法展开，直接跳过
    val shown = ArrayList<ChatMessage>()
    for (message in messages) {
        if ((message.role == "user" || message.role == "assistant") && message.content.isNotBlank()) {
            shown.add(message)
        }
    }
    val visible = if (shown.size > MAX_SHOWN) shown.subList(shown.size - MAX_SHOWN, shown.size) else shown
    // 列表项的稳定 key：流式输出时条数会变，索引 key 会把新回合复用到旧槽位，导致展开态错位
    val visibleOffset = shown.size - visible.size

    /*
     * 贴底：两个触发条件共用该逻辑。
     * 1) 消息条数变化：新回复进入时，小窗可视高度有限，不滚动到底部将无法看到最新回复；
     * 2) 消息区尺寸变化：向下拖动底边后视口变矮，原有滚动量无法保持贴底，
     *    消息会停留在上一位置，无法随输入框上移。尺寸变化后重新「滚动到最后一项」，
     *    LazyListState 会将该次滚动限制到内容末尾，表现为最后一条紧贴输入框上移。
     * 触发点位于 LazyColumn 的 onSizeChanged（在布局阶段写入 state，重组后该 effect 才执行，不干扰布局）。
     */
    LaunchedEffect(visible.size, messageAreaSize) {
        if (visible.isNotEmpty()) runCatching { listState.scrollToItem(visible.lastIndex) }
    }
    // 小窗收起或销毁时补充一次「失焦」：否则窗口会持续认为输入框持有焦点，收起后仍抢占键盘
    DisposableEffect(Unit) {
        onDispose { focusHook(false) }
    }

    val canSend = text.isNotBlank()
    val submit: () -> Unit = {
        val body = text.trim()
        if (body.isNotEmpty()) {
            onSend(body)
            text = ""
        }
    }

    // 内缩 SHADOW_PADDING：阴影绘制在窗口内部，贴合窗口边缘会被裁剪
    Box(Modifier.fillMaxSize().padding(SHADOW_PADDING)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(WINDOW_CORNER), clip = false),
            shape = RoundedCornerShape(WINDOW_CORNER),
            // 画布使用页面底色而非 surface：surface 与 AI 气泡色相同，会导致气泡与背景混为一体。
            // 改用页面底色后，小窗的三层（底、AI 气泡、用户气泡）与主页对话页层次一致。
            color = MaterialTheme.colorScheme.background
        ) {
            // 内容与两个贴边手柄：手柄绘制在窗口内部，贴合可变动的那条边
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    // 标题栏固定为 48dp，输入行与底部手柄位于下方，中间区域使用 weight(1f) 占满剩余高度：
                    // 窗口变宽或变高时仅消息区扩展，标题栏与输入行不会被压缩
                    TopBar(
                        sessionsOpen = sessionPanel,
                        onSessions = { sessionPanel = !sessionPanel },
                        onCollapse = onCollapse,
                        onHome = onHome,
                        onClose = onClose,
                        onMoveBy = onMoveBy,
                        onGestureEnd = onGestureEnd
                    )

                    // 消息区：会话切换面板覆盖其上（同一区域，切换后自动收起）
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (visible.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "还没有消息，说句话试试",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    } else {
                        // 折叠阈值按当前可视高度实时计算：窗口由用户自由拖动，同一段内容
                        // 在较高时展开、较矮时折叠，固定 dp 在高度较小的窗口中会导致完全折叠。
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val foldAt = maxHeight * FLOAT_FOLD_AT_RATIO
                            val foldedHeight = maxHeight * FLOAT_FOLDED_RATIO
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    // 尺寸变化时记录（写入相同值不会触发重组），交由上方的贴底 effect 处理
                                    .onSizeChanged { messageAreaSize = it },
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                items(visible.size, key = { visibleOffset + it }) { index ->
                                    MessageBubble(
                                        message = visible[index],
                                        foldAt = foldAt,
                                        foldedHeight = foldedHeight
                                    )
                                }
                            }
                        }
                    }

                    if (sessionPanel) {
                        SessionPickerPanel(
                            sessions = sessions,
                            onPick = { id ->
                                sessionPanel = false
                                onPickSession(id)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    }

                    InputRow(
                        text = text,
                        onTextChange = { text = it },
                        canSend = canSend,
                        onFocusChanged = focusHook,
                        onSubmit = submit
                    )

                    // 底部调高手柄：位于输入行下方，独占 12dp（窗口内最底部），不与输入行重叠，
                    // 避免点击发送按钮下缘时被误判为 resize
                    BottomResizeHandle(
                        onResizeHeight = onResizeHeight,
                        onGestureEnd = onGestureEnd
                    )
                }

                // 右侧调宽手柄：16dp 宽、满高，位于最上层并贴合右边缘（标题栏与输入行均已避让）
                RightResizeHandle(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onResizeWidth = onResizeWidth,
                    onGestureEnd = onGestureEnd
                )
            }
        }
    }
}

/**
 * 顶部 48dp 工具条：左侧为「会话切换 + 折叠回球」，右侧为「回主页 + 关闭」，中间可拖动。
 *
 * 不显示会话名，四个按钮分布于两端，中间仅保留可拖动空白。窗口拖窄时按钮位置不变、
 * 可拖动区域优先缩减，左右内边距固定为 8dp 与 20dp（右侧 20dp 为调宽手柄留出空间），
 * 因此任意尺寸下均不会遮挡内容。
 */
@Composable
private fun TopBar(
    sessionsOpen: Boolean,
    onSessions: () -> Unit,
    onCollapse: () -> Unit,
    onHome: () -> Unit,
    onClose: () -> Unit,
    onMoveBy: (Float, Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    val iconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val activeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            // 右侧内边距为 20dp：右边缘 16dp 为调宽手柄的命中区，避免遮挡「✕」按钮
            .padding(start = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleToolButton(onClick = onSessions) {
            ListIcon(if (sessionsOpen) activeColor else iconColor)
        }
        Spacer(Modifier.width(6.dp))
        CircleToolButton(onClick = onCollapse) { ChevronDownIcon(iconColor) }
        // 可拖动区域：dragAmount 为布局 px（未使用 scale，等同于屏幕 px），原样传递给服务。
        // 不设置最小宽度：窗口拖至最窄时可拖动区域优先消失，按钮仍完整可见可点。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onGestureEnd() },
                        onDragCancel = { onGestureEnd() }
                    ) { _, dragAmount ->
                        onMoveBy(dragAmount.x, dragAmount.y)
                    }
                }
        )
        CircleToolButton(onClick = onHome) { HomeIcon(iconColor) }
        Spacer(Modifier.width(6.dp))
        CircleToolButton(onClick = onClose) { CloseIcon(iconColor) }
    }
}

/**
 * 会话切换面板：铺满消息区，列出未归档会话（按最近更新排序，无图标）。
 *
 * 使用页面底色而非浮层灰色：小窗尺寸较小，叠加半透明层会导致标题难以辨认；
 * 当前会话以强调色标记，点击其他行即切换并自动收起。
 */
@Composable
private fun SessionPickerPanel(
    sessions: List<ChatPortSession>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有其它会话",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(sessions.size, key = { sessions[it].id }) { index ->
                    val session = sessions[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .saltRowClickable(RoundedCornerShape(8.dp)) { onPick(session.id) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = session.title,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (session.isCurrent) onSurface else onSurface.copy(alpha = 0.62f)
                        )
                        if (session.isCurrent) {
                            Text(
                                text = "当前",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 右侧调宽手柄：命中区为 16dp 宽、满高，视觉为居中的 6×60dp 圆角竖条。
 *
 * 仅识别水平位移：dragAmount.x 原样交给服务，服务仅修改 lp.width，不改变 x/y，
 * 即左边缘固定、右边缘向右扩展（锚点左上角）。松手时才调用 onGestureEnd。
 */
@Composable
private fun RightResizeHandle(
    modifier: Modifier = Modifier,
    onResizeWidth: (Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    val barColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .width(RIGHT_HANDLE_HIT_W)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        onGestureEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        onGestureEnd()
                    }
                ) { _, dx -> onResizeWidth(dx) }
            },
        contentAlignment = Alignment.Center
    ) {
        HandleBar(
            modifier = Modifier.width(HANDLE_THIN).height(HANDLE_LONG),
            dragging = dragging,
            color = barColor
        )
    }
}

/**
 * 底部调高手柄：命中区为满宽、12dp 高，视觉为居中的 60×6dp 圆角横条。
 *
 * 仅识别竖直位移：dragAmount.y 原样交给服务，服务仅修改 lp.height，
 * 即上边缘固定、下边缘向下扩展（锚点左上角）。松手时才调用 onGestureEnd。
 */
@Composable
private fun BottomResizeHandle(
    onResizeHeight: (Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    val barColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BOTTOM_HANDLE_HIT_H)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        onGestureEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        onGestureEnd()
                    }
                ) { _, dy -> onResizeHeight(dy) }
            },
        contentAlignment = Alignment.Center
    ) {
        HandleBar(
            modifier = Modifier.width(HANDLE_LONG).height(HANDLE_THIN),
            dragging = dragging,
            color = barColor
        )
    }
}

/** 手柄视觉：底色 12%、描边 30%，拖动中加深至 22% 与 45%（提供简单的进度反馈）。 */
@Composable
private fun HandleBar(modifier: Modifier, dragging: Boolean, color: Color) {
    Box(
        modifier = modifier
            .background(
                color.copy(alpha = if (dragging) HANDLE_BG_DRAG else HANDLE_BG_IDLE),
                RoundedCornerShape(HANDLE_CORNER)
            )
            .border(
                1.dp,
                color.copy(alpha = if (dragging) HANDLE_BORDER_DRAG else HANDLE_BORDER_IDLE),
                RoundedCornerShape(HANDLE_CORNER)
            )
    )
}

/** 30dp 圆按钮（工具条上的按钮）。 */
@Composable
private fun CircleToolButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(TOOL_BUTTON_SIZE)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 会话列表图标：三条横线（上两条短、下一条长），展开面板时换成强调色。 */
@Composable
private fun ListIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val lines = listOf(0.24f to 0.78f, 0.50f to 0.78f, 0.76f to 0.56f)
        for ((y, endX) in lines) {
            drawLine(
                color = color,
                start = Offset(w * 0.14f, h * y),
                end = Offset(w * endX, h * y),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round
            )
        }
    }
}

/** 房子图标：屋顶折线与屋身方框，两条路径描边。 */
@Composable
private fun HomeIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val roof = Path().apply {
            moveTo(w * 0.12f, h * 0.46f)
            lineTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.88f, h * 0.46f)
        }
        val body = Path().apply {
            moveTo(w * 0.24f, h * 0.45f)
            lineTo(w * 0.24f, h * 0.86f)
            lineTo(w * 0.76f, h * 0.86f)
            lineTo(w * 0.76f, h * 0.45f)
        }
        drawPath(roof, color = color, style = stroke)
        drawPath(body, color = color, style = stroke)
    }
}

/** 向下箭头（折叠）。 */
@Composable
private fun ChevronDownIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.38f)
            lineTo(w * 0.50f, h * 0.64f)
            lineTo(w * 0.80f, h * 0.38f)
        }
        drawPath(path, color = color, style = stroke)
    }
}

/** 关闭（叉）。 */
@Composable
private fun CloseIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(w * 0.24f, h * 0.24f)
            lineTo(w * 0.76f, h * 0.76f)
            moveTo(w * 0.76f, h * 0.24f)
            lineTo(w * 0.24f, h * 0.76f)
        }
        drawPath(path, color = color, style = stroke)
    }
}

/**
 * 一条消息气泡：只读，使用与主页一致的 [MarkdownText]（代码块、列表、粗体表现相同）。
 *
 * 气泡占满左右宽度（左右各 12dp 内边距）：窗口拉宽后正文随之变宽。
 * 左右对齐已无意义（气泡占满整行），角色仅决定底色。
 *
 * 不使用带 `shape` 的 `Surface`：小窗可拉至较高尺寸，长回复的气泡可达数千像素，
 * 而 `Surface` 的裁剪图层达到该尺寸后，内部代码块的「缩进 / 复制」无法接收点击
 * （主页气泡同样存在该问题）。`Box + background(color, shape)` 视觉一致且不创建裁剪图层。
 *
 * 不使用 `maxLines` 截断：Markdown 内容按行截断会破坏代码块，因此改为按块折叠：
 * 超过当前可视高度时在整段末尾折叠，右下角显示与主页一致的 [FoldToggle]，
 * 展开与收起仅改变本气泡的高度。
 */
@Composable
private fun MessageBubble(message: ChatMessage, foldAt: Dp, foldedHeight: Dp) {
    val isUser = message.role == "user"
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 与主页气泡使用同一套配色：用户气泡为 Palette.BubbleUserLight／BubbleUserDark（画布上最亮），
    // AI 气泡为 Palette.BubbleAiLight／BubbleAiDark（较画布深一档）。窗口画布使用页面底色，
    // 因此可直接使用不透明气泡色，折叠遮罩也能完全覆盖最后一行。
    val bubbleColor = if (isUser) {
        if (isDark) Palette.BubbleUserDark else Palette.BubbleUserLight
    } else {
        if (isDark) Palette.BubbleAiDark else Palette.BubbleAiLight
    }
    // 展开状态仅在当前屏有效：滑出小窗视口后返回会重新折叠（与主页一致）
    var expanded by remember { mutableStateOf(false) }
    Box(
        // 先 fillMaxWidth 再 padding：底色与圆角绘制在内缩 12dp 的区域，气泡本身占满可用宽度
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            // 展开与收起仅改变气泡高度，不重新换行
            .animateContentSize(animationSpec = tween(180), alignment = Alignment.TopStart)
            .background(color = bubbleColor, shape = RoundedCornerShape(12.dp))
    ) {
        MarkdownText(
            text = message.content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            // 小窗较主页窄，正文字号相应缩小一级（代码块字号由 MarkdownText 按 0.88 等比缩放）
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
            // 用户手动换行按主页规则保留：用户气泡保留空行，AI 气泡合并连续空行
            keepBlankLines = isUser,
            // 折叠仅用于用户的长消息：AI 气泡与主页一致，始终完整展示、不折叠。
            // 不传阈值与按钮时，MarkdownText 使用「不限高」的直铺分支。
            foldAt = if (isUser) foldAt else Dp.Unspecified,
            foldedHeight = if (isUser) foldedHeight else Dp.Unspecified,
            expanded = expanded,
            foldColor = bubbleColor,
            // 按钮位置按小窗自身内边距（10 / 7dp）计算，否则会被推到气泡之外
            foldToggleInsetX = 10.dp,
            foldToggleInsetY = 7.dp,
            foldToggle = if (isUser) {
                { FoldToggle(expanded = expanded, onClick = { expanded = !expanded }) }
            } else {
                null
            }
        )
    }
}

/**
 * 底部输入行：BasicTextField 置于圆角 surface 面片内（无描边），右侧为圆形发送按钮。
 * 回车（IME 发送）与点击按钮两条路径均调用同一个 submit。
 */
@Composable
private fun InputRow(
    text: String,
    onTextChange: (String) -> Unit,
    canSend: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 右侧内边距为 20dp：右边缘 16dp 为调宽手柄的命中区，否则发送按钮右半部分无法点击
            .padding(start = 10.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = saltCursorBrush(),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "输入消息…",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.width(8.dp))
        // 发送按钮与主页 Composer 使用同一套配色：底色为 UiColors.accentFill（浅色 #4176E6、深色 #7AAAFF），
        // 图标为 UiColors.onAccent（浅色白、深色墨），不可发送时透明度降至 40%。
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (canSend) {
                        UiColors.accentFill
                    } else {
                        UiColors.accentFill.copy(alpha = 0.40f)
                    },
                    shape = CircleShape
                )
                .clickable(enabled = canSend, onClick = onSubmit),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "↑", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = UiColors.onAccent)
        }
    }
}
