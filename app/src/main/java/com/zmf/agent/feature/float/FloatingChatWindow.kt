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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.SolidColor
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
import com.zmf.agent.uikit.common.saltCursorBrush
import com.zmf.agent.uikit.markdown.MarkdownText

/** 小窗只读展示最近多少条消息（老消息不铺进 LazyColumn，省内存也省布局）。 */
private const val MAX_SHOWN = 80

/**
 * 小窗折叠：正文完整高度超过**可视高**的这个比例才折。
 *
 * 用比例而不是写死的 dp：小窗的宽高由用户随便拖，同一段话在拉高的窗口里该整段铺开、
 * 在压矮的窗口里才该折起来。主页按屏幕 45% 折，这里放宽到 62%：小窗本来就矮，
 * 折得太早会只剩个标题，反而要看两遍。
 */
private const val FLOAT_FOLD_AT_RATIO = 0.62f

/** 折叠态最多占可视高的比例：窗口压矮时跟着收，拉高时自动摊开。 */
private const val FLOAT_FOLDED_RATIO = 0.42f

/** 小窗整体留给阴影的内缩：窗口是服务给的精确像素（默认 240x320dp），阴影得画在窗口里面才看得见。 */
private val SHADOW_PADDING = 5.dp

/** 小窗圆角：12dp。 */
private val WINDOW_CORNER = 12.dp

/** 顶部工具条高度：48dp。 */
private val TOP_BAR_HEIGHT = 48.dp

/** 工具条圆按钮直径：30dp。 */
private val TOOL_BUTTON_SIZE = 30.dp

/** 右侧调宽手柄：命中区 16dp 宽 × 满高（窗内，贴右边缘）。 */
private val RIGHT_HANDLE_HIT_W = 16.dp

/** 底部调高手柄：命中区 满宽 × 12dp（窗内，贴底边；占位在输入行下方，不叠在输入行上）。 */
private val BOTTOM_HANDLE_HIT_H = 12.dp

/** 手柄视觉：长边 60dp、短边 6dp、圆角 3dp（右侧竖条 / 底部横条共用）。 */
private val HANDLE_LONG = 60.dp
private val HANDLE_THIN = 6.dp
private val HANDLE_CORNER = 3.dp

/** 手柄配色：空闲 12% 底 + 30% 描边，拖动中加深到 22% / 45%（一点进度感）。 */
private const val HANDLE_BG_IDLE = 0.12f
private const val HANDLE_BG_DRAG = 0.22f
private const val HANDLE_BORDER_IDLE = 0.30f
private const val HANDLE_BORDER_DRAG = 0.45f

/**
 * 输入框焦点变化的对外钩子。
 *
 * 服务侧只用它做一件事：输入框**真正获得焦点**的那一刻请求一次 IME（200ms 防抖）。
 * 窗口的 flags 在展开时就一次性设成可获焦，**不随焦点变化**：上一版正是这里
 * 反复改 flags，窗口一失焦系统就收键盘、代码又去 showSoftInput，键盘来回闪。
 * Composable 的形参是定死的，所以用一个 CompositionLocal 把这件事回传给服务，
 * 默认实现是空操作（单独预览也不会崩）。
 */
val LocalFloatInputFocus = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * 系统小窗里的对话面板（尺寸由服务给，默认 240x320dp，圆角 12dp，八级阴影）。
 *
 * 顶部 48dp 工具条按参考实现分两端：左边「会话切换 + 折叠回球」，
 * 右边「回主页 + 关闭」，中间一条可拖空白；**不再显示会话名**。
 * 会话切换点开后面板铺满消息区，列出未归档会话（无图标，当前会话标「当前」）。
 * 中间只读展示当前会话消息，底部一行输入（surface 面片 + 圆角，**不描白边**），
 * 回车 / 点发送都走 [onSend]。
 *
 * 图标：工程里没有 material-icons 依赖（也不许新增），所以四个图标用 Canvas 手画，
 * 不新增任何资源文件，任何系统字体下都能渲染。
 *
 * 手势（锚点都是窗口左上角，热区与换算跟 Operit 的逆向结论一致）：
 * - 移动：**只有左右按钮组之间那块空白**能拖（[onMoveBy]，位移直接透传 px）；
 * - 调宽：右边缘一条 16dp 宽 × 满高的竖条（[onResizeWidth]），只改宽、x 不动；
 * - 调高：底边一条 满宽 × 12dp 的横条（[onResizeHeight]），在输入行下方，只改高、y 不动；
 * - 松手：[onGestureEnd] 告诉服务「这一段手势结束」，服务只在这时落盘一次。
 *
 * 尺寸自适应：内距固定 8dp / 20dp（右侧给调宽手柄让位），按钮组固定宽度、
 * 中间可拖区吃剩余宽度且没有最小宽度——窗口拖窄到极限时先让出可拖区，按钮始终完整可点。
 *
 * 这里仍然只负责画和把手指位移换算成增量，真正改窗口参数 / 夹取 / 落盘全在
 * FloatingBallService 里。
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
    // 会话切换面板是否展开：铺在消息区上，点某条会话即切换并收起
    var sessionPanel by remember { mutableStateOf(false) }
    // 小窗不在 Activity 里，不用 rememberLazyListState（它走 rememberSaveable，需要 SavedStateRegistryOwner）
    val listState = remember { LazyListState() }
    // 消息区当前尺寸（px）：拖底边改窗口高度 / 拖右边改宽度都会让它变，用来触发一次「贴底」。
    // 存 IntSize 而不是单个高度：窗口拉宽会让长文本重新折行、最后一条的位置也会变，
    // 两维都参与才叫「窗口尺寸变了就重新贴底」。
    var messageAreaSize by remember { mutableStateOf(IntSize.Zero) }

    // 只挑用户/AI 两种正常气泡：工具消息、系统提示在小窗里没地方展开，直接跳过
    val shown = ArrayList<ChatMessage>()
    for (message in messages) {
        if ((message.role == "user" || message.role == "assistant") && message.content.isNotBlank()) {
            shown.add(message)
        }
    }
    val visible = if (shown.size > MAX_SHOWN) shown.subList(shown.size - MAX_SHOWN, shown.size) else shown

    /*
     * 贴底：两个触发条件共用这一段。
     * 1) 消息条数变了：新回复进来，小窗就这么点高，不跟到底等于看不见最新回复；
     * 2) 消息区尺寸变了：拖底边把窗口往下压时视口变矮，原来的滚动量不再贴底，
     *    消息会卡在上一格、不跟输入框一起上移。尺寸变化后重新「滚到最后一项」，
     *    LazyListState 会把这次滚动夹到内容末尾，视觉上就是最后一条贴着输入框往上走。
     * 触发点在 LazyColumn 的 onSizeChanged（布局阶段写 state，重组后这个 effect 才跑，不抢布局）。
     */
    LaunchedEffect(visible.size, messageAreaSize) {
        if (visible.isNotEmpty()) runCatching { listState.scrollToItem(visible.lastIndex) }
    }
    // 小窗被收起/销毁时补一次「失焦」：否则窗口会一直以为输入框还占着焦点，收起后还抢键盘
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

    // 内缩 SHADOW_PADDING：阴影画在窗口里，贴着窗口边缘会被裁掉
    Box(Modifier.fillMaxSize().padding(SHADOW_PADDING)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(WINDOW_CORNER), clip = false),
            shape = RoundedCornerShape(WINDOW_CORNER),
            // 画布用**页面底色**而不是 surface：surface 正好等于 AI 气泡色，
            // 那样气泡会糊在背景里。换成页面底色后，小窗里的三层（底 / AI 气泡 / 用户气泡）
            // 与主页对话页是同一套层次。
            color = MaterialTheme.colorScheme.background
        ) {
            // 内容 + 两个贴边手柄：手柄画在窗口**里面**，贴着「会动的那条边」
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    // 标题栏固定 48dp、输入行 + 底部手柄贴在下面、中间那块 weight(1f) 吃满剩余高度：
                    // 窗口变宽变高时只有中间的消息区变大，标题栏 / 输入行不会被压缩
                    TopBar(
                        sessionsOpen = sessionPanel,
                        onSessions = { sessionPanel = !sessionPanel },
                        onCollapse = onCollapse,
                        onHome = onHome,
                        onClose = onClose,
                        onMoveBy = onMoveBy,
                        onGestureEnd = onGestureEnd
                    )

                    // 消息区：会话切换面板铺在它上面（同一块区域，切完自动收起）
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
                        // 折叠阈值按**当前可视高**现算：窗口是用户随便拖的，同一段话
                        // 拉高时摊开、压矮时才折，写死的 dp 在很矮的窗口里会一折到底。
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val foldAt = maxHeight * FLOAT_FOLD_AT_RATIO
                            val foldedHeight = maxHeight * FLOAT_FOLDED_RATIO
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    // 尺寸一变就记下来（同一个值写进去不会触发重组），交给上面的贴底 effect
                                    .onSizeChanged { messageAreaSize = it },
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                items(visible.size) { index ->
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

                    // 底部调高手柄：在输入行**下方**独占 12dp（窗口内最底部），不叠在输入行上，
                    // 免得点发送按钮的下沿被误判成 resize
                    BottomResizeHandle(
                        onResizeHeight = onResizeHeight,
                        onGestureEnd = onGestureEnd
                    )
                }

                // 右侧调宽手柄：16dp 宽 × 满高，叠在最上层、贴右边缘（标题栏/输入行都让开了它）
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
 * 顶部 48dp 工具条：左「会话切换 + 转悬浮窗（折叠回球）」、右「回主页 + 关闭」，中间可拖。
 *
 * 按参考实现重排（2026-10-31）：**不再显示会话名**，四个按钮平均贴两端，
 * 中间只剩一条可拖空白。窗口被拖窄时按钮不动、可拖区先被吃掉，
 * 左右内距固定 8dp / 20dp（右侧 20dp 是给调宽手柄让位），所以任意尺寸下都不会压到内容。
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
    val activeColor = Palette.AccentGreen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            // end 让到 20dp：右边缘 16dp 是调宽手柄的命中区，别让它盖住「✕」按钮
            .padding(start = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleToolButton(onClick = onSessions) {
            ListIcon(if (sessionsOpen) activeColor else iconColor)
        }
        Spacer(Modifier.width(6.dp))
        CircleToolButton(onClick = onCollapse) { ChevronDownIcon(iconColor) }
        // 可拖区：dragAmount 是布局 px（我们没有 scale，等于屏幕 px），原样透传给服务。
        // 不给最小宽度：窗口拖窄到极限时可拖区先消失，按钮仍然完整可见可点。
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
 * 用页面底色而不是浮层灰色：小窗本身很小，再叠一层半透明会看不清标题；
 * 当前会话用强调色标出来，点其它行即切换并自动收起。
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
                items(sessions.size) { index ->
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
                            color = if (session.isCurrent) Palette.AccentGreen else onSurface
                        )
                        if (session.isCurrent) {
                            Text(
                                text = "当前",
                                fontSize = 10.sp,
                                color = Palette.AccentGreen.copy(alpha = 0.7f),
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
 * 右侧调宽手柄：命中区 16dp 宽 × 满高，视觉一条 6×60dp 圆角竖条（居中）。
 *
 * 只认水平位移：dragAmount.x 原样交给服务，服务只改 lp.width、绝不碰 x/y
 * ⇒ 左边缘固定、右边缘向右长（锚点左上角）。松手才调 onGestureEnd。
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
 * 底部调高手柄：命中区 满宽 × 12dp，视觉一条 60×6dp 圆角横条（居中）。
 *
 * 只认竖直位移：dragAmount.y 原样交给服务，服务只改 lp.height
 * ⇒ 上边缘固定、下边缘向下长（锚点左上角）。松手才调 onGestureEnd。
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

/** 手柄视觉：底色 12% + 30% 描边，拖动中加深到 22% / 45%（简单的一档进度感）。 */
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

/** 30dp 圆按钮（工具条上的三个）。 */
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

/** 房子图标：屋顶折线 + 屋身方框，两条路径描边。 */
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
 * 一条消息气泡：只读、走**和主页同一套** [MarkdownText]（代码块、列表、粗体都一样）。
 *
 * 气泡**占满左右**（左右各 12dp 内边距）：窗口拉宽以后正文要跟着一起变宽。
 * 上一版有两处把它钉死在了旧尺寸上：外层 widthIn(max = 215dp) 卡着宽度上限、
 * 内层 Row 的 Arrangement 按角色靠左/靠右，于是窗口变宽后右边空一大截。
 * 现在左右对齐没有意义了（气泡已经占满整行），角色只决定底色。
 *
 * 不用带 `shape` 的 `Surface`：小窗能拉得很高，一条长回复的气泡也会长到几千像素，
 * 而 `Surface` 的裁剪图层长到那个尺寸后，里面代码块的「缩进 / 复制」就收不到点击
 * （主页的气泡也踩过同一个坑：长到几千像素后，里面代码块的按钮收不到点击）。
 * `Box + background(color, shape)` 视觉一致、但不建裁剪图层。
 *
 * 原先这里写死 `maxLines = 14` 省略超长回复；改用 Markdown 后不能按行截断
 * （截在一半会把代码块切坏），改为**按块折叠**：超过当前可视高就到整段末尾折起来，
 * 右下角压一枚和主页同一款的 [FoldToggle]，展开 / 收起只动本气泡的高度。
 */
@Composable
private fun MessageBubble(message: ChatMessage, foldAt: Dp, foldedHeight: Dp) {
    val isUser = message.role == "user"
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 与主页气泡同一套配色：用户气泡 Palette.BubbleUser*（最亮）、AI 气泡 Palette.BubbleAi*
    // （比画布深一档）。窗口画布也换成了页面底色，所以这里直接用不透明的气泡色即可，
    // 折叠遮罩也能真的盖住最后一行。小窗原来用户气泡是 primary 的 14%：主题 primary 是
    // AccentBlue，整屏单色里就那两块是浅蓝，跟全局配色对不上。
    val bubbleColor = if (isUser) {
        if (isDark) Palette.BubbleUserDark else Palette.BubbleUserLight
    } else {
        if (isDark) Palette.BubbleAiDark else Palette.BubbleAiLight
    }
    // 展开态只活在这一屏：滑出小窗视口再回来重新折起（与主页口径一致）
    var expanded by remember { mutableStateOf(false) }
    Box(
        // fillMaxWidth 之后 padding：底色/圆角画在内缩 12dp 的区域里，气泡本身吃满可用宽度
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            // 展开 / 收起只动气泡高度，不重新折行
            .animateContentSize(animationSpec = tween(180), alignment = Alignment.TopStart)
            .background(color = bubbleColor, shape = RoundedCornerShape(12.dp))
    ) {
        MarkdownText(
            text = message.content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            // 小窗比主页窄，正文字号跟着收一档（代码块字号由 MarkdownText 按 0.88 等比缩）
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
            // 用户自己排的换行照主页口径保留：用户气泡留空行，AI 气泡收起连续空行
            keepBlankLines = isUser,
            // 折叠只服务用户的长消息：AI 气泡与主页口径一致，始终整段铺开、永不折叠。
            // 不传阈值与按钮，MarkdownText 就走「不限高」的直铺分支。
            foldAt = if (isUser) foldAt else Dp.Unspecified,
            foldedHeight = if (isUser) foldedHeight else Dp.Unspecified,
            expanded = expanded,
            foldColor = bubbleColor,
            // 按钮落点按小窗自己的内边距（10 / 7dp）算，否则会被推到气泡外面
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
 * 底部一行输入：BasicTextField 装在圆角 surface 面片里（无描边），右边一个圆形发送按钮。
 * 回车（IME 的发送）与点按钮两条路径都落到同一个 submit。
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
            // end 让到 20dp：右边缘 16dp 是调宽手柄的命中区，不然发送按钮右半截会按不动
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
        // 发送键跟主页 Composer 同一套配色：底色 Palette.AccentGreen（本主题的强调色是
        // 灰阶单色，不是 Material 的 primary 蓝），图标用 Palette.LightText，不可发时压到 35%。
        // 小窗原来直接刷 AccentBlue，是整屏里唯一一块饱和蓝，跟全局配色对不上。
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (canSend) {
                        Palette.AccentGreen
                    } else {
                        Palette.AccentGreen.copy(alpha = 0.35f)
                    },
                    shape = CircleShape
                )
                .clickable(enabled = canSend, onClick = onSubmit),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "↑", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.LightText)
        }
    }
}
