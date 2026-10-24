/*
 * 本文件源自 Operit AI 项目（https://github.com/AAswordman/Operit）。
 * Copyright (C) 2023-2024 Operit AI 项目团队
 *
 * 依 GNU Lesser General Public License v3.0 授权使用与分发，
 * 许可证全文见仓库根目录 terminal/LICENSE；Mingent 对其所做的修改
 * 同样以 LGPL-3.0 提供。
 */

package com.ai.assistance.operit.terminal.view.canvas

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.ai.assistance.operit.terminal.R
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.operit.terminal.view.domain.ansi.TerminalChar
import kotlin.math.roundToInt

internal data class TerminalTabAccessibilityNode(
    val tabId: String,
    val title: String,
    val canClose: Boolean,
    val isActive: Boolean,
    val tabRect: RectF,
    val closeRect: RectF?
)

internal data class TerminalTabAccessibilitySnapshot(
    val nodes: List<TerminalTabAccessibilityNode> = emptyList(),
    val addButtonRect: RectF = RectF()
)

/**
 * 为终端视图提供无障碍支持
 * 将终端内容按行暴露给屏幕阅读器，并补齐顶部标签栏的虚拟节点。
 */
internal class TerminalAccessibilityDelegate(
    private val view: CanvasTerminalView,
    private val getEmulator: () -> AnsiTerminalEmulator?,
    private val getTextMetrics: () -> TextMetrics,
    private val getScrollOffsetY: () -> Float,
    private val getContentTop: () -> Float,
    private val getTabs: () -> List<TerminalTabRenderItem>,
    private val getCurrentTabId: () -> String?,
    private val getTabSnapshot: () -> TerminalTabAccessibilitySnapshot,
    private val hasNewTabAction: () -> Boolean,
    private val onSelectTab: (String) -> Unit,
    private val onCloseTab: (String) -> Unit,
    private val onNewTab: () -> Unit
) : View.AccessibilityDelegate() {

    private val nodeProvider = TerminalAccessibilityNodeProvider()

    private fun isAccessibilityEnabled(): Boolean {
        val manager =
            view.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return manager?.isEnabled == true
    }

    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider {
        return nodeProvider
    }

    /**
     * 通知无障碍服务终端内容已更新
     */
    fun notifyContentChanged() {
        view.post {
            if (!isAccessibilityEnabled()) return@post
            try {
                view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            } catch (_: IllegalStateException) {
            }
        }
    }

    /**
     * 为终端的每一行和顶部标签栏创建虚拟无障碍节点
     */
    private inner class TerminalAccessibilityNodeProvider : AccessibilityNodeProvider() {

        private val HOST_VIEW_ID = -1
        private val TAB_NODE_ID_BASE = 1_000
        private val TAB_CLOSE_NODE_ID_BASE = 10_000
        private val TAB_NEW_NODE_ID = 20_000
        private val LINE_NODE_ID_BASE = 100_000

        private var currentAccessibilityFocusedVirtualViewId: Int = -1

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            return when (virtualViewId) {
                HOST_VIEW_ID -> createHostNodeInfo()
                TAB_NEW_NODE_ID -> createNewTabNodeInfo()
                else -> createVirtualNodeInfo(virtualViewId)
            }
        }

        private fun createHostNodeInfo(): AccessibilityNodeInfo {
            val info = AccessibilityNodeInfo.obtain(view)
            view.onInitializeAccessibilityNodeInfo(info)

            info.className = CanvasTerminalView::class.java.name
            info.isFocusable = false
            info.isAccessibilityFocused = false
            info.isClickable = false
            info.isLongClickable = false
            info.isEnabled = true

            val tabs = getTabs()
            tabs.forEachIndexed { index, tab ->
                info.addChild(view, tabNodeId(index))
                if (tab.canClose) {
                    info.addChild(view, tabCloseNodeId(index))
                }
            }
            if (hasNewTabAction()) {
                info.addChild(view, TAB_NEW_NODE_ID)
            }

            val emulator = getEmulator()
            if (emulator != null) {
                val screenContent = emulator.getScreenContent()
                val visibleLines = screenContent.size
                for (i in 0 until visibleLines) {
                    info.addChild(view, lineNodeId(i))
                }
            }

            return info
        }

        private fun createVirtualNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId in TAB_NODE_ID_BASE until TAB_CLOSE_NODE_ID_BASE) {
                return createTabNodeInfo(virtualViewId - TAB_NODE_ID_BASE)
            }

            if (virtualViewId in TAB_CLOSE_NODE_ID_BASE until TAB_NEW_NODE_ID) {
                return createTabCloseNodeInfo(virtualViewId - TAB_CLOSE_NODE_ID_BASE)
            }

            val emulator = getEmulator() ?: return null
            val lineIndex = virtualViewId - LINE_NODE_ID_BASE
            val screenContent = emulator.getScreenContent()
            if (lineIndex < 0 || lineIndex >= screenContent.size) {
                return null
            }

            val info = AccessibilityNodeInfo.obtain(view, virtualViewId)
            info.setParent(view)
            info.className = "android.widget.TextView"
            info.packageName = view.context.packageName

            val lineContent = getLineText(screenContent[lineIndex])
            info.text = lineContent
            info.contentDescription =
                view.context.getString(
                    R.string.terminal_accessibility_line,
                    lineIndex + 1,
                    lineContent
                )

            val bounds = getLineBounds(lineIndex)
            info.setBoundsInParent(bounds)
            info.setBoundsInScreen(parentToScreenRect(bounds))

            info.isVisibleToUser = bounds.top >= 0 && bounds.top < view.height
            info.isEnabled = true
            info.isFocusable = true
            info.isAccessibilityFocused =
                virtualViewId == currentAccessibilityFocusedVirtualViewId
            info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            info.addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            return info
        }

        private fun createTabNodeInfo(tabIndex: Int): AccessibilityNodeInfo? {
            val tab = getTabs().getOrNull(tabIndex) ?: return null
            val snapshotNode = getTabSnapshot().nodes.firstOrNull { it.tabId == tab.id }
            val bounds = snapshotNode?.tabRect?.toRoundedRect() ?: fallbackTabBounds(tabIndex)
            val tabTitle =
                tab.title.ifBlank { view.context.getString(R.string.unknown_session) }
            val isCurrent = getCurrentTabId() == tab.id

            return AccessibilityNodeInfo.obtain(view, tabNodeId(tabIndex)).apply {
                setParent(view)
                className = "android.widget.Button"
                packageName = view.context.packageName
                contentDescription =
                    view.context.getString(
                        if (isCurrent) {
                            R.string.terminal_accessibility_current_session_tab
                        } else {
                            R.string.terminal_accessibility_session_tab
                        },
                        tabTitle
                    )
                setBoundsInParent(bounds)
                setBoundsInScreen(parentToScreenRect(bounds))
                isVisibleToUser = !bounds.isEmpty
                isEnabled = true
                isFocusable = true
                isClickable = true
                isSelected = isCurrent
                isAccessibilityFocused =
                    tabNodeId(tabIndex) == currentAccessibilityFocusedVirtualViewId
                addAction(AccessibilityNodeInfo.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            }
        }

        private fun createTabCloseNodeInfo(tabIndex: Int): AccessibilityNodeInfo? {
            val tab = getTabs().getOrNull(tabIndex) ?: return null
            if (!tab.canClose) {
                return null
            }

            val snapshotNode = getTabSnapshot().nodes.firstOrNull { it.tabId == tab.id }
            val bounds =
                snapshotNode?.closeRect?.toRoundedRect()
                    ?: fallbackCloseBounds(fallbackTabBounds(tabIndex))
            val tabTitle =
                tab.title.ifBlank { view.context.getString(R.string.unknown_session) }

            return AccessibilityNodeInfo.obtain(view, tabCloseNodeId(tabIndex)).apply {
                setParent(view)
                className = "android.widget.Button"
                packageName = view.context.packageName
                contentDescription =
                    view.context.getString(
                        R.string.terminal_accessibility_close_session_tab,
                        tabTitle
                    )
                setBoundsInParent(bounds)
                setBoundsInScreen(parentToScreenRect(bounds))
                isVisibleToUser = !bounds.isEmpty
                isEnabled = true
                isFocusable = true
                isClickable = true
                isAccessibilityFocused =
                    tabCloseNodeId(tabIndex) == currentAccessibilityFocusedVirtualViewId
                addAction(AccessibilityNodeInfo.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            }
        }

        private fun createNewTabNodeInfo(): AccessibilityNodeInfo {
            val bounds =
                getTabSnapshot().addButtonRect
                    .takeUnless { it.isEmpty }
                    ?.toRoundedRect()
                    ?: fallbackNewTabBounds()

            return AccessibilityNodeInfo.obtain(view, TAB_NEW_NODE_ID).apply {
                setParent(view)
                className = "android.widget.Button"
                packageName = view.context.packageName
                contentDescription = view.context.getString(R.string.new_session)
                setBoundsInParent(bounds)
                setBoundsInScreen(parentToScreenRect(bounds))
                isVisibleToUser = !bounds.isEmpty
                isEnabled = true
                isFocusable = true
                isClickable = true
                isAccessibilityFocused =
                    TAB_NEW_NODE_ID == currentAccessibilityFocusedVirtualViewId
                addAction(AccessibilityNodeInfo.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            }
        }

        private fun getLineText(line: Array<TerminalChar>): String {
            val sb = StringBuilder()
            for (terminalChar in line) {
                if (terminalChar.char != '\u0000' && terminalChar.char != ' ') {
                    sb.append(terminalChar.char)
                } else if (terminalChar.char == ' ') {
                    sb.append(' ')
                }
            }
            return sb.toString().trimEnd()
        }

        private fun getLineBounds(lineIndex: Int): Rect {
            val emulator = getEmulator()
            val metrics = getTextMetrics()
            val charHeight = metrics.charHeight
            val scrollOffset = getScrollOffsetY()
            val contentTop = getContentTop()
            val historySize = emulator?.getHistorySize() ?: 0
            val absoluteRow = historySize + lineIndex
            val exactY = contentTop + absoluteRow * charHeight - scrollOffset
            return Rect(
                0,
                exactY.toInt(),
                view.width,
                (exactY + charHeight).toInt()
            )
        }

        override fun performAction(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    when {
                        virtualViewId == TAB_NEW_NODE_ID -> {
                            onNewTab()
                            sendEventForVirtualView(
                                virtualViewId,
                                AccessibilityEvent.TYPE_VIEW_CLICKED
                            )
                            return true
                        }
                        virtualViewId in TAB_NODE_ID_BASE until TAB_CLOSE_NODE_ID_BASE -> {
                            val tabId = getTabs().getOrNull(virtualViewId - TAB_NODE_ID_BASE)?.id
                                ?: return false
                            onSelectTab(tabId)
                            sendEventForVirtualView(
                                virtualViewId,
                                AccessibilityEvent.TYPE_VIEW_CLICKED
                            )
                            return true
                        }
                        virtualViewId in TAB_CLOSE_NODE_ID_BASE until TAB_NEW_NODE_ID -> {
                            val tabId =
                                getTabs().getOrNull(virtualViewId - TAB_CLOSE_NODE_ID_BASE)?.id
                                    ?: return false
                            if (virtualViewId == currentAccessibilityFocusedVirtualViewId) {
                                currentAccessibilityFocusedVirtualViewId = -1
                            }
                            onCloseTab(tabId)
                            sendEventForVirtualView(
                                virtualViewId,
                                AccessibilityEvent.TYPE_VIEW_CLICKED
                            )
                            return true
                        }
                    }
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (virtualViewId == HOST_VIEW_ID) {
                        return false
                    }

                    if (currentAccessibilityFocusedVirtualViewId != -1) {
                        val oldFocusedId = currentAccessibilityFocusedVirtualViewId
                        currentAccessibilityFocusedVirtualViewId = -1
                        sendEventForVirtualView(
                            oldFocusedId,
                            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
                        )
                    }

                    currentAccessibilityFocusedVirtualViewId = virtualViewId
                    sendEventForVirtualView(
                        virtualViewId,
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                    )
                    return true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (virtualViewId == currentAccessibilityFocusedVirtualViewId) {
                        currentAccessibilityFocusedVirtualViewId = -1
                        sendEventForVirtualView(
                            virtualViewId,
                            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
                        )
                        return true
                    }
                    return false
                }
            }
            return false
        }

        private fun sendEventForVirtualView(virtualViewId: Int, eventType: Int) {
            if (!isAccessibilityEnabled()) {
                return
            }

            val event = AccessibilityEvent.obtain(eventType)
            event.packageName = view.context.packageName
            event.className =
                if (
                    virtualViewId == TAB_NEW_NODE_ID ||
                        virtualViewId in TAB_NODE_ID_BASE until TAB_NEW_NODE_ID
                ) {
                    "android.widget.Button"
                } else {
                    "android.widget.TextView"
                }
            event.setSource(view, virtualViewId)

            try {
                view.parent?.requestSendAccessibilityEvent(view, event)
            } catch (_: IllegalStateException) {
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? {
            if (
                focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY &&
                    currentAccessibilityFocusedVirtualViewId != -1
            ) {
                return createAccessibilityNodeInfo(currentAccessibilityFocusedVirtualViewId)
            }
            return null
        }

        override fun findAccessibilityNodeInfosByText(
            text: String?,
            virtualViewId: Int
        ): List<AccessibilityNodeInfo>? {
            return null
        }

        fun findVirtualViewAt(x: Float, y: Float): Int {
            val contentTop = getContentTop()
            if (y in 0f..contentTop) {
                val snapshot = getTabSnapshot()
                val newTabRect =
                    snapshot.addButtonRect.takeUnless { it.isEmpty }
                        ?: RectF(fallbackNewTabBounds())
                if (hasNewTabAction() && newTabRect.contains(x, y)) {
                    return TAB_NEW_NODE_ID
                }

                val tabs = getTabs()
                for (index in tabs.indices.reversed()) {
                    val tab = tabs[index]
                    val node = snapshot.nodes.firstOrNull { it.tabId == tab.id }
                    val closeRect =
                        node?.closeRect
                            ?: if (tab.canClose) {
                                RectF(fallbackCloseBounds(fallbackTabBounds(index)))
                            } else {
                                null
                            }
                    if (closeRect?.contains(x, y) == true) {
                        return tabCloseNodeId(index)
                    }
                    val tabRect = node?.tabRect ?: RectF(fallbackTabBounds(index))
                    if (tabRect.contains(x, y)) {
                        return tabNodeId(index)
                    }
                }
            }

            val emulator = getEmulator() ?: return HOST_VIEW_ID
            val metrics = getTextMetrics()
            val charHeight = metrics.charHeight
            val scrollOffset = getScrollOffsetY()
            if (y < contentTop) {
                return HOST_VIEW_ID
            }

            val localY = y - contentTop
            val absoluteRow = ((localY + scrollOffset) / charHeight).toInt()
            val screenContent = emulator.getScreenContent()
            val historySize = emulator.getHistorySize()
            val lineIndex = absoluteRow - historySize
            return if (lineIndex in 0 until screenContent.size) {
                lineNodeId(lineIndex)
            } else {
                HOST_VIEW_ID
            }
        }

        fun clearAccessibilityFocus() {
            if (currentAccessibilityFocusedVirtualViewId != -1) {
                performAction(
                    currentAccessibilityFocusedVirtualViewId,
                    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
                    null
                )
            }
        }

        private fun tabNodeId(index: Int): Int = TAB_NODE_ID_BASE + index

        private fun tabCloseNodeId(index: Int): Int = TAB_CLOSE_NODE_ID_BASE + index

        private fun lineNodeId(index: Int): Int = LINE_NODE_ID_BASE + index

        private fun parentToScreenRect(bounds: Rect): Rect {
            val screenBounds = Rect(bounds)
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            screenBounds.offset(location[0], location[1])
            return screenBounds
        }

        private fun RectF.toRoundedRect(): Rect =
            Rect(
                left.roundToInt(),
                top.roundToInt(),
                right.roundToInt(),
                bottom.roundToInt()
            )

        private fun fallbackTabBounds(tabIndex: Int): Rect {
            val tabs = getTabs()
            val width = view.width.coerceAtLeast(1)
            val contentTop = getContentTop().roundToInt().coerceAtLeast(1)
            if (tabs.isEmpty()) {
                return Rect(0, 0, width, contentTop)
            }

            val hasAddButton = hasNewTabAction()
            val addButtonWidth = if (hasAddButton) contentTop else 0
            val rightEdge = (width - addButtonWidth).coerceAtLeast(1)
            val slotWidth = (rightEdge.toFloat() / tabs.size).coerceAtLeast(1f)
            val left = (slotWidth * tabIndex).roundToInt().coerceAtLeast(0)
            val right =
                if (tabIndex == tabs.lastIndex) {
                    rightEdge
                } else {
                    (slotWidth * (tabIndex + 1)).roundToInt().coerceAtLeast(left + 1)
                }
            return Rect(left, 0, right, contentTop)
        }

        private fun fallbackCloseBounds(tabBounds: Rect): Rect {
            val size = (tabBounds.height() * 0.55f).roundToInt().coerceAtLeast(1)
            val right = tabBounds.right
            val left = (right - size).coerceAtLeast(tabBounds.left)
            val top = (tabBounds.centerY() - size / 2f).roundToInt().coerceAtLeast(tabBounds.top)
            val bottom = (top + size).coerceAtMost(tabBounds.bottom)
            return Rect(left, top, right, bottom)
        }

        private fun fallbackNewTabBounds(): Rect {
            val contentTop = getContentTop().roundToInt().coerceAtLeast(1)
            val width = view.width.coerceAtLeast(contentTop)
            val left = (width - contentTop).coerceAtLeast(0)
            return Rect(left, 0, width, contentTop)
        }
    }

    fun findVirtualViewAt(x: Float, y: Float): Int {
        return nodeProvider.findVirtualViewAt(x, y)
    }

    fun clearAccessibilityFocus() {
        nodeProvider.clearAccessibilityFocus()
    }
}
