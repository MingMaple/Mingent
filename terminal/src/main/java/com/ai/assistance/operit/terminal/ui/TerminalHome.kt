/*
 * 本文件源自 Operit AI 项目（https://github.com/AAswordman/Operit）。
 * Copyright (C) 2023-2024 Operit AI 项目团队
 *
 * 依 GNU Lesser General Public License v3.0 授权使用与分发，
 * 许可证全文见仓库根目录 terminal/LICENSE；Mingent 对其所做的修改
 * 同样以 LGPL-3.0 提供。
 */

package com.ai.assistance.operit.terminal.ui

import android.content.Context
import android.graphics.Typeface
import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalOutput
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import com.ai.assistance.operit.terminal.view.canvas.RenderConfig
import com.ai.assistance.operit.terminal.view.canvas.TerminalTabRenderItem
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.operit.terminal.TerminalEnv
import com.ai.assistance.operit.terminal.utils.TerminalFontConfigManager
import com.ai.assistance.operit.terminal.utils.VirtualKeyAction
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardButtonConfig
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardConfigManager
import com.ai.assistance.operit.terminal.view.SyntaxColors
import com.ai.assistance.operit.terminal.view.SyntaxHighlightingVisualTransformation
import com.ai.assistance.operit.terminal.view.highlight
import androidx.compose.material.icons.filled.Settings
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalHome(
    env: TerminalEnv,
    useLocalImeHandling: Boolean = true,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current
    val density = LocalDensity.current
    val fontConfigManager = remember { TerminalFontConfigManager.getInstance(context) }
    val virtualKeyboardConfigManager = remember { VirtualKeyboardConfigManager.getInstance(context) }
    
    // 字体配置状态
    var fontConfig by remember { 
        mutableStateOf(fontConfigManager.loadRenderConfig())
    }
    var virtualKeyboardLayout by remember {
        mutableStateOf(virtualKeyboardConfigManager.loadLayout())
    }
    
    // 监听字体配置变化（当从设置界面返回时）
    LaunchedEffect(Unit) {
        // 每次进入时重新读取配置
        fontConfig = fontConfigManager.loadRenderConfig()
    }
    
    // 当组件重新组合时，检查配置是否变化并更新
    DisposableEffect(Unit) {
        val newConfig = fontConfigManager.loadRenderConfig()
        
        if (fontConfig != newConfig) {
            fontConfig = newConfig
        }
        
        onDispose { }
    }

    DisposableEffect(context, virtualKeyboardConfigManager) {
        val settingsPrefs =
            context.getSharedPreferences(VirtualKeyboardConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == VirtualKeyboardConfigManager.PREF_KEY_VIRTUAL_KEYBOARD_LAYOUT) {
                virtualKeyboardLayout = virtualKeyboardConfigManager.loadLayout()
            }
        }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // 组合期只取 insets 的「对象」，绝不读数值：读数值会让键盘动画的每一帧都重组整个
    // TerminalHome（连带 AndroidView 的 update 每帧跑 setTabBarState()/refreshNow()），
    // 这是上一轮定位到的根因，必须保持住。
    val imeInsets = if (useLocalImeHandling) WindowInsets.ime else null
    val navigationBarsInsets = WindowInsets.navigationBars

    // 全屏分支的兼容口径：API 30 以下拿不到逐帧 insets 动画回调，只能沿用「组合期读一次
    // 高度」，那批设备上保持旧行为；API 30+ 一律不再读，交给画布自己跟手（见
    // CanvasTerminalView 的 WindowInsetsAnimation.Callback）。
    val legacyFullscreenImeBottomPx =
        if (useLocalImeHandling &&
            env.isFullscreen &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            WindowInsets.ime.getBottom(density)
        } else {
            0
        }

    // IME 处理策略（2026-10-22 之后又一轮）：
    // - 跟手位移全部下沉到「绘制/摆放层」：画布内部按逐帧 IME 高度平移自己绘制的内容
    //   （CanvasTerminalView.getTerminalVisualOffsetY），工具栏与虚拟键盘面板用
    //   Modifier.offset { } 在摆放阶段读 insets 平移。两者都不触发重组。
    // - 布局高度（= 终端行列数）只在键盘稳定后提交一次，动画期间绝不改 rows/cols，
    //   避免动画和 PTY resize/全量重排抢同一帧。
    // - 提交时画布内部同时把「已让位高度」从位移里扣掉，所以提交那一帧屏幕上内容不动。
    val settledImeBottomPx = rememberSettledImeBottomPx(imeInsets, navigationBarsInsets)

    // 命令输入框焦点控制
    val inputFocusRequester = remember { FocusRequester() }
    var pendingShowIme by remember { mutableStateOf(false) }

    LaunchedEffect(pendingShowIme) {
        if (pendingShowIme) {
            pendingShowIme = false
            // 模仿全屏模式，在焦点切换后稍微延迟再请求输入法
            delay(100)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(rootView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // 语法高亮
    val visualTransformation = remember { SyntaxHighlightingVisualTransformation() }

    // 缩放状态
    var scaleFactor by remember { mutableStateOf(1f) }

    // 删除确认弹窗状态
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }
    
    // 非全屏模式下虚拟键盘显示状态
    var showVirtualKeyboard by remember { mutableStateOf(false) }
    var isDirectInputMode by remember { mutableStateOf(false) }

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    // 首帧兜底：SurfaceView 第一次 attach 后窗口没有重新合成，画布要等一次外部事件（滑动/收起输入法）
    // 才显示。这里在进页后做一次 1dp 的尺寸微抖，逼 Surface 走一次真正的 resize，
    // 视觉上看不出来，但足以让首帧合成下来。
    var surfaceWarmup by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(400)
        surfaceWarmup = false
    }

    fun applyCtrlModifierChar(ch: Char): String? {
        return when {
            ch in 'a'..'z' || ch in 'A'..'Z' -> {
                val code = ch.uppercaseChar().code - 64
                code.toChar().toString()
            }
            ch == ' ' || ch == '@' -> "\u0000"
            ch == '[' -> "\u001b"
            ch == '\\' -> "\u001c"
            ch == ']' -> "\u001d"
            ch == '^' -> "\u001e"
            ch == '_' -> "\u001f"
            else -> null
        }
    }

    fun applyCtrlModifierToText(input: String): String {
        if (!ctrlActive) return input
        val sb = StringBuilder()
        input.forEach { ch ->
            val mapped = applyCtrlModifierChar(ch)
            if (mapped != null) sb.append(mapped) else sb.append(ch)
        }
        return sb.toString()
    }

    fun applyModifiers(input: String): String {
        var output = applyCtrlModifierToText(input)
        if (altActive) {
            output = if (output.startsWith("\u001b")) output else "\u001b$output"
        }
        return output
    }

    fun consumeModifiers() {
        if (ctrlActive) ctrlActive = false
        if (altActive) altActive = false
    }

    fun decodeVirtualKeyValue(rawValue: String): String {
        val output = StringBuilder()
        var index = 0

        while (index < rawValue.length) {
            val char = rawValue[index]
            if (char == '\\' && index + 1 < rawValue.length) {
                val next = rawValue[index + 1]
                when (next) {
                    'e' -> output.append('\u001b')
                    't' -> output.append('\t')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    '\\' -> output.append('\\')
                    else -> {
                        output.append('\\')
                        output.append(next)
                    }
                }
                index += 2
                continue
            }

            output.append(char)
            index++
        }

        return output.toString()
    }

    fun sendDirectInput(input: String) {
        val output = if (ctrlActive || altActive) applyModifiers(input) else input
        env.onSendInput(output, false)
        if (ctrlActive || altActive) {
            consumeModifiers()
        }
    }

    fun sendCommandFromInput() {
        val commandText = env.command
        if (ctrlActive || altActive) {
            val output = applyModifiers(commandText)
            env.onSendInput(output, false)
            env.onCommandChange("")
            consumeModifiers()
        } else {
            env.onSendInput(commandText, true)
        }
    }

    fun toggleDirectInputMode() {
        isDirectInputMode = !isDirectInputMode
        if (isDirectInputMode) {
            // 进入直接输入模式：展开虚拟键盘，清空命令并收起系统键盘
            showVirtualKeyboard = true
            env.onCommandChange("")
            keyboardController?.hide()
        } else {
            // 退出直接输入模式：关闭虚拟键盘面板并恢复系统键盘
            showVirtualKeyboard = false
            keyboardController?.show()
        }
    }

    // 计算基于缩放因子的字体大小和间距
    val baseFontSize = 14.sp
    val fontSize = with(LocalDensity.current) {
        (baseFontSize.toPx() * scaleFactor).toSp()
    }
    val basePadding = 8.dp
    val padding = basePadding * scaleFactor

    // 获取当前 session 的 PTY
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }
    val tabItems = remember(env.sessions) {
        val closable = env.sessions.size > 1
        env.sessions.map { session ->
            TerminalTabRenderItem(
                id = session.id,
                title = session.title,
                canClose = closable
            )
        }
    }
    val onTabCloseRequest: (String) -> Unit = { sessionId ->
        if (env.sessions.size > 1) {
            sessionToDelete = sessionId
            showDeleteConfirmDialog = true
        }
    }

    // 注意：这里不要再给根容器铺不透明黑底。画布是 SurfaceView（独立 layer + 挖洞合成），
    // 上层一旦有铺满的不透明背景，挖洞就失效，屏幕上只剩黑，直到某个窗口事件让它重新合成。
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        if (env.isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // 终端输出区域
                CanvasTerminalScreen(
                    emulator = env.terminalEmulator,
                    modifier = Modifier.weight(1f),
                    config = fontConfig,
                    pty = currentPty,
                    imeAnimationOffsetPx = legacyFullscreenImeBottomPx,
                    committedImeBottomInsetPx = 0,
                    imeFollowEnabled = useLocalImeHandling,
                    onInput = { sendDirectInput(it) },
                    sessionId = env.currentSessionId,
                    onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                    getScrollOffset = { id -> env.getScrollOffset(id) },
                    tabs = tabItems,
                    currentTabId = env.currentSessionId,
                    onTabClick = env::onSwitchSession,
                    onTabClose = onTabCloseRequest,
                    onNewTab = env::onNewSession
                )

                Box(
                    modifier = Modifier.followImeOffset(imeInsets, navigationBarsInsets)
                ) {
                    VirtualKeyboard(
                        onKeyPress = { key -> sendDirectInput(decodeVirtualKeyValue(key)) },
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleAlt = { altActive = !altActive },
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        keyRows = virtualKeyboardLayout.rows,
                        fontSize = fontSize * 0.7f,
                        padding = padding * 0.5f
                    )
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        // 注意：这里不再用 padding 让位。布局高度一旦随键盘变化，就是
                        // onSizeChanged → 终端行数变化 → emulator.resize 全量重排 + PTY SIGWINCH，
                        // 和键盘动画抢同一帧。改成「布局不动，画布内部让位 + 工具栏摆放层平移」。
            ) {
                // Canvas输出区域（占满剩余空间）
                if (isDirectInputMode) {
                    // 直接输入模式：使用与全屏相同的 CanvasTerminalScreen，点击画布时由 CanvasTerminalView 自己弹出输入法
                    CanvasTerminalScreen(
                        emulator = env.terminalEmulator,
                        modifier = Modifier.weight(1f),
                        config = fontConfig,
                        pty = currentPty,
                        // 让位交给画布自己：committedImeBottomInsetPx = 稳定后的键盘高度（视口让位），
                        // 逐帧跟手位移由 CanvasTerminalView 自己按 insets 动画算，这里不传。
                        imeAnimationOffsetPx = 0,
                        committedImeBottomInsetPx = settledImeBottomPx,
                        imeFollowEnabled = useLocalImeHandling,
                        onInput = { sendDirectInput(it) },
                        sessionId = env.currentSessionId,
                        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                        getScrollOffset = { id -> env.getScrollOffset(id) },
                        tabs = tabItems,
                        currentTabId = env.currentSessionId,
                        onTabClick = env::onSwitchSession,
                        onTabClose = onTabCloseRequest,
                        onNewTab = env::onNewSession
                    )
                } else {
                    // 嵌入聊天页（useLocalImeHandling = false）时 settled 恒为 0，行为与旧版一致；
                    // 独立终端页则由画布内部按键盘高度让位 + 逐帧跟手（不改布局、不改行数）。
                    CanvasTerminalOutput(
                        emulator = env.terminalEmulator,
                        // 这里只留 1dp 的首次微抖，用于逼 Surface 走一次真实 resize（首帧合成兜底）。
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = if (surfaceWarmup) 1.dp else 0.dp),
                        config = fontConfig,
                        pty = currentPty,
                        imeAnimationOffsetPx = 0,
                        committedImeBottomInsetPx = settledImeBottomPx,
                        imeFollowEnabled = useLocalImeHandling,
                        onRequestShowKeyboard = {
                            inputFocusRequester.requestFocus()
                            pendingShowIme = true
                        },
                        sessionId = env.currentSessionId,
                        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                        getScrollOffset = { id -> env.getScrollOffset(id) },
                        tabs = tabItems,
                        currentTabId = env.currentSessionId,
                        onTabClick = env::onSwitchSession,
                        onTabClose = onTabCloseRequest,
                        onNewTab = env::onNewSession
                    )
                }

                // 工具栏 + 输入行：布局上仍停在这一列底部，实际位置由 followImeOffset 在
                // 摆放阶段平移，始终贴住键盘顶边。用 offset 而不是 graphicsLayer，是因为
                // 平移量会一直保留（键盘收起时才归零），必须让点击/无障碍命中也一起跟过去。
                Column(
                    modifier =
                        Modifier
                            .background(Color.Black)
                            .followImeOffset(imeInsets, navigationBarsInsets)
                ) {
                    // 终端工具栏
                    TerminalToolbar(
                        onInterrupt = env::onInterrupt,
                        onSendCommand = { env.onSendInput(it, true) },
                        fontSize = fontSize * 0.8f,
                        padding = padding,
                        onNavigateToSetup = onNavigateToSetup,
                        onNavigateToSettings = onNavigateToSettings,
                        isDirectInputMode = isDirectInputMode,
                        showVirtualKeyboard = showVirtualKeyboard,
                        onToggleVirtualKeyboard = { showVirtualKeyboard = !showVirtualKeyboard },
                        onToggleInputMode = { toggleDirectInputMode() }
                    )

                    // 当前输入行：直接输入映射模式下隐藏（按图示仅保留工具栏右侧快捷按钮）
                    if (!isDirectInputMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(padding),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.padding(end = padding * 0.5f),
                                color = Color(0xFF006400), // DarkGreen
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = getTruncatedPrompt(env.currentDirectory.ifEmpty { "$ " }),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize,
                                    modifier = Modifier.padding(horizontal = padding * 0.5f, vertical = padding * 0.1f)
                                )
                            }
                            BasicTextField(
                                value = env.command,
                                onValueChange = env::onCommandChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(inputFocusRequester),
                                enabled = true,
                                textStyle = TextStyle(
                                    color = SyntaxColors.commandDefault,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize
                                ),
                                cursorBrush = SolidColor(Color.Green),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    sendCommandFromInput()
                                })
                            )
                            Surface(
                                modifier = Modifier
                                    .padding(start = padding * 0.5f)
                                    .clickable { showVirtualKeyboard = !showVirtualKeyboard },
                                color = if (showVirtualKeyboard) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "⌨",
                                    color = Color.White,
                                    fontFamily = FontFamily.Default,
                                    fontSize = fontSize * 1.2f,
                                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .padding(start = padding * 0.5f)
                                    .clickable { toggleDirectInputMode() },
                                color = if (isDirectInputMode) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "⇄",
                                    color = Color.White,
                                    fontFamily = FontFamily.Default,
                                    fontSize = fontSize * 1.1f,
                                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                                )
                            }
                        }
                    }

                    if (showVirtualKeyboard) {
                        VirtualKeyboard(
                            onKeyPress = { key -> sendDirectInput(decodeVirtualKeyValue(key)) },
                            onToggleCtrl = { ctrlActive = !ctrlActive },
                            onToggleAlt = { altActive = !altActive },
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            keyRows = virtualKeyboardLayout.rows,
                            fontSize = fontSize * 0.7f,
                            padding = padding * 0.5f
                        )
                    }
                }
            }
        }
    }

    // 删除确认弹窗
    if (showDeleteConfirmDialog && sessionToDelete != null) {
        val context = LocalContext.current
        val sessionTitle = env.sessions.find { it.id == sessionToDelete }?.title ?: context.getString(com.ai.assistance.operit.terminal.R.string.unknown_session)

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                sessionToDelete = null
            },
            title = {
                Text(
                    text = context.getString(com.ai.assistance.operit.terminal.R.string.confirm_delete_session),
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = context.getString(com.ai.assistance.operit.terminal.R.string.delete_session_message, sessionTitle),
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { sessionId ->
                            env.onCloseSession(sessionId)
                        }
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.delete),
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.cancel),
                        color = Color.White
                    )
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }
}

/**
 * 让某个可组合元素在「摆放阶段」贴住键盘顶边：键盘抬高多少，它就被推上去多少。
 *
 * 关键点：inset 的数值是在 Modifier.offset 的 lambda 里读的，也就是摆放阶段，
 * 不是组合阶段。键盘动画期间每帧只会让这一小块重新摆放（不重组、不重新测量整棵树），
 * 而且平移量会反映到点击命中与无障碍边界上（用 graphicsLayer.translationY 就做不到，
 * 因为键盘保持打开时平移量是常驻的）。
 */
private fun Modifier.followImeOffset(
    imeInsets: WindowInsets?,
    navigationBarsInsets: WindowInsets
): Modifier = this.offset {
    val rawIme = imeInsets?.getBottom(this) ?: 0
    val navigation = navigationBarsInsets.getBottom(this)
    val currentIme = (rawIme - navigation).coerceAtLeast(0)
    IntOffset(0, -currentIme)
}

/**
 * 把 IME 高度「沉降」后再交给画布：只在键盘真正稳定后返回一次新值，用来改终端行列数。
 *
 * 1. inset 的读取放在 snapshotFlow 里（组合之外）。组合期直接读 WindowInsets.ime 的数值会让
 *    键盘动画的每一帧都触发整页重组，那是卡顿的根因；这里只在沉降值变化时重组（每次手势 ≤ 2 次）。
 * 2. 用 collectLatest 做「短延迟 + 值相等」判断：新值一来就取消上一次等待，等价于
 *    「连续 delay 不变化才提交」。所以动画进行中的任何一帧都不会触发 emulator.resize / PTY 重设。
 * 3. 收起方向也走同一条延迟路径（只是延迟更短）：以前收起时立刻释放布局，那一帧正好落在
 *    键盘回落动画的第一帧上，终端要重排 + 重设 PTY + 全量重绘，动画自然卡一下。
 *    现在收起期间内容由绘制层保持不动，等动画结束再提交新行数。
 */
@Composable
private fun rememberSettledImeBottomPx(
    imeInsets: WindowInsets?,
    navigationBarsInsets: WindowInsets,
    expandSettleDelayMs: Long = 100L,
    collapseSettleDelayMs: Long = 64L
): Int {
    val density = LocalDensity.current
    var settledBottomPx by remember { mutableStateOf(0) }

    LaunchedEffect(imeInsets, navigationBarsInsets, density, expandSettleDelayMs, collapseSettleDelayMs) {
        val activeImeInsets = imeInsets
        if (activeImeInsets == null) {
            settledBottomPx = 0
            return@LaunchedEffect
        }

        snapshotFlow {
            val rawIme = activeImeInsets.getBottom(density)
            val navigation = navigationBarsInsets.getBottom(density)
            (rawIme - navigation).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            // (a') Compose 侧拿到的 ime 值是不是逐帧在变（也用来验证工具栏跟手的同一份 insets）：
            // 每次发射记一条，条数≈动画帧数；若整个动画只发射 2 次，说明组合层拿到的是跳变值。
            .onEach { value ->
                Log.e("IMEDBG", System.currentTimeMillis().toString() + " home.imeFlow target=" + value + "px")
            }
            .collectLatest { target ->
                // 连续 delay 没有新值才提交；期间来了新值，collectLatest 会取消这次等待
                delay(if (target >= settledBottomPx) expandSettleDelayMs else collapseSettleDelayMs)
                if (target != settledBottomPx) {
                    val previous = settledBottomPx
                    settledBottomPx = target
                    // (c) 沉降提交现场：本行（连同上一条 resize 日志）用来回答「什么时候才真正改行列数」
                    Log.e(
                        "IMEDBG",
                        System.currentTimeMillis().toString() +
                            " home.settled " + previous + "px->" + target + "px" +
                            " delayMs=" + (if (target >= previous) expandSettleDelayMs else collapseSettleDelayMs)
                    )
                }
            }
    }

    return settledBottomPx
}

private fun getTruncatedPrompt(prompt: String, maxLength: Int = 16): String {
    val trimmed = prompt.trimEnd()
    return if (trimmed.length > maxLength) {
        "..." + trimmed.takeLast(maxLength - 3)
    } else {
        trimmed
    }
}

@Composable
private fun TerminalToolbar(
    onInterrupt: () -> Unit,
    onSendCommand: (String) -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit,
    isDirectInputMode: Boolean,
    showVirtualKeyboard: Boolean,
    onToggleVirtualKeyboard: () -> Unit,
    onToggleInputMode: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(padding * 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isDirectInputMode) {
                // Ctrl+C 中断按钮
                Surface(
                    modifier = Modifier.clickable { onInterrupt() },
                    color = Color(0xFF4A4A4A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(padding * 0.3f)
                    ) {
                        Text(
                            text = "Ctrl+C",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = context.getString(com.ai.assistance.operit.terminal.R.string.interrupt),
                            color = Color.Gray,
                            fontFamily = FontFamily.Default,
                            fontSize = fontSize * 0.9f
                        )
                    }
                }

                // 分隔线
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(padding * 1.5f)
                        .background(Color(0xFF3A3A3A))
                )
            }

            Spacer(Modifier.weight(1f))

            // 主页：回到 App 的对话页（终端是独立 Activity，finish 就是回主页）
            Surface(
                modifier = Modifier.clickable {
                    // 终端页可能被 Embedded 在别处，找不到 Activity 就什么都不做
                    var ctx: android.content.Context? = context
                    while (ctx is android.content.ContextWrapper && ctx !is Activity) {
                        ctx = ctx.baseContext
                    }
                    (ctx as? Activity)?.finish()
                },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "主页",
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 环境配置按钮
            Surface(
                modifier = Modifier.clickable { onNavigateToSetup() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.environment_setup),
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (isDirectInputMode) {
                Surface(
                    modifier = Modifier.clickable { onToggleVirtualKeyboard() },
                    color = if (showVirtualKeyboard) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "⌨",
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 1.15f,
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.35f)
                    )
                }
                Surface(
                    modifier = Modifier.clickable { onToggleInputMode() },
                    color = Color(0xFF4A4A4A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "⇄",
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 1.05f,
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                    )
                }
            }

            // 设置按钮
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.settings),
                tint = Color.Gray,
                modifier = Modifier
                    .clickable { onNavigateToSettings() }
                    .padding(start = padding)
                    .size(padding * 2.5f)
            )
        }
    }
}

@Composable
private fun VirtualKeyboard(
    onKeyPress: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    keyRows: List<List<VirtualKeyboardButtonConfig>>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            verticalArrangement = Arrangement.spacedBy(padding * 0.5f)
        ) {
            keyRows.forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
                ) {
                    rowKeys.forEach { keyConfig ->
                        val isActive = when (keyConfig.action) {
                            VirtualKeyAction.TOGGLE_CTRL -> ctrlActive
                            VirtualKeyAction.TOGGLE_ALT -> altActive
                            VirtualKeyAction.SEND_TEXT -> false
                        }
                        val clickOverride = when (keyConfig.action) {
                            VirtualKeyAction.TOGGLE_CTRL -> onToggleCtrl
                            VirtualKeyAction.TOGGLE_ALT -> onToggleAlt
                            VirtualKeyAction.SEND_TEXT -> null
                        }

                        KeyButton(
                            label = keyConfig.label,
                            key = keyConfig.value,
                            fontSize = fontSize,
                            padding = padding,
                            onKeyPress = onKeyPress,
                            modifier = Modifier.weight(1f),
                            isActive = isActive,
                            onClickOverride = clickOverride
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    key: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onClickOverride: (() -> Unit)? = null
) {
    val backgroundColor = if (isActive) Color(0xFF2563EB) else Color(0xFF3A3A3A)
    Surface(
        modifier = modifier
            .clickable { onClickOverride?.invoke() ?: onKeyPress(key) },
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = padding * 0.5f, vertical = padding * 0.8f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
} 
