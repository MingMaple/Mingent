/*
 * 本文件源自 Operit AI 项目（https://github.com/AAswordman/Operit）。
 * Copyright (C) 2023-2024 Operit AI 项目团队
 *
 * 依 GNU Lesser General Public License v3.0 授权使用与分发，
 * 许可证全文见仓库根目录 terminal/LICENSE；Mingent 对其所做的修改
 * 同样以 LGPL-3.0 提供。
 */

package com.ai.assistance.operit.terminal.view.canvas

import android.view.MotionEvent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

/**
 * Compose集成桥接
 * 将CanvasTerminalView包装为Compose组件
 */
@Composable
fun CanvasTerminalScreen(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.ai.assistance.operit.terminal.Pty? = null,
    imeAnimationOffsetPx: Int = 0,
    committedImeBottomInsetPx: Int = 0,
    // 由画布自己按 IME 动画跟手（宿主自己处理输入法让位时打开；外层已统一处理时关掉）
    imeFollowEnabled: Boolean = false,
    onInput: (String) -> Unit = {},
    onScaleChanged: (Float) -> Unit = {},
    sessionId: String? = null,
    onScrollOffsetChanged: ((String, Float) -> Unit)? = null,
    getScrollOffset: ((String) -> Float)? = null,
    tabs: List<TerminalTabRenderItem> = emptyList(),
    currentTabId: String? = null,
    onTabClick: ((String) -> Unit)? = null,
    onTabClose: ((String) -> Unit)? = null,
    onNewTab: (() -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setPty(pty)
                setImeViewportState(
                    animationOffsetPx = imeAnimationOffsetPx,
                    committedBottomInsetPx = committedImeBottomInsetPx
                )
                setImeFollowEnabled(imeFollowEnabled)
                setInputCallback(onInput)
                setScaleCallback(onScaleChanged)
                setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
                setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
                
                // 全屏模式下自动请求焦点
                post {
                    requestFocus()
                }
                
                // 兜底一：进页立刻回到最新输出。
                // 之前的现象是「画布全黑」，实际是 setEmulator 恢复了上次保存的滚动偏移，
                // 提示符被留在屏幕上方看不见（下滑或弹出输入法触发重排后才回来）。
                postDelayed({ scrollToBottom() }, 80L)
                // 兜底二：冷进页面时补几帧，覆盖渲染线程/首帧时序不稳。
                listOf(120L, 250L, 600L, 1200L, 2500L).forEach { delayMs ->
                    postDelayed({ refreshNow() }, delayMs)
                }
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动和缩放手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
            view.setPty(pty)
            view.setImeViewportState(
                animationOffsetPx = imeAnimationOffsetPx,
                committedBottomInsetPx = committedImeBottomInsetPx
            )
            view.setImeFollowEnabled(imeFollowEnabled)
            view.setInputCallback(onInput)
            view.setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
            view.setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
            view.refreshNow()
        },
        onRelease = { view ->
            // 在视图被移除时释放资源，避免持有渲染线程和监听器引用
            view.release()
        },
        modifier = modifier
    )
}

/**
 * 带配置的Canvas终端视图
 */
@Composable
fun ConfigurableCanvasTerminal(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    backgroundColor: Int = 0xFF000000.toInt(),
    foregroundColor: Int = 0xFFFFFFFF.toInt(),
    cursorColor: Int = 0xFF00FF00.toInt(),
    onInput: (String) -> Unit = {}
) {
    val config = remember(fontSize, backgroundColor, foregroundColor, cursorColor) {
        RenderConfig(
            fontSize = fontSize,
            backgroundColor = backgroundColor,
            foregroundColor = foregroundColor,
            cursorColor = cursorColor
        )
    }
    
    var currentScale by remember { mutableStateOf(1f) }
    
    CanvasTerminalScreen(
        emulator = emulator,
        modifier = modifier,
        config = config,
        onInput = onInput,
        onScaleChanged = { scale -> currentScale = scale }
    )
}

/**
 * 性能监控版本的Canvas终端
 */
@Composable
fun PerformanceMonitoredTerminal(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    onInput: (String) -> Unit = {},
    onFpsUpdate: (Float) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setInputCallback(onInput)
                setPerformanceCallback { fps: Float, frameTime: Long ->
                    onFpsUpdate(fps)
                }
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动和缩放手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
        },
        onRelease = { view ->
            // 在视图被移除时释放资源，避免持有渲染线程和监听器引用
            view.release()
        },
        modifier = modifier
    )
}

/**
 * 非全屏Canvas终端输出
 * 仅用于显示终端输出，不处理输入
 */
@Composable
fun CanvasTerminalOutput(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.ai.assistance.operit.terminal.Pty? = null,
    imeAnimationOffsetPx: Int = 0,
    committedImeBottomInsetPx: Int = 0,
    imeFollowEnabled: Boolean = false,
    onRequestShowKeyboard: (() -> Unit)? = null,
    sessionId: String? = null,
    onScrollOffsetChanged: ((String, Float) -> Unit)? = null,
    getScrollOffset: ((String) -> Float)? = null,
    tabs: List<TerminalTabRenderItem> = emptyList(),
    currentTabId: String? = null,
    onTabClick: ((String) -> Unit)? = null,
    onTabClose: ((String) -> Unit)? = null,
    onNewTab: (() -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setPty(pty)
                setImeViewportState(
                    animationOffsetPx = imeAnimationOffsetPx,
                    committedBottomInsetPx = committedImeBottomInsetPx
                )
                setImeFollowEnabled(imeFollowEnabled)
                setFullscreenMode(false) // 关键：设置为非全屏模式
                setOnRequestShowKeyboard(onRequestShowKeyboard)
                setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
                setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
                
                // 兜底一：进页立刻回到最新输出。
                // 之前的现象是「画布全黑」，实际是 setEmulator 恢复了上次保存的滚动偏移，
                // 提示符被留在屏幕上方看不见（下滑或弹出输入法触发重排后才回来）。
                postDelayed({ scrollToBottom() }, 80L)
                // 兜底二：冷进页面时补几帧，覆盖渲染线程/首帧时序不稳。
                listOf(120L, 250L, 600L, 1200L, 2500L).forEach { delayMs ->
                    postDelayed({ refreshNow() }, delayMs)
                }
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
            view.setPty(pty)
            view.setImeViewportState(
                animationOffsetPx = imeAnimationOffsetPx,
                committedBottomInsetPx = committedImeBottomInsetPx
            )
            view.setImeFollowEnabled(imeFollowEnabled)
            view.setOnRequestShowKeyboard(onRequestShowKeyboard)
            view.setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
            view.setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
            view.refreshNow()
        },
        onRelease = { view ->
            // 在视图被移除时释放资源，避免持有渲染线程和监听器引用
            view.release()
        },
        modifier = modifier
    )
}
