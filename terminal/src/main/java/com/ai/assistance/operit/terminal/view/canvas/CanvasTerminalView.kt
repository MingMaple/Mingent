/*
 * 本文件源自 Operit AI 项目（https://github.com/AAswordman/Operit）。
 * Copyright (C) 2023-2024 Operit AI 项目团队
 *
 * 依 GNU Lesser General Public License v3.0 授权使用与分发，
 * 许可证全文见仓库根目录 terminal/LICENSE；Mingent 对其所做的修改
 * 同样以 LGPL-3.0 提供。
 */

package com.ai.assistance.operit.terminal.view.canvas
 
 import android.content.ClipData
 import android.content.ClipboardManager
 import android.content.Context
 import android.graphics.*
 import android.os.Build
 import android.os.Handler
 import android.os.Looper
 import android.util.AttributeSet
 import android.util.Log
 import androidx.annotation.RequiresApi
 import android.view.ActionMode
 import android.view.KeyEvent
 import android.view.Menu
 import android.view.MenuItem
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.accessibility.AccessibilityManager
 import android.view.inputmethod.BaseInputConnection
 import android.view.inputmethod.EditorInfo
 import android.view.inputmethod.InputConnection
 import android.view.inputmethod.InputMethodManager
 import android.widget.OverScroller
 import com.ai.assistance.operit.terminal.R
 import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
 import com.ai.assistance.operit.terminal.view.domain.ansi.TerminalChar
 import java.io.File
 import java.util.concurrent.locks.ReentrantLock
 import kotlin.math.abs
 import kotlin.math.ceil
 import kotlin.math.floor
 import kotlin.math.max
 import kotlin.math.min
 
 /**
  * 基于Canvas的高性能终端视图
  * 使用SurfaceView + 独立渲染线程实现
 */
class CanvasTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {
    
    // 渲染配置
    private var config = RenderConfig()
    
    // 暂停控制
    private val pauseLock = Object()
    @Volatile
    private var isPaused = false
    
    // Paint对象（复用以提高性能）
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = config.fontSize
    }
    
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    
    private val cursorPaint = Paint().apply {
        color = Color.GREEN
        alpha = 180
        style = Paint.Style.FILL
    }
    
    private val selectionPaint = Paint().apply {
        color = Color.argb(100, 100, 149, 237) // 半透明蓝色
        style = Paint.Style.FILL
    }

    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.FILL
    }

    private val selectionHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 220
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.8f
    }

    private val selectionHandleRadius: Float = resources.displayMetrics.density * 10f

    private val magnifierBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 20, 20, 20)
        style = Paint.Style.FILL
    }

    private val magnifierBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 180
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    private val magnifierCellStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 210
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val magnifierPointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 220
        style = Paint.Style.FILL
    }

    private val magnifierRadius: Float = resources.displayMetrics.density * 58f
    private val magnifierPadding: Float = resources.displayMetrics.density * 10f
    private var isSelectionDragging: Boolean = false
    private var lastSelectionTouchX: Float = 0f
    private var lastSelectionTouchY: Float = 0f

    private val autoScrollEdgeThreshold: Float = resources.displayMetrics.density * 40f
    private val autoScrollStepPx: Float = resources.displayMetrics.density * 14f
    private var autoScrollDirection: Int = 0
    private var autoScrollRunnable: Runnable? = null
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var hasMovedBeyondTouchSlop: Boolean = false
    private var hadMultiTouch: Boolean = false
    private var imeAnimationOffsetPx: Int = 0
    private var committedImeBottomInsetPx: Int = 0
    /**
     * 提交帧残余位移（纯绘制层，和滚动无关）。
     *
     * 键盘沉降提交那一帧，scrollOffsetY 会被重锚点到新的贴底位置，而同一帧
     * (animated - committed) 也归零；两者对不齐时剩下的差值记在这里，120ms 缓出到 0。
     *
     * 为什么不改滚动（原来的 scrollCatchUp 做法）：滚动一动，行范围、命中映射全跟着变，
     * 屏幕位置是「先跳一下再滑过去」；而残余只改一个位移量，屏幕位置从第一帧起就是连续的。
     * 正常贴底时这个值算出来就是 0，只有光标行被重排挤动时才非 0（几十像素量级）。
     */
    @Volatile
    private var commitResidualPx: Float = 0f
    @Volatile
    private var commitResidualStartMs: Long = 0L
    private val commitResidualDurationMs: Long = 120L
    /**
     * 改 committed 之前那一刻的位移快照。
     *
     * setImeViewportState 是「先改 committed，再调 updateTerminalSize」，等后者去读
     * getTerminalVisualOffsetY() 时 (animated - committed) 已经归零，算出来的残余正好把要
     * 消掉的那一跳又加回来（开键盘会得到 -1122，等于原地再跳一次）。所以改 committed 之前
     * 先抓一份；旋转/缩放等其它 resize 入口没有这一帧，保持 null，走旧行为。
     */
    @Volatile
    private var offsetBeforeCommitPx: Int? = null

    // === IME 动画跟手（绘制层） ===
    // imeAnimatedInsetPx: 键盘动画的逐帧高度（已减掉导航栏），由 WindowInsetsAnimation.Callback
    //   在 UI 线程逐帧写入，渲染线程只读；整条链路不经过 Compose 组合，所以键盘动画期间
    //   既不会重组整棵树，也不会改行列数。API < 30 拿不到逐帧回调，这里退化成「只在稳定后
    //   拿到最终值」，此时跟手位移等于 0，行为与旧版一致。
    @Volatile
    private var imeAnimatedInsetPx: Int = 0
    private var imeAnimationRunning: Boolean = false
    private var imeAnimationCallbackInstalled: Boolean = false
    // 是否由画布自己跟手 IME。嵌在「外层自己处理输入法」的宿主里时必须关掉，
    // 否则外层让位 + 画布位移会变成双重偏移。
    private var imeFollowEnabled: Boolean = false
    // 定位期 A/B 开关（验证完可连同旧分支一起删）：
    //   true  = 新口径「按键盘实际盖住的内容像素跟手」
    //   false = 旧口径「内容贴底才跟手」（0.5px 判据）
    // 改这一行即可在真机上对比新旧行为，日志里 mode= 会标明走的是哪支。
    private val imeFollowByCoveredPx: Boolean = true
    // 上一次参与排版的视口高度：用来判断「视口是不是被键盘压矮了」，从而决定滚动锚点。
    private var previousTerminalContentHeightPx: Float = 0f

    // === IME 定位插桩（临时，定位完可整块删） ===
    // 本模块依赖不到 app 的 FileLog，统一走 android.util.Log；捞法：adb shell logcat -d -s IMEDBG
    private var imeDbgProgressMs: Long = 0L
    private var imeDbgNoFollowMs: Long = 0L
    private var imeDbgFrameMs: Long = 0L
    private fun imeDbg(msg: String) {
        Log.e("IMEDBG", System.currentTimeMillis().toString() + " " + msg)
    }
    
    // 文本测量工具
    private val textMetrics: TextMetrics
    
    // 终端模拟器
    private var emulator: AnsiTerminalEmulator? = null
    private var emulatorChangeListener: (() -> Unit)? = null
    private var emulatorNewOutputListener: (() -> Unit)? = null

    private val newOutputDispatchLock = Any()
    private var newOutputDispatchPosted = false
    private val newOutputDispatchRunnable = Runnable {
        synchronized(newOutputDispatchLock) {
            newOutputDispatchPosted = false
        }
        if (autoScrollToBottom) {
            scrollToBottom()
        }
    }

    private fun drawSelectionHandles(canvas: Canvas, charWidth: Float, charHeight: Float) {
        val centers = getSelectionHandleCenters(charWidth, charHeight) ?: return
        val start = centers.first
        val end = centers.second

        drawSelectionHandle(canvas, start)
        drawSelectionHandle(canvas, end)
    }

    private fun drawSelectionHandle(canvas: Canvas, center: PointF) {
        val r = selectionHandleRadius
        val bodyWidth = r * 1.2f
        val tipHeight = r * 0.9f
        val bodyHeight = r * 1.8f
        // 手柄画在 translate 块外面，用的是已经减过位移的屏幕坐标（getSelectionHandleCenters）。
        // 夹取边界必须是整窗：收键盘时锚点会被推到「视口底」以下，夹在视口底就会和内容脱节。
        val windowBottom = height.toFloat()

        val tipX = center.x.coerceIn(0f, width.toFloat())
        val tipY = center.y.coerceIn(0f, windowBottom)

        val baseY = (tipY + tipHeight).coerceIn(0f, windowBottom)
        val bodyTop = baseY
        val bodyBottom = (bodyTop + bodyHeight).coerceIn(bodyTop, windowBottom)

        val left = (tipX - bodyWidth / 2f).coerceIn(0f, width.toFloat())
        val right = (tipX + bodyWidth / 2f).coerceIn(0f, width.toFloat())

        val tipPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(left, baseY)
            lineTo(right, baseY)
            close()
        }

        val rect = RectF(left, bodyTop, right, bodyBottom)
        val rx = bodyWidth / 2f
        val ry = bodyWidth / 2f

        canvas.drawPath(tipPath, selectionHandlePaint)
        canvas.drawPath(tipPath, selectionHandleStrokePaint)

        canvas.drawRoundRect(rect, rx, ry, selectionHandlePaint)
        canvas.drawRoundRect(rect, rx, ry, selectionHandleStrokePaint)
    }

    private fun drawSelectionMagnifier(canvas: Canvas) {
        val em = emulator ?: return
        val fullContent = em.getFullContent()
        if (fullContent.isEmpty()) return

        val (row, col) = screenToTerminalCoords(lastSelectionTouchX, lastSelectionTouchY)
        val r = magnifierRadius
        val bubbleSize = r * 2f

        var cx = lastSelectionTouchX
        var cy = lastSelectionTouchY - r * 1.9f
        if (cy < r) {
            cy = lastSelectionTouchY + r * 1.9f
        }

        val contentTop = getTerminalContentTop()
        cx = cx.coerceIn(r + 2f, width - r - 2f)
        val minCy = contentTop + r + 2f
        val maxCy = getTerminalViewportBottom() - r - 2f
        cy = if (minCy <= maxCy) {
            cy.coerceIn(minCy, maxCy)
        } else {
            contentTop + (getTerminalViewportBottom() - contentTop) / 2f
        }

        val bubbleRect = RectF(cx - r, cy - r, cx + r, cy + r)
        val corner = r * 0.32f

        canvas.drawRoundRect(bubbleRect, corner, corner, magnifierBgPaint)
        canvas.drawRoundRect(bubbleRect, corner, corner, magnifierBorderPaint)

        val inner = RectF(
            bubbleRect.left + magnifierPadding,
            bubbleRect.top + magnifierPadding,
            bubbleRect.right - magnifierPadding,
            bubbleRect.bottom - magnifierPadding
        )

        val contextCols = 3
        val contextRows = 1
        val cols = contextCols * 2 + 1
        val rows = contextRows * 2 + 1

        val charWidth = textMetrics.charWidth
        val charHeight = textMetrics.charHeight
        val baseline = textMetrics.charBaseline

        val regionWidth = cols * charWidth
        val regionHeight = rows * charHeight

        val maxScaleX = (inner.width() / regionWidth).coerceAtLeast(1f)
        val maxScaleY = (inner.height() / regionHeight).coerceAtLeast(1f)
        val scale = min(3.2f, max(1.6f, min(maxScaleX, maxScaleY)))

        val drawLeft = inner.centerX() - (regionWidth * scale) / 2f
        val drawTop = inner.centerY() - (regionHeight * scale) / 2f

        val startRow = (row - contextRows).coerceAtLeast(0)
        val endRow = (row + contextRows).coerceAtMost(fullContent.size - 1)

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(
                inner,
                corner * 0.8f,
                corner * 0.8f,
                Path.Direction.CW
            )
        }
        canvas.clipPath(clipPath)
        canvas.translate(drawLeft, drawTop)
        canvas.scale(scale, scale)

        var localRowIndex = 0
        for (rr in startRow..endRow) {
            val srcLine = fullContent.getOrNull(rr) ?: emptyArray()
            val line = Array(cols) { i ->
                val srcCol = col - contextCols + i
                if (srcCol in srcLine.indices) srcLine[srcCol] else TerminalChar()
            }
            drawLine(canvas, line, localRowIndex, 0f, localRowIndex * charHeight, charWidth, charHeight, baseline)
            localRowIndex++
        }

        val centerLeft = contextCols * charWidth
        val centerTop = contextRows * charHeight
        val focusLine = fullContent.getOrNull(row) ?: emptyArray()
        val safeCol = if (focusLine.isNotEmpty()) col.coerceIn(0, focusLine.size - 1) else 0
        val cellWidth = if (focusLine.isNotEmpty()) {
            textMetrics.getCellWidth(focusLine[safeCol].char).toFloat()
        } else {
            1f
        }
        val cellLeft = if (focusLine.isNotEmpty()) {
            getXOffsetForCol(focusLine, safeCol, charWidth)
        } else {
            0f
        }
        val cellRight = cellLeft + charWidth * cellWidth
        val useRightEdge = when (activeDragHandle) {
            DragHandle.START -> false
            DragHandle.END -> true
            else -> lastSelectionTouchX >= (cellLeft + cellRight) / 2f
        }
        val pointerOffset = if (useRightEdge) charWidth * cellWidth else 0f
        val pointerX = (centerLeft + pointerOffset).coerceIn(0f, cols * charWidth)
        val pointerTop = centerTop + charHeight * 0.12f
        val pointerBottom = centerTop + charHeight * 0.88f
        val pointerWidth = max(2f, charWidth * 0.08f)
        canvas.drawRect(
            pointerX - pointerWidth / 2f,
            pointerTop,
            pointerX + pointerWidth / 2f,
            pointerBottom,
            magnifierPointerPaint
        )

        val tipHeight = charHeight * 0.2f
        val tipWidth = charWidth * 0.45f
        val tipBaseY = pointerBottom
        val tipY = min(rows * charHeight - 1f, tipBaseY + tipHeight)
        val tipPath = Path().apply {
            moveTo(pointerX, tipY)
            lineTo(pointerX - tipWidth / 2f, tipBaseY)
            lineTo(pointerX + tipWidth / 2f, tipBaseY)
            close()
        }
        canvas.drawPath(tipPath, magnifierPointerPaint)

        canvas.restore()
    }

    private fun updateAutoScrollForSelectionDrag() {
        if (!isSelectionDragging || !selectionManager.hasSelection()) {
            autoScrollDirection = 0
            return
        }
        val y = lastSelectionTouchY
        val contentTop = getTerminalContentTop()
        val contentBottom = getTerminalViewportBottom()
        autoScrollDirection = when {
            y < contentTop + autoScrollEdgeThreshold -> -1
            y > contentBottom - autoScrollEdgeThreshold -> 1
            else -> 0
        }

        if (autoScrollDirection == 0) {
            autoScrollRunnable?.let { handler.removeCallbacks(it) }
            autoScrollRunnable = null
            return
        }

        if (autoScrollRunnable != null) return
        autoScrollRunnable = object : Runnable {
            override fun run() {
                if (!isSelectionDragging || !selectionManager.hasSelection()) {
                    autoScrollRunnable = null
                    autoScrollDirection = 0
                    return
                }
                if (autoScrollDirection == 0) {
                    autoScrollRunnable = null
                    return
                }

                val em = emulator ?: run {
                    autoScrollRunnable = null
                    autoScrollDirection = 0
                    return
                }

                val maxScrollOffset = max(0f, em.getFullContent().size * textMetrics.charHeight - getTerminalViewportHeight())
                scrollOffsetY = (scrollOffsetY + autoScrollDirection * autoScrollStepPx).coerceIn(0f, maxScrollOffset)

                val (row, col) = screenToTerminalCoords(lastSelectionTouchX, lastSelectionTouchY)
                when (activeDragHandle) {
                    DragHandle.START -> selectionManager.setSelectionStart(row, col)
                    DragHandle.END -> selectionManager.setSelectionEnd(row, col)
                    else -> selectionManager.updateSelection(row, col)
                }

                actionMode?.invalidate()
                requestRender()
                postOnAnimation(this)
            }
        }
        postOnAnimation(autoScrollRunnable!!)
    }

    private fun getSelectionHandleCenters(charWidth: Float, charHeight: Float): Pair<PointF, PointF>? {
        val selection = selectionManager.selection?.normalize() ?: return null
        val em = emulator ?: return null
        val fullContent = em.getFullContent()
        if (fullContent.isEmpty()) return null

        val startRow = selection.startRow.coerceIn(0, fullContent.size - 1)
        val endRow = selection.endRow.coerceIn(0, fullContent.size - 1)

        val startLine = fullContent.getOrNull(startRow) ?: return null
        val endLine = fullContent.getOrNull(endRow) ?: return null

        val startCol = selection.startCol.coerceIn(0, max(0, startLine.size - 1))
        val endCol = selection.endCol.coerceIn(0, max(0, endLine.size - 1))
        val contentTop = getTerminalContentTop()
        // 中心的 y 在这里就把位移减掉，和内容行（translate 之后）落点一致：同一个 O，
        // 同帧同位移。夹取边界用整窗，理由见 drawSelectionHandle。
        val contentBottom = height.toFloat()
        val visualShiftY = getTerminalVisualOffsetY()

        val startX = getXOffsetForCol(startLine, startCol, charWidth)
        val startY =
            contentTop +
                kotlin.math.round(startRow * charHeight - scrollOffsetY + charHeight) -
                visualShiftY

        val endX = run {
            val x = getXOffsetForCol(endLine, endCol, charWidth)
            val w = if (endLine.isNotEmpty()) {
                charWidth * textMetrics.getCellWidth(endLine[endCol].char)
            } else {
                charWidth
            }
            x + w
        }
        val endY =
            contentTop +
                kotlin.math.round(endRow * charHeight - scrollOffsetY + charHeight) -
                visualShiftY

        val start = PointF(startX.coerceIn(0f, width.toFloat()), startY.coerceIn(contentTop, contentBottom))
        val end = PointF(endX.coerceIn(0f, width.toFloat()), endY.coerceIn(contentTop, contentBottom))

        return Pair(start, end)
    }
    
    // 是否自动滚动到底部（当新内容到达时）
    private var autoScrollToBottom = true
    // 用户是否正在手动滚动
    private var isUserScrolling = false
    // 是否需要滚动到底部（在渲染时执行）
    private var needScrollToBottom = false

    // Top tab bar rendered inside SurfaceView
    private var tabs: List<TerminalTabRenderItem> = emptyList()
    private var currentTabId: String? = null
    private var onTabClick: ((String) -> Unit)? = null
    private var onTabClose: ((String) -> Unit)? = null
    private var onNewTab: (() -> Unit)? = null
    @Volatile
    private var tabHitSnapshot = TabHitSnapshot()
    private var tabContentWidth = 0f
    private var tabScrollOffsetX = 0f
    private var pendingScrollToEndAfterNewTab = false
    private var pendingScrollToEndMinTabCount = 0
    private var activeTabTouchTarget: TabTouchTarget = TabTouchTarget.None
    private var tabTouchDownX = 0f
    private var tabTouchDownY = 0f
    private var tabTouchMoved = false
    private var isTabTouchActive = false
    private var tabTouchX = 0f
    private var tabTouchY = 0f
    private var tabVelocityTracker: VelocityTracker? = null

    private val tabBarBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tabTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        isAntiAlias = true
    }
    private val tabIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.8f * resources.displayMetrics.density
    }

    private val tabBarBackgroundColor = 0xFF2D2D2D.toInt()
    private val tabActiveColor = 0xFF4A4A4A.toInt()
    private val tabInactiveColor = 0xFF3A3A3A.toInt()
    private val tabActivePressedColor = 0xFF3F3F3F.toInt()
    private val tabInactivePressedColor = 0xFF323232.toInt()
    private val tabClosePressedBgColor = Color.argb(70, 255, 255, 255)
    private val tabAddPressedBgColor = 0xFF2F2F2F.toInt()

    private val tabBarHeightPx = 40f * resources.displayMetrics.density
    private val tabHorizontalPaddingPx = 8f * resources.displayMetrics.density
    private val tabVerticalPaddingPx = 4f * resources.displayMetrics.density
    private val tabSpacingPx = 4f * resources.displayMetrics.density
    private val tabCornerRadiusPx = 8f * resources.displayMetrics.density
    private val tabTextHorizontalPaddingPx = 12f * resources.displayMetrics.density
    private val tabMinWidthPx = 72f * resources.displayMetrics.density
    private val tabMaxWidthPx = 200f * resources.displayMetrics.density
    private val closeButtonSizePx = 16f * resources.displayMetrics.density
    private val addButtonSizePx = 32f * resources.displayMetrics.density
    private val tabTouchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tabMinFlingVelocityPx = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val tabMaxFlingVelocityPx = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    
    // PTY 引用（用于窗口大小同步）
    private var pty: com.ai.assistance.operit.terminal.Pty? = null
    
    // 渲染线程
    private var renderThread: RenderThread? = null
    private val renderLock = ReentrantLock()
    private val renderCondition = renderLock.newCondition()
    @Volatile private var isDirty = true // 是否需要重绘
    
    // 手势处理
    private lateinit var gestureHandler: GestureHandler
    private val selectionManager = TextSelectionManager()
    
    // 缩放因子
    private var scaleFactor = 1f
        set(value) {
            field = value.coerceIn(0.5f, 3f)
            updateFontSize()
        }
    
    // 滚动偏移
    private var scrollOffsetY = 0f
    
    // 惯性滚动处理器
    private val scroller: OverScroller by lazy { OverScroller(context) }
    private val tabScroller: OverScroller by lazy { OverScroller(context) }
    
    // 输入回调
    private var inputCallback: ((String) -> Unit)? = null
    
    // 点击请求显示键盘的回调（非全屏模式下由上层控制）
    private var onRequestShowKeyboard: (() -> Unit)? = null
    
    // 会话ID和滚动位置回调
    private var sessionId: String? = null
    private var onScrollOffsetChanged: ((String, Float) -> Unit)? = null
    private var getScrollOffset: ((String) -> Float)? = null
    
    // 缓存终端尺寸，避免重复调用
    private var cachedRows = 0
    private var cachedCols = 0
    
    // 临时字符缓冲区，用于避免 drawChar 中的 String 分配
    private val tempCharBuffer = CharArray(1)
    
    // 文本选择ActionMode
    private var actionMode: ActionMode? = null

    private enum class DragHandle {
        NONE, START, END
    }

    private data class TabHitNode(
        val tabId: String,
        val tabRect: RectF,
        val closeRect: RectF?
    )

    private data class TabHitSnapshot(
        val nodes: List<TabHitNode> = emptyList(),
        val addButtonRect: RectF = RectF()
    )

    private sealed interface TabTouchTarget {
        data object None : TabTouchTarget
        data object Background : TabTouchTarget
        data object NewTab : TabTouchTarget
        data class SelectTab(val tabId: String) : TabTouchTarget
        data class CloseTab(val tabId: String) : TabTouchTarget
    }

    private var activeDragHandle: DragHandle = DragHandle.NONE
    
    // 全屏模式标记
    private var isFullscreenMode = true
    
    // 方向键长按处理
    private val handler = Handler(Looper.getMainLooper())
    private var currentArrowKey: Int? = null
    private var arrowKeyRepeatRunnable: Runnable? = null
    private var isArrowKeyPressed = false
    private val longPressDelay = 500L // 长按延迟时间（毫秒）
    private val repeatInterval = 200L // 重复发送间隔（毫秒）
    
    // 无障碍支持
    private val terminalAccessibilityDelegate: TerminalAccessibilityDelegate
    
    init {
        // 显式固定 SurfaceView 合成行为，减少部分厂商系统上层级异常
        setZOrderOnTop(false)
        setZOrderMediaOverlay(false)
        holder.setFormat(PixelFormat.OPAQUE)

        holder.addCallback(this)
        setWillNotDraw(false)
        
        // 使视图可以获得焦点以接收输入法输入
        isFocusable = true
        isFocusableInTouchMode = true
        
        // 初始化文本指标
        textMetrics = TextMetrics(textPaint, config)
        
        // 加载并应用字体
        loadAndApplyFont()
        
        // 初始化手势处理器
        initGestureHandler()
        
        // 初始化无障碍支持
        terminalAccessibilityDelegate = TerminalAccessibilityDelegate(
            view = this,
            getEmulator = { emulator },
            getTextMetrics = { textMetrics },
            getScrollOffsetY = { scrollOffsetY },
            getContentTop = { getTerminalContentTop() },
            getTabs = { tabs },
            getCurrentTabId = { currentTabId },
            getTabSnapshot = {
                TerminalTabAccessibilitySnapshot(
                    nodes =
                        tabHitSnapshot.nodes.map { node ->
                            val tab =
                                tabs.firstOrNull { it.id == node.tabId } ?: TerminalTabRenderItem(
                                    id = node.tabId,
                                    title = "",
                                    canClose = node.closeRect != null
                                )
                            TerminalTabAccessibilityNode(
                                tabId = node.tabId,
                                title = tab.title,
                                canClose = tab.canClose,
                                isActive = node.tabId == currentTabId,
                                tabRect = RectF(node.tabRect),
                                closeRect = node.closeRect?.let { rect -> RectF(rect) }
                            )
                        },
                    addButtonRect = RectF(tabHitSnapshot.addButtonRect)
                )
            },
            hasNewTabAction = { onNewTab != null },
            onSelectTab = { tabId -> onTabClick?.invoke(tabId) },
            onCloseTab = { tabId -> onTabClose?.invoke(tabId) },
            onNewTab = { onNewTab?.invoke() }
        )
        accessibilityDelegate = terminalAccessibilityDelegate
        
        // 重要：启用accessibility以让系统识别虚拟节点
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        
        // 保持 SurfaceView 在默认窗口层，不作为透明/悬浮覆盖层参与合成
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return manager?.isEnabled == true
    }
    
    /**
     * 加载并应用字体
     */
    private fun loadAndApplyFont() {
        // 主字体已由 config.typeface 提供，直接应用
        textMetrics.updateFromRenderConfig(config)

        // 加载 Nerd Font (如果路径在 config 中未指定，则使用默认资源)
        val nerdFontResId = R.font.jetbrains_mono_nerd_font_regular
        val nerdTypeface = try {
            // 优先从 config 的 nerdFontPath 加载
            config.nerdFontPath?.let { path ->
                val file = File(path)
                if (file.exists() && file.isFile) {
                    Typeface.createFromFile(file)
                } else {
                    // 如果路径无效，尝试加载默认资源
                    resources.getFont(nerdFontResId)
                }
            } ?: resources.getFont(nerdFontResId) // 如果路径为空，直接加载默认资源
        } catch (e: Exception) {
            // 加载失败
            null
        }
        textMetrics.setNerdTypeface(nerdTypeface)
    }
    
    private fun initGestureHandler() {
        gestureHandler = GestureHandler(
            context = context,
            onScale = { scale ->
                scaleFactor *= scale
                requestRender()
            },
            onScroll = { _, distanceY ->
                if (!selectionManager.hasSelection()) {
                    // 手动滚动时停止惯性滚动
                    if (!scroller.isFinished) {
                        scroller.abortAnimation()
                    }
                    
                    val newOffset = clampScrollOffset(scrollOffsetY + distanceY)
                    if (newOffset == scrollOffsetY) {
                        return@GestureHandler
                    }
                    scrollOffsetY = newOffset
                    
                    // 保存滚动位置
                    sessionId?.let { id ->
                        onScrollOffsetChanged?.invoke(id, scrollOffsetY)
                    }
                    
                    // 用户手动滚动，检测是否在底部
                    if (scrollOffsetY > 0f) {
                        isUserScrolling = true
                    } else {
                        // 滚动到底部，恢复自动滚动
                        isUserScrolling = false
                    }
                    
                    requestRender()
                }
            },
            onFling = { velocityX, velocityY ->
                if (!selectionManager.hasSelection()) {
                    // 开始惯性滚动
                    val em = emulator ?: return@GestureHandler
                    val fullContent = em.getFullContent()
                    val charHeight = textMetrics.charHeight
                    val maxScrollOffset = max(0f, fullContent.size * charHeight - getTerminalViewportHeight()).toInt()
                    val startY = clampScrollOffset(scrollOffsetY)
                    
                    if ((startY <= 0f && velocityY > 0f) ||
                        (startY >= maxScrollOffset.toFloat() && velocityY < 0f)
                    ) {
                        return@GestureHandler
                    }
                    
                    scroller.fling(
                        0, startY.toInt(),  // 起始位置
                        0, (-velocityY).toInt(),    // 速度（Y方向反转）
                        0, 0,                        // X范围
                        0, maxScrollOffset           // Y范围
                    )
                    requestRender()
                }
            },
            onDoubleTap = { x, y ->
                // 双击选择单词（可选实现）
                // 也可以用来切换输入法（仅全屏模式）
                if (isFullscreenMode) {
                    if (!hasFocus()) {
                        requestFocus()
                    }
                    showSoftKeyboard()
                }
            },
            onLongPress = { x, y ->
                startTextSelection(x, y)
            }
        )
    }
    
    /**
     * 设置终端模拟器
     */
    fun setEmulator(emulator: AnsiTerminalEmulator) {
        // Compose 每次重组都会调用本方法。重复设置同一个模拟器时若还去「保存当前偏移 + 恢复
        // 偏移」，就会把布局完成前的错误偏移（那时 height 为 0，clamp 上限等于整段内容高度）
        // 反复写回，表现为进页后终端内容整体被顶到屏幕上方、只剩一片黑。这里直接跳过。
        if (this.emulator === emulator) {
            return
        }
        // 保存当前模拟器的滚动位置
        sessionId?.let { id ->
            onScrollOffsetChanged?.invoke(id, scrollOffsetY)
        }

        // 移除旧的监听器
        removeEmulatorListeners()
        
        this.emulator = emulator
        // 恢复新模拟器的滚动位置
        sessionId?.let { id ->
            getScrollOffset?.invoke(id)?.let { offset ->
                scrollOffsetY = clampScrollOffset(offset)
            }
        }
        
        // 添加新的监听器
        emulatorChangeListener = {
            isDirty = true
            requestRender()
            // 通知无障碍服务内容已更新
            // 直接使用成员变量以兼容 API < 29 (getAccessibilityDelegate 是 API 29+)
            terminalAccessibilityDelegate.notifyContentChanged()
        }
        emulator.addChangeListener(emulatorChangeListener!!)
        
        // 添加新输出监听器（用于自动滚动到底部）
        emulatorNewOutputListener = {
            scheduleNewOutputDispatch()
        }
        emulator.addNewOutputListener(emulatorNewOutputListener!!)
        
        // 如果 Surface 已经创建，立即同步终端大小
        if (width > 0 && height > 0) {
            updateTerminalSize(width, height)
        }
        
        requestRender()
    }

    private fun removeEmulatorListeners() {
        val currentEmulator = emulator ?: return
        emulatorChangeListener?.let { listener ->
            currentEmulator.removeChangeListener(listener)
        }
        emulatorNewOutputListener?.let { listener ->
            currentEmulator.removeNewOutputListener(listener)
        }
        emulatorChangeListener = null
        emulatorNewOutputListener = null
    }

    private fun scheduleNewOutputDispatch() {
        var shouldPost = false
        synchronized(newOutputDispatchLock) {
            if (!newOutputDispatchPosted) {
                newOutputDispatchPosted = true
                shouldPost = true
            }
        }
        if (!shouldPost) return
        if (isAttachedToWindow) {
            postOnAnimation(newOutputDispatchRunnable)
        } else {
            post(newOutputDispatchRunnable)
        }
    }

    /**
     * 计算当前可用的最大滚动偏移
     */
    private fun getMaxScrollOffset(): Float {
        val em = emulator ?: return 0f
        // 还没布局（height 为 0）时视口高度算成 0，上限会等于整段内容高度，
        // 任何「恢复上一次滚动位置」都会把内容整体推出屏幕。此时不接受滚动。
        if (height <= 0) return 0f
        val contentHeight = em.getFullContent().size * textMetrics.charHeight
        return max(0f, contentHeight - getTerminalViewportHeight())
    }

    /**
     * 将滚动偏移限制在合法范围内
     */
    private fun clampScrollOffset(offset: Float): Float {
        if (emulator == null) {
            return offset.coerceAtLeast(0f)
        }
        return offset.coerceIn(0f, getMaxScrollOffset())
    }
    
    /**
     * 设置滚动偏移（用于恢复会话滚动位置）
     */
    fun setScrollOffset(offset: Float) {
        scrollOffsetY = clampScrollOffset(offset)
        requestRender()
    }
    
    /**
     * 获取当前滚动偏移（用于保存会话滚动位置）
     */
    fun getScrollOffset(): Float = scrollOffsetY
    
    /**
     * 滚动到底部
     */
    fun scrollToBottom() {
        needScrollToBottom = true
        isUserScrolling = false // 恢复自动滚动
        isDirty = true
        requestRender()
    }
    
    /**
     * 设置 PTY（用于窗口大小同步）
     */
    fun setPty(pty: com.ai.assistance.operit.terminal.Pty?) {
        this.pty = pty
        // 如果 Surface 已经创建且有 emulator，立即同步终端大小
        if (width > 0 && height > 0 && emulator != null) {
            updateTerminalSize(width, height)
        }
    }
    
    /**
     * 设置全屏模式
     */
    fun setFullscreenMode(isFullscreen: Boolean) {
        isFullscreenMode = isFullscreen
        // 非全屏模式下禁用焦点和输入法
        isFocusable = isFullscreen
        isFocusableInTouchMode = isFullscreen
    }
    
    /**
     * 设置输入回调
     */
    fun setInputCallback(callback: (String) -> Unit) {
        this.inputCallback = callback
    }
    
    /**
     * 设置点击时请求显示软键盘的回调（仅非全屏模式使用）
     */
    fun setOnRequestShowKeyboard(callback: (() -> Unit)?) {
        this.onRequestShowKeyboard = callback
    }
    
    /**
     * 设置会话ID和滚动位置回调
     */
    fun setSessionScrollCallbacks(
        sessionId: String?,
        onScrollOffsetChanged: ((String, Float) -> Unit)?,
        getScrollOffset: ((String) -> Float)?
    ) {
        this.sessionId = sessionId
        this.onScrollOffsetChanged = onScrollOffsetChanged
        this.getScrollOffset = getScrollOffset
        
        // 如果设置了sessionId，立即恢复滚动位置
        sessionId?.let { id ->
            getScrollOffset?.invoke(id)?.let { offset ->
                scrollOffsetY = clampScrollOffset(offset)
                requestRender()
            }
        }
    }

    fun setTabBarState(
        tabs: List<TerminalTabRenderItem>,
        currentTabId: String?,
        onTabClick: ((String) -> Unit)?,
        onTabClose: ((String) -> Unit)?,
        onNewTab: (() -> Unit)?
    ) {
        val newTabCount = tabs.size
        this.tabs = tabs.toList()
        this.currentTabId = currentTabId
        this.onTabClick = onTabClick
        this.onTabClose = onTabClose
        this.onNewTab = onNewTab
        if (!hasTabBar()) {
            recycleTabVelocityTracker()
            resetTabTouchState()
            if (!tabScroller.isFinished) {
                tabScroller.abortAnimation()
            }
            pendingScrollToEndAfterNewTab = false
            pendingScrollToEndMinTabCount = 0
        }
        tabContentWidth = measureTabContentWidth()
        clampTabScrollOffset()
        if (pendingScrollToEndAfterNewTab && newTabCount >= pendingScrollToEndMinTabCount) {
            val endOffset = getMaxTabScrollOffset()
            if (!tabScroller.isFinished) {
                tabScroller.abortAnimation()
            }
            if (endOffset > 0f && endOffset != tabScrollOffsetX) {
                val delta = (endOffset - tabScrollOffsetX).toInt()
                if (delta != 0) {
                    tabScroller.startScroll(tabScrollOffsetX.toInt(), 0, delta, 0, 220)
                } else {
                    tabScrollOffsetX = endOffset
                }
            } else {
                tabScrollOffsetX = endOffset
            }
            pendingScrollToEndAfterNewTab = false
            pendingScrollToEndMinTabCount = 0
            requestRender()
        }
        if (getMaxTabScrollOffset() <= 0f && !tabScroller.isFinished) {
            tabScroller.abortAnimation()
            tabScrollOffsetX = 0f
        }
        if (width > 0 && height > 0 && emulator != null) {
            updateTerminalSize(width, height)
        }
        requestRender()
        terminalAccessibilityDelegate.notifyContentChanged()
    }

    fun setImeViewportState(animationOffsetPx: Int, committedBottomInsetPx: Int) {
        val normalizedAnimationOffsetPx = animationOffsetPx.coerceAtLeast(0)
        val normalizedCommittedInsetPx = committedBottomInsetPx.coerceAtLeast(0)
        var sizeChanged = false
        var renderChanged = false

        if (imeAnimationOffsetPx != normalizedAnimationOffsetPx) {
            imeAnimationOffsetPx = normalizedAnimationOffsetPx
            renderChanged = true
        }
        if (this.committedImeBottomInsetPx != normalizedCommittedInsetPx) {
            val oldCommitted = this.committedImeBottomInsetPx
            // 提交前屏幕上的位移是按旧 committed 画出来的，先抓下来给 updateTerminalSize
            // 算残余；等它自己去读时 (animated - committed) 已经归零，会算反（见 offsetBeforeCommitPx）。
            offsetBeforeCommitPx = getTerminalVisualOffsetY()
            this.committedImeBottomInsetPx = normalizedCommittedInsetPx
            sizeChanged = true
            renderChanged = true
            // (c) 视口让位提交帧：这一帧之后行列数与滚动锚点都会变，是「跳一下」的现场
            imeDbg(
                "vb.commitViewport committed=" + oldCommitted + "->" + normalizedCommittedInsetPx +
                    " animated=" + imeAnimatedInsetPx +
                    " height=" + height +
                    " scroll=" + scrollOffsetY.toInt()
            )
        }

        if (sizeChanged && width > 0 && height > 0 && emulator != null) {
            updateTerminalSize(width, height)
        }
        // 兜底：视口尺寸无效或 emulator 还没就绪时 updateTerminalSize 根本不会跑，
        // 这份快照不能留给下一次无关的 resize。
        offsetBeforeCommitPx = null
        if (renderChanged || sizeChanged) {
            // 父层提交的是「键盘真实高度」：顺带把跟手位移对齐到窗口当前 insets，
            // 避免在页面切后台期间收键盘、动画回调没送达而留下一个用旧值的位移。
            refreshImeAnimatedInsetIfSupported()
        }
        if (renderChanged) {
            requestRender()
        }
    }

    /**
     * 安装 IME 动画逐帧回调（API 30+）。
     *
     * 之前「键盘动画跟手」只能在组合层做：要么组合期读 WindowInsets.ime（每帧重组整棵树），
     * 要么 snapshotFlow 稳定后提交（动画结束后才一次性跳位）。这里换成 View 层的
     * WindowInsetsAnimation.Callback：系统按帧回调 onProgress，拿到的是插值后的 inset，
     * 我们只写一个 @Volatile Int 并置脏，渲染线程下一帧就按新位移重绘。
     * 全程不碰 Compose 组合、不碰布局、不改行列数。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun installImeAnimationCallback() {
        if (imeAnimationCallbackInstalled) return
        imeAnimationCallbackInstalled = true
        val callback =
            object : WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onStart(
                    animation: WindowInsetsAnimation,
                    bounds: WindowInsetsAnimation.Bounds
                ): WindowInsetsAnimation.Bounds {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                        imeAnimationRunning = true
                        // (a) 回调来没来：有这一条就说明 WindowInsetsAnimation 派发是通的
                        imeDbg(
                            "vb.onStart durMs=" + animation.durationMillis +
                                " followEnabled=" + imeFollowEnabled +
                                " animated=" + imeAnimatedInsetPx +
                                " committed=" + committedImeBottomInsetPx +
                                " height=" + height +
                                " contentTop=" + getTerminalContentTop().toInt()
                        )
                    }
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>
                ): WindowInsets {
                    val imeRunning =
                        runningAnimations.any { it.typeMask and WindowInsets.Type.ime() != 0 }
                    if (imeRunning) {
                        applyAnimatedImeInsets(insets)
                    } else {
                        refreshImeAnimatedInset()
                    }
                    // (a) 逐帧数值：100ms 一条，看 imeBottom 是否真的在动（还是一步到位）
                    val now = System.currentTimeMillis()
                    if (imeRunning && now - imeDbgProgressMs >= 100L) {
                        imeDbgProgressMs = now
                        imeDbg(
                            "vb.onProgress imeBottom=" + insets.getInsets(WindowInsets.Type.ime()).bottom +
                                " navBottom=" + insets.getInsets(WindowInsets.Type.navigationBars()).bottom +
                                " animated=" + imeAnimatedInsetPx +
                                " committed=" + committedImeBottomInsetPx +
                                " offsetY=" + getTerminalVisualOffsetY() +
                                " blank=" + getContentBlankBelowViewportBottom().toInt()
                        )
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                    imeAnimationRunning = false
                    refreshImeAnimatedInset()
                    imeDbg(
                        "vb.onEnd animated=" + imeAnimatedInsetPx +
                            " committed=" + committedImeBottomInsetPx +
                            " offsetY=" + getTerminalVisualOffsetY()
                    )
                }
            }
        setWindowInsetsAnimationCallback(callback)
    }

    /**
     * 开关「画布按 IME 动画自己跟手」。
     *
     * 只有宿主（TerminalHome）自己处理输入法让位时才打开；关闭时位移恒为 0，
     * 行为回到旧版（由外层布局/外层 padding 决定）。
     */
    fun setImeFollowEnabled(enabled: Boolean) {
        if (imeFollowEnabled == enabled) return
        imeFollowEnabled = enabled
        if (!enabled) {
            imeAnimatedInsetPx = 0
            // 关掉跟手也就没人消费残余位移了，别留一个永远衰减不完的值
            commitResidualPx = 0f
            requestRender()
        } else {
            refreshImeAnimatedInsetIfSupported()
            requestRender()
        }
    }

    /** 用某一帧的实际 insets 更新跟手位移。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyAnimatedImeInsets(insets: WindowInsets) {
        if (!imeFollowEnabled) {
            if (imeAnimatedInsetPx != 0) {
                imeAnimatedInsetPx = 0
                requestRender()
            }
            return
        }
        val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
        val navigationBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        val target = (imeBottom - navigationBottom).coerceAtLeast(0)
        if (target != imeAnimatedInsetPx) {
            val old = imeAnimatedInsetPx
            imeAnimatedInsetPx = target
            // (b) 值变化即记一条：证明画布确实按新位移请求了重绘，并带上实际用的位移
            imeDbg(
                "vb.apply animated=" + old + "->" + target +
                    " imeBottom=" + imeBottom +
                    " navBottom=" + navigationBottom +
                    " committed=" + committedImeBottomInsetPx +
                    " offsetY=" + getTerminalVisualOffsetY()
            )
            requestRender()
        }
    }

    /** 非动画期从窗口 insets 同步一次当前高度（进页时键盘已经弹开、旋转、动画结束等）。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun refreshImeAnimatedInset() {
        val insets = rootWindowInsets ?: return
        applyAnimatedImeInsets(insets)
    }

    private fun refreshImeAnimatedInsetIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !imeAnimationRunning) {
            refreshImeAnimatedInset()
        }
    }

    private fun hasTabBar(): Boolean = tabs.isNotEmpty() || onNewTab != null
    private fun hasAddTabButton(): Boolean = onNewTab != null

    private fun getTerminalContentTop(): Float = if (hasTabBar()) tabBarHeightPx else 0f
    /**
     * 画布内容相对视口要往上抬多少像素（纯绘制层位移，不改布局、不改行列数）。
     *
     * 全场只有这一个位移量，三处消费者（裁剪、行范围、命中映射）必须用同一个值：
     *   O = imeAnimationOffsetPx + (animated - committed) + residual
     *
     * - (animated - committed)：键盘顶相对「已提交视口底」多出来的那截。它是提交前后连续的
     *   关键：提交那一帧 animated 不变、committed 追上来，这一项归零，但同一帧 scrollOffsetY
     *   被重锚点到新的贴底位置（+1122），屏幕位置 = -scroll - O 两边严格相等。
     * - residual：提交帧 scroll 跳变量与「这一项归零」对不齐时的差值，由 updateTerminalSize
     *   记下、120ms 缓出到 0（见 commitResidualPx）。正常贴底时它就是 0。
     *
     * 两个「不再」：
     * - 不再扣 blank（内容底到视口底的空白）。那是「内容不足一屏」才成立的东西，而那种情况
     *   已经整块交给下面的例外分支；blank 现在只喂诊断日志。
     * - 收键盘时不再恒为 0。恒为 0 = 内容原地待着，等 committed 归零、scroll 重锚点那一帧
     *   屏幕位置突然差出整个键盘高度（用户看到的就是「等输入法收完再跳到最下方」）。
     *   允许负位移之后，内容跟着键盘往下走，配合整窗裁剪不会画错。
     *
     * 为什么位移不能用「贴底才跟手」的 0.5px 判据（最早的 bug）：终端行数是 floor 量化的
     * （rows = floor(视口高 / 行高)），内容底天然比视口底矮 (0, 行高) 像素，于是
     * isContentPinnedToViewportBottom() 在「无回滚 + 未滚动」这个最常见状态下几乎恒为 false
     * → 动画期间位移恒为 0，等沉降提交时滚动锚点一次性加上整个键盘高度 → 「停一下再跳」。
     */
    private fun getTerminalVisualOffsetY(): Int {
        if (!imeFollowEnabled) return imeAnimationOffsetPx
        // 例外（唯一一个）：内容装不满一屏时，键盘开合只是在盖/露下方那片空区域，内容原地
        // 不动才是对的——跟着动会把仅有的几行顶出屏幕顶部，或者往下推到屏幕外。
        // 判据用 committed 口径，动画期间视口高度恒定；但提交那一帧视口高度正好变了，判据
        // 可能翻转，所以残余要照带：它正是「保住提交前屏幕位置」的那一份，丢了就是一跳。
        if (!isContentOverflowsViewport()) {
            return (imeAnimationOffsetPx + getCommitResidualPx()).toInt()
        }
        val delta = (imeAnimatedInsetPx - committedImeBottomInsetPx).toFloat()
        val followed = delta + getCommitResidualPx()
        if (delta > 0f && followed <= 0f) {
            logImeNoFollow()
        }
        return (followed + imeAnimationOffsetPx).toInt()
    }

    /**
     * 提交帧残余位移的当前值：120ms ease-out 衰减到 0。
     *
     * 读的时候现算、不靠定时器推进：绘制线程每帧读一次就自然衰减，暂停/后台期间也不会
     * 白跑动画；时间只用于算衰减系数，所以即使中间掉帧，最终也一定回到 0。
     */
    private fun getCommitResidualPx(): Float {
        if (commitResidualPx == 0f) return 0f
        val elapsed =
            (android.os.SystemClock.uptimeMillis() - commitResidualStartMs).toFloat()
        if (elapsed >= commitResidualDurationMs.toFloat()) return 0f
        val progress = (elapsed / commitResidualDurationMs.toFloat()).coerceIn(0f, 1f)
        // ease-out：开头掉得最快、尾巴很轻，观感是「一下站到位」而不是匀速滑过去
        val remain = (1f - progress) * (1f - progress)
        return commitResidualPx * remain
    }

    /**
     * 内容是否比视口高（能滚动）。
     *
     * 只有这种状态才需要在键盘动画期跟手：内容不足一屏时，提示行下面是空的，
     * 键盘开合只是在盖/露空区域，内容跟着动反而会把仅有的几行顶出屏幕。
     */
    private fun isContentOverflowsViewport(): Boolean {
        val em = emulator ?: return false
        val charHeight = textMetrics.charHeight
        if (charHeight <= 0f || height <= 0) return false
        // 同样按光标行算：光标下方那些补位的空行不算内容。
        val contentHeight = (em.getHistorySize() + em.getCursorY() + 1) * charHeight
        // ★ 容差是半行而不是 0.5px：行数按 floor(视口高 / 行高) 量化，内容贴底时光标行底
        // 天然比视口底矮 (0, 行高) 像素，用 0.5px 会把「差半行」的正常贴底判成内容不足一屏，
        // 位移退回 0，又变回这一轮要修的不跟手。半行以内都算装得下。
        return contentHeight > getTerminalViewportHeight(height.toFloat()) + charHeight * 0.5f
    }

    /**
     * 「位移为 0」的节流诊断：区分两种完全不同的原因——
     * animated=0 → 回调没来/没拿到逐帧值；animated>0 但 blank 大 → 键盘还没盖到内容（不动是对的）。
     * 同时打出旧判据 pinned 的取值，用来验证「旧逻辑为什么不动」。
     */
    private fun logImeNoFollow() {
        // 动画期间才有诊断价值：键盘弹满后 animated == committed，位移恒为 0 是稳态，
        // 再记下去就是每 100ms 一条的日志洪水（真机上实测刷屏）。
        if (imeAnimatedInsetPx <= committedImeBottomInsetPx) return
        val now = System.currentTimeMillis()
        if (now - imeDbgNoFollowMs < 100L) return
        imeDbgNoFollowMs = now
        imeDbg(
            "vb.noFollow animated=" + imeAnimatedInsetPx +
                " committed=" + committedImeBottomInsetPx +
                " blank=" + getContentBlankBelowViewportBottom().toInt() +
                " viewportBottom=" + getTerminalViewportBottom().toInt() +
                " contentBottom=" + getContentUsedBottom().toInt() +
                " pinned=" + isContentPinnedToViewportBottom() +
                " mode=" + (if (imeFollowByCoveredPx) "covered" else "pinned")
        )
    }

    /** 视口底到内容底的空白像素（含行高 floor 量化余量）：内容贴底时为 0，不足一屏时为正。 */
    private fun getContentBlankBelowViewportBottom(): Float =
        (getTerminalViewportBottom() - getContentUsedBottom()).coerceAtLeast(0f)

    /**
     * 内容实际用到的底部 y。
     *
     * 必须按**光标行**算，不能按缓冲区末尾算：终端模拟器会把光标下方补成空行，
     * 用 fullContent.size 会把那些空行当成内容 → blank 恒为 0 →
     * 跟手时把内容多抬一大截，等沉降提交再跳回来（就是「跟得慢、末尾突一下」的根因）。
     */
    private fun getContentUsedBottom(): Float {
        val em = emulator ?: return 0f
        val charHeight = textMetrics.charHeight
        if (charHeight <= 0f || height <= 0) return 0f
        val cursorRow = em.getHistorySize() + em.getCursorY()
        return getTerminalContentTop() + (cursorRow + 1) * charHeight - scrollOffsetY
    }

    /** 内容是否铺满视口（旧口径，仅在 imeFollowByCoveredPx=false 的 A/B 对照里使用）。 */
    private fun isContentPinnedToViewportBottom(): Boolean {
        val em = emulator ?: return false
        if (textMetrics.charHeight <= 0f || height <= 0) return false
        return getContentUsedBottom() >= getTerminalViewportBottom() - 0.5f
    }

    private fun getTerminalViewportBottom(totalHeight: Float = height.toFloat()): Float =
        (totalHeight - committedImeBottomInsetPx).coerceAtLeast(getTerminalContentTop())

    private fun getTerminalViewportHeight(totalHeight: Float = height.toFloat()): Float =
        (getTerminalViewportBottom(totalHeight) - getTerminalContentTop()).coerceAtLeast(0f)

    private fun getTabsViewportWidth(totalWidth: Float = width.toFloat()): Float {
        val addButtonArea = if (hasAddTabButton()) addButtonSizePx + tabSpacingPx else 0f
        return (totalWidth - tabHorizontalPaddingPx * 2f - addButtonArea).coerceAtLeast(0f)
    }

    private fun measureTabWidth(tab: TerminalTabRenderItem): Float {
        val textWidth = tabTextPaint.measureText(tab.title)
        val closeSpace = if (tab.canClose) closeButtonSizePx + tabSpacingPx else 0f
        val desiredWidth = textWidth + tabTextHorizontalPaddingPx * 2f + closeSpace
        return desiredWidth.coerceIn(tabMinWidthPx, tabMaxWidthPx)
    }

    private fun measureTabContentWidth(): Float {
        if (tabs.isEmpty()) return 0f
        var width = 0f
        tabs.forEachIndexed { index, tab ->
            if (index > 0) width += tabSpacingPx
            width += measureTabWidth(tab)
        }
        return width
    }

    private fun clampTabScrollOffset() {
        val maxOffset = max(0f, tabContentWidth - getTabsViewportWidth())
        tabScrollOffsetX = tabScrollOffsetX.coerceIn(0f, maxOffset)
    }
    
    /**
     * 设置缩放回调
     */
    fun setScaleCallback(callback: (Float) -> Unit) {
        // 当缩放因子变化时调用
        gestureHandler = GestureHandler(
            context = context,
            onScale = { scale ->
                scaleFactor *= scale
                callback(scaleFactor)
                // 更新字体指标和终端大小
                updateTerminalSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
                requestRender()
            },
            onScroll = { _, distanceY ->
                if (!selectionManager.hasSelection()) {
                    // 手动滚动时停止惯性滚动
                    if (!scroller.isFinished) {
                        scroller.abortAnimation()
                    }
                    
                    val newOffset = clampScrollOffset(scrollOffsetY + distanceY)
                    if (newOffset == scrollOffsetY) {
                        return@GestureHandler
                    }
                    scrollOffsetY = newOffset
                    
                    // 保存滚动位置
                    sessionId?.let { id ->
                        onScrollOffsetChanged?.invoke(id, scrollOffsetY)
                    }
                    
                    // 用户手动滚动，检测是否在底部
                    if (scrollOffsetY > 0f) {
                        isUserScrolling = true
                    } else {
                        // 滚动到底部，恢复自动滚动
                        isUserScrolling = false
                    }
                    
                    requestRender()
                }
            },
            onFling = { velocityX, velocityY ->
                if (!selectionManager.hasSelection()) {
                    // 开始惯性滚动
                    val em = emulator ?: return@GestureHandler
                    val fullContent = em.getFullContent()
                    val charHeight = textMetrics.charHeight
                    val maxScrollOffset = max(0f, fullContent.size * charHeight - getTerminalViewportHeight()).toInt()
                    val startY = clampScrollOffset(scrollOffsetY)
                    
                    if ((startY <= 0f && velocityY > 0f) ||
                        (startY >= maxScrollOffset.toFloat() && velocityY < 0f)
                    ) {
                        return@GestureHandler
                    }
                    
                    scroller.fling(
                        0, startY.toInt(),  // 起始位置
                        0, (-velocityY).toInt(),    // 速度（Y方向反转）
                        0, 0,                        // X范围
                        0, maxScrollOffset           // Y范围
                    )
                    requestRender()
                }
            },
            onDoubleTap = { x, y ->
                // 双击选择单词（可选实现）
            },
            onLongPress = { x, y ->
                startTextSelection(x, y)
            }
        )
    }
    
    /**
     * 设置性能监控回调
     */
    fun setPerformanceCallback(callback: (fps: Float, frameTime: Long) -> Unit) {
        // 性能监控逻辑可以在RenderThread中实现
        // 这里暂时留空，可以后续扩展
    }
    
    /**
     * 设置渲染配置
     */
    fun setConfig(newConfig: RenderConfig) {
        val oldTypeface = config.typeface
        val oldNerdFontPath = config.nerdFontPath
        val oldFontSize = config.fontSize

        // 更新配置
        config = newConfig

        // 如果字体或字体大小改变了，重新加载并更新指标
        if (oldTypeface != newConfig.typeface ||
            oldNerdFontPath != newConfig.nerdFontPath ||
            oldFontSize != newConfig.fontSize
        ) {
            loadAndApplyFont()
            updateTerminalSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
            requestRender()
        } else {
            // 其他配置改变，也需要重新渲染
            requestRender()
        }
    }
    
    /**
     * 更新字体大小
     */
    private fun updateFontSize() {
        // 字体大小的更新现在由 textMetrics.updateFromRenderConfig 处理
        // 这里可以保留用于缩放手势等场景
        textMetrics.updateFromRenderConfig(config.copy(fontSize = config.fontSize * scaleFactor))
        updateTerminalSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        requestRender()
    }
    
    /**
     * 请求渲染
     */
    private fun requestRender() {
        isDirty = true
    }

    /**
     * 外部强制刷新一次。
     *
     * 冷进页面时偶发画布全黑、触摸一次才出内容：此时要么渲染线程没起来，
     * 要么最近一帧在 emulator/textMetrics 还没就绪时被画成了空背景，而
     * isDirty 已被消费、后续又没有新输出触发重绘。这里把三件事一起兜住：
     * 置脏、解除暂停唤醒线程、线程不在就重启，并补一次尺寸同步。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // IME 逐帧跟手：API 30+ 由本 View 自己订阅窗口 insets 动画，进页时先同步一次静态高度。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            installImeAnimationCallback()
            post { refreshImeAnimatedInsetIfSupported() }
        }
        // 注意：imeFollowEnabled 由 Compose 侧在更早的 factory/update 里设置，
        // 因此这里 attach 时的首次 refresh 已经能拿到正确开关。
        // 首帧兜底：进页后补两次刷新，覆盖「渲染线程已出帧但窗口没合成」的时序。
        postDelayed({ refreshNow() }, 400L)
        postDelayed({ refreshNow() }, 1600L)
    }

    fun refreshNow() {
        isDirty = true
        synchronized(pauseLock) {
            isPaused = false
            pauseLock.notifyAll()
        }
        if (width > 0 && height > 0 && emulator != null) {
            updateTerminalSize(width, height)
        }
        if (width > 0 && height > 0 && (renderThread == null || renderThread?.isAlive != true)) {
            startRenderThread()
        }
    }
    
    // === SurfaceHolder.Callback 实现 ===
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("CanvasTerminalView", "onSizeChanged: ${w}x${h} (old: ${oldw}x${oldh})")
        
        // 从无效尺寸恢复时，清空缓存以确保 PTY resize 被触发
        if (w > 0 && h > 0 && (oldw <= 0 || oldh <= 0)) {
            cachedRows = 0
            cachedCols = 0
            Log.d("CanvasTerminalView", "onSizeChanged: Cleared terminal size cache due to 0->Normal transition")
        }

        if (w <= 0 || h <= 0) {
            Log.w("CanvasTerminalView", "onSizeChanged: Invalid size, stopping render thread")
            stopRenderThread()
            synchronized(pauseLock) { isPaused = true }
        } else {
            // 如果线程不存在（之前被销毁了），这里不急着启动，交给 surfaceChanged 统一处理
            // 只是确保 pause 状态解除
            synchronized(pauseLock) { 
                isPaused = false
                pauseLock.notifyAll()
            }
            
            // 尺寸发生变化时，重新计算并同步终端大小到 PTY
            // 这样可以确保终端窗口大小与 View 大小保持一致
            if (oldw != w || oldh != h) {
                Log.d("CanvasTerminalView", "onSizeChanged: Triggering updateTerminalSize(${w}x${h})")
                tabContentWidth = measureTabContentWidth()
                clampTabScrollOffset()
                updateTerminalSize(w, h)
                // 尺寸变化（旋转 / 分屏）后重新对齐当前键盘高度，避免继续用旧位移。
                refreshImeAnimatedInsetIfSupported()
            }
        }
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("CanvasTerminalView", "surfaceCreated")
        // surfaceCreated 时如果尺寸未知或为0，startRenderThread 会被调用但 run 循环会等待
        // 但为了安全，我们尽量由 surfaceChanged 驱动
        if (width > 0 && height > 0) {
            startRenderThread()
        }
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("CanvasTerminalView", "surfaceChanged: ${width}x${height}")
        
        // 如果尺寸无效，停止渲染
        if (width <= 0 || height <= 0) {
            Log.d("CanvasTerminalView", "surfaceChanged: Invalid size, stopping render thread")
            stopRenderThread()
            synchronized(pauseLock) {
                isPaused = true
            }
            return
        }
        
        // 恢复状态
        synchronized(pauseLock) {
            isPaused = false
            pauseLock.notifyAll()
        }
        
        // 确保线程运行
        if (renderThread == null || !renderThread!!.isAlive) {
            Log.d("CanvasTerminalView", "surfaceChanged: Starting render thread for valid size")
            startRenderThread()
        }

        // 更新终端窗口大小
        tabContentWidth = measureTabContentWidth()
        clampTabScrollOffset()
        updateTerminalSize(width, height)
        
        requestRender()
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("CanvasTerminalView", "surfaceDestroyed")
        stopRenderThread()
        // 清理方向键长按回调，避免内存泄漏
        handleArrowKeyUp()
    }
    
    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    // === 渲染线程 ===
    
    private fun startRenderThread() {
        Log.d("CanvasTerminalView", "startRenderThread: Starting new thread")
        stopRenderThread()
        renderThread = RenderThread(holder).apply {
            start()
        }
    }
    
    /**
     * 停止渲染线程
     * 在视图被销毁或移除时调用，避免SurfaceView锁竞争导致ANR
     */
    fun stopRenderThread() {
        Log.d("CanvasTerminalView", "stopRenderThread: Stopping thread")
        renderThread?.let { thread ->
            Log.d("CanvasTerminalView", "stopRenderThread: Requesting stop for thread ${thread.id}")
            thread.stopRendering()
            thread.interrupt()
            try {
                thread.join(1000)
                Log.d("CanvasTerminalView", "stopRenderThread: Thread joined")
            } catch (e: InterruptedException) {
                Log.e("CanvasTerminalView", "stopRenderThread: Interrupted while joining", e)
                e.printStackTrace()
            }
        }
        renderThread = null
    }

    /**
     * 释放视图运行时资源，避免离开页面后持有外部对象引用。
     */
    fun release() {
        stopRenderThread()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && imeAnimationCallbackInstalled) {
            imeAnimationCallbackInstalled = false
            setWindowInsetsAnimationCallback(null)
        }
        imeAnimationRunning = false
        imeAnimatedInsetPx = 0
        handleArrowKeyUp()
        autoScrollRunnable?.let { handler.removeCallbacks(it) }
        autoScrollRunnable = null
        removeCallbacks(newOutputDispatchRunnable)
        synchronized(newOutputDispatchLock) {
            newOutputDispatchPosted = false
        }
        removeEmulatorListeners()
        emulator = null
        actionMode?.finish()
        actionMode = null
        inputCallback = null
        onRequestShowKeyboard = null
        onScrollOffsetChanged = null
        getScrollOffset = null
        sessionId = null
        tabs = emptyList()
        currentTabId = null
        onTabClick = null
        onTabClose = null
        onNewTab = null
        tabHitSnapshot = TabHitSnapshot()
        pendingScrollToEndAfterNewTab = false
        pendingScrollToEndMinTabCount = 0
        if (!tabScroller.isFinished) {
            tabScroller.abortAnimation()
        }
        recycleTabVelocityTracker()
        resetTabTouchState()
    }
    
    private inner class RenderThread(private val surfaceHolder: SurfaceHolder) : Thread("TerminalRenderThread") {
        @Volatile
        private var running = false
        
        override fun start() {
            Log.d("CanvasTerminalView", "RenderThread: start() called")
            running = true
            super.start()
        }
        
        fun stopRendering() {
            Log.d("CanvasTerminalView", "RenderThread: stopRendering() called")
            running = false
        }
        
        override fun run() {
            Log.d("CanvasTerminalView", "RenderThread started")
            var lastRenderTime = System.currentTimeMillis()
            val targetFrameTime = (1000L / config.targetFps.coerceAtLeast(1))
            
            while (running) {
                try {
                    // === 1. 状态检查 ===
                    // 尺寸无效：休眠等待
                    if (width <= 0 || height <= 0) {
                        sleep(200)
                        continue
                    }
                    
                    // 暂停状态：等待唤醒
                    if (isPaused) {
                        synchronized(pauseLock) {
                            while (isPaused && running) {
                                pauseLock.wait()
                            }
                        }
                        lastRenderTime = System.currentTimeMillis() // 重置时间基准
                        continue
                    }
                    
                    // === 2. 帧率控制 ===
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastRender = currentTime - lastRenderTime
                    
                    // 未到渲染时间且无脏数据：短暂休眠
                    if (!isDirty && timeSinceLastRender < targetFrameTime) {
                        sleep(5)
                        continue
                    }
                    
                    // === 3. 渲染 ===
                    // 字体度量尚未初始化时先补一次，否则 drawTerminal 会直接返回、只留下黑背景。
                    if (emulator != null && textMetrics.charHeight <= 0f) {
                        textMetrics.updateFromRenderConfig(config.copy(fontSize = config.fontSize * scaleFactor))
                    }
                    var canvas: Canvas? = null
                    try {
                        canvas = surfaceHolder.lockCanvas()
                        if (canvas != null) {
                            drawTerminal(canvas)
                            isDirty = false
                            lastRenderTime = currentTime
                        } else {
                            // Canvas 不可用，休眠后重试
                            sleep(10)
                        }
                    } catch (e: Exception) {
                        Log.e("CanvasTerminalView", "Render error", e)
                    } finally {
                        canvas?.let {
                            try {
                                surfaceHolder.unlockCanvasAndPost(it)
                            } catch (e: Exception) {
                                Log.e("CanvasTerminalView", "Failed to unlock canvas", e)
                            }
                        }
                    }

                    // === 4. 帧时间控制 ===
                    if (canvas != null) {
                        val frameTime = System.currentTimeMillis() - currentTime
                        val sleepTime = targetFrameTime - frameTime
                        if (sleepTime > 0) {
                            sleep(sleepTime)
                        }
                    }
                    
                } catch (e: InterruptedException) {
                    Log.d("CanvasTerminalView", "RenderThread interrupted")
                    break
                }
            }
            
            Log.d("CanvasTerminalView", "RenderThread stopped")
        }
    }
    
    private fun drawTabBar(canvas: Canvas) {
        if (!hasTabBar()) {
            tabHitSnapshot = TabHitSnapshot()
            tabContentWidth = 0f
            tabScrollOffsetX = 0f
            if (!tabScroller.isFinished) {
                tabScroller.abortAnimation()
            }
            return
        }

        val barHeight = min(tabBarHeightPx, canvas.height.toFloat())
        if (barHeight <= 0f) return

        tabBarBackgroundPaint.color = tabBarBackgroundColor
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), barHeight, tabBarBackgroundPaint)

        val rowTop = tabVerticalPaddingPx
        val rowBottom = (barHeight - tabVerticalPaddingPx).coerceAtLeast(rowTop + 1f)
        val addButtonRect = RectF()
        val addButtonLeft: Float
        if (hasAddTabButton()) {
            val addButtonRight = (canvas.width.toFloat() - tabHorizontalPaddingPx).coerceAtLeast(0f)
            addButtonLeft = (addButtonRight - addButtonSizePx).coerceAtLeast(0f)
            val addButtonTop = rowTop + ((rowBottom - rowTop - addButtonSizePx) / 2f).coerceAtLeast(0f)
            val addButtonBottom = (addButtonTop + addButtonSizePx).coerceAtMost(rowBottom)
            addButtonRect.set(addButtonLeft, addButtonTop, addButtonRight, addButtonBottom)
            val isAddPressed =
                isTabTouchActive &&
                    !tabTouchMoved &&
                    activeTabTouchTarget is TabTouchTarget.NewTab &&
                    addButtonRect.contains(tabTouchX, tabTouchY)
            drawAddTabButton(canvas, addButtonRect, isPressed = isAddPressed)
        } else {
            addButtonLeft = (canvas.width.toFloat() - tabHorizontalPaddingPx).coerceAtLeast(0f)
        }

        val tabsLeft = tabHorizontalPaddingPx
        val tabsRight = if (hasAddTabButton()) {
            (addButtonLeft - tabSpacingPx).coerceAtLeast(tabsLeft)
        } else {
            (canvas.width.toFloat() - tabHorizontalPaddingPx).coerceAtLeast(tabsLeft)
        }
        if (tabsRight <= tabsLeft) {
            tabHitSnapshot = TabHitSnapshot(addButtonRect = RectF(addButtonRect))
            tabContentWidth = 0f
            tabScrollOffsetX = 0f
            if (!tabScroller.isFinished) {
                tabScroller.abortAnimation()
            }
            return
        }

        tabContentWidth = measureTabContentWidth()
        clampTabScrollOffset()
        if (tabScroller.computeScrollOffset()) {
            val maxOffset = getMaxTabScrollOffset()
            val nextOffset = tabScroller.currX.toFloat().coerceIn(0f, maxOffset)
            if (nextOffset != tabScrollOffsetX) {
                tabScrollOffsetX = nextOffset
                isDirty = true
            }
            if (maxOffset <= 0f && !tabScroller.isFinished) {
                tabScroller.abortAnimation()
                tabScrollOffsetX = 0f
            }
        }
        val nextTabNodes = ArrayList<TabHitNode>(tabs.size)

        canvas.save()
        canvas.clipRect(tabsLeft, rowTop, tabsRight, rowBottom)
        try {
            var cursorX = tabsLeft - tabScrollOffsetX
            tabs.forEach { tab ->
                val tabWidth = measureTabWidth(tab)
                val tabRect = RectF(cursorX, rowTop, cursorX + tabWidth, rowBottom)
                val closeRect = if (tab.canClose) {
                    val closeRight = tabRect.right - tabTextHorizontalPaddingPx * 0.5f
                    val closeLeft = closeRight - closeButtonSizePx
                    val closeTop = tabRect.centerY() - closeButtonSizePx / 2f
                    RectF(closeLeft, closeTop, closeRight, closeTop + closeButtonSizePx)
                } else {
                    null
                }

                nextTabNodes.add(
                    TabHitNode(
                        tabId = tab.id,
                        tabRect = RectF(tabRect),
                        closeRect = closeRect?.let { RectF(it) }
                    )
                )

                if (tabRect.right >= tabsLeft && tabRect.left <= tabsRight) {
                    val touchTarget = activeTabTouchTarget
                    val isTabPressed =
                        isTabTouchActive &&
                            !tabTouchMoved &&
                            touchTarget is TabTouchTarget.SelectTab &&
                            touchTarget.tabId == tab.id &&
                            tabRect.contains(tabTouchX, tabTouchY)
                    val isClosePressed =
                        isTabTouchActive &&
                            !tabTouchMoved &&
                            touchTarget is TabTouchTarget.CloseTab &&
                            touchTarget.tabId == tab.id &&
                            (closeRect?.contains(tabTouchX, tabTouchY) == true)
                    drawTabNode(
                        canvas = canvas,
                        tab = tab,
                        tabRect = tabRect,
                        closeRect = closeRect,
                        isActive = tab.id == currentTabId,
                        isTabPressed = isTabPressed,
                        isClosePressed = isClosePressed
                    )
                }
                cursorX += tabWidth + tabSpacingPx
            }
        } finally {
            canvas.restore()
        }
        tabHitSnapshot = TabHitSnapshot(
            nodes = nextTabNodes.toList(),
            addButtonRect = RectF(addButtonRect)
        )
    }

    private fun drawTabNode(
        canvas: Canvas,
        tab: TerminalTabRenderItem,
        tabRect: RectF,
        closeRect: RectF?,
        isActive: Boolean,
        isTabPressed: Boolean,
        isClosePressed: Boolean
    ) {
        tabPaint.color = when {
            isTabPressed && isActive -> tabActivePressedColor
            isTabPressed -> tabInactivePressedColor
            isActive -> tabActiveColor
            else -> tabInactiveColor
        }
        canvas.drawRoundRect(tabRect, tabCornerRadiusPx, tabCornerRadiusPx, tabPaint)

        val textStartX = tabRect.left + tabTextHorizontalPaddingPx
        val textEndX = (closeRect?.left ?: (tabRect.right - tabTextHorizontalPaddingPx)) - tabSpacingPx
        val availableTextWidth = (textEndX - textStartX).coerceAtLeast(0f)
        val displayText = ellipsizeTabText(tab.title, availableTextWidth)

        tabTextPaint.color = if (isActive) Color.WHITE else Color.GRAY
        tabTextPaint.isFakeBoldText = isActive
        val baselineY = tabRect.centerY() - (tabTextPaint.ascent() + tabTextPaint.descent()) / 2f
        canvas.drawText(displayText, textStartX, baselineY, tabTextPaint)
        tabTextPaint.isFakeBoldText = false

        if (closeRect != null) {
            if (isClosePressed) {
                tabPaint.color = tabClosePressedBgColor
                canvas.drawRoundRect(
                    closeRect,
                    closeRect.height() * 0.45f,
                    closeRect.height() * 0.45f,
                    tabPaint
                )
            }
            drawCloseIcon(canvas, closeRect, isActive, isPressed = isClosePressed)
        }
    }

    private fun drawAddTabButton(canvas: Canvas, rect: RectF, isPressed: Boolean) {
        if (rect.isEmpty) return
        tabPaint.color = if (isPressed) tabAddPressedBgColor else tabInactiveColor
        canvas.drawRoundRect(rect, tabCornerRadiusPx * 0.8f, tabCornerRadiusPx * 0.8f, tabPaint)

        val cx = rect.centerX()
        val cy = rect.centerY()
        val half = min(rect.width(), rect.height()) * 0.22f
        tabIconPaint.color = Color.WHITE
        canvas.drawLine(cx - half, cy, cx + half, cy, tabIconPaint)
        canvas.drawLine(cx, cy - half, cx, cy + half, tabIconPaint)
    }

    private fun drawCloseIcon(canvas: Canvas, rect: RectF, isActive: Boolean, isPressed: Boolean) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val half = min(rect.width(), rect.height()) * 0.20f
        tabIconPaint.color = if (isPressed || isActive) Color.WHITE else Color.GRAY
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, tabIconPaint)
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, tabIconPaint)
    }

    private fun ellipsizeTabText(text: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (tabTextPaint.measureText(text) <= maxWidth) return text
        val ellipsis = "..."
        val ellipsisWidth = tabTextPaint.measureText(ellipsis)
        if (ellipsisWidth >= maxWidth) return ellipsis
        val visibleChars = tabTextPaint.breakText(
            text,
            true,
            maxWidth - ellipsisWidth,
            null
        ).coerceAtLeast(0)
        return if (visibleChars == 0) {
            ellipsis
        } else {
            text.substring(0, visibleChars) + ellipsis
        }
    }

    private fun getMaxTabScrollOffset(): Float = max(0f, tabContentWidth - getTabsViewportWidth())

    private fun hitTestTabTarget(x: Float, y: Float): TabTouchTarget {
        if (!hasTabBar() || y < 0f || y > tabBarHeightPx) {
            return TabTouchTarget.None
        }
        val hitSnapshot = tabHitSnapshot
        if (hitSnapshot.addButtonRect.contains(x, y)) {
            return TabTouchTarget.NewTab
        }
        val nodes = hitSnapshot.nodes
        for (index in nodes.indices.reversed()) {
            val node = nodes[index]
            if (node.closeRect?.contains(x, y) == true) {
                return TabTouchTarget.CloseTab(node.tabId)
            }
            if (node.tabRect.contains(x, y)) {
                return TabTouchTarget.SelectTab(node.tabId)
            }
        }
        return TabTouchTarget.Background
    }

    private fun isSameTabTarget(a: TabTouchTarget, b: TabTouchTarget): Boolean {
        return when {
            a is TabTouchTarget.SelectTab && b is TabTouchTarget.SelectTab -> a.tabId == b.tabId
            a is TabTouchTarget.CloseTab && b is TabTouchTarget.CloseTab -> a.tabId == b.tabId
            a is TabTouchTarget.NewTab && b is TabTouchTarget.NewTab -> true
            a is TabTouchTarget.Background && b is TabTouchTarget.Background -> true
            a is TabTouchTarget.None && b is TabTouchTarget.None -> true
            else -> false
        }
    }

    private fun executeTabTouchTarget(target: TabTouchTarget) {
        when (target) {
            is TabTouchTarget.SelectTab -> onTabClick?.invoke(target.tabId)
            is TabTouchTarget.CloseTab -> onTabClose?.invoke(target.tabId)
            is TabTouchTarget.NewTab -> {
                pendingScrollToEndAfterNewTab = true
                pendingScrollToEndMinTabCount = tabs.size + 1
                onNewTab?.invoke()
            }
            TabTouchTarget.Background, TabTouchTarget.None -> Unit
        }
    }

    private fun resetTabTouchState() {
        activeTabTouchTarget = TabTouchTarget.None
        isTabTouchActive = false
        tabTouchMoved = false
        tabTouchDownX = 0f
        tabTouchDownY = 0f
        tabTouchX = 0f
        tabTouchY = 0f
    }

    private fun recycleTabVelocityTracker() {
        tabVelocityTracker?.recycle()
        tabVelocityTracker = null
    }

    private fun handleTabTouch(event: MotionEvent): Boolean {
        if (!hasTabBar()) return false

        val action = event.actionMasked
        val withinTabBar = event.y in 0f..tabBarHeightPx

        if (action == MotionEvent.ACTION_DOWN) {
            if (!withinTabBar) return false
            if (!tabScroller.isFinished) {
                tabScroller.abortAnimation()
            }
            recycleTabVelocityTracker()
            tabVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            activeTabTouchTarget = hitTestTabTarget(event.x, event.y)
            isTabTouchActive = true
            tabTouchDownX = event.x
            tabTouchDownY = event.y
            tabTouchX = event.x
            tabTouchY = event.y
            tabTouchMoved = false
            requestRender()
            return true
        }

        if (activeTabTouchTarget == TabTouchTarget.None) {
            return false
        }

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                tabVelocityTracker?.addMovement(event)
                tabTouchX = event.x
                tabTouchY = event.y
                val dxTotal = event.x - tabTouchDownX
                val dyTotal = event.y - tabTouchDownY
                if (!tabTouchMoved && (abs(dxTotal) > tabTouchSlopPx || abs(dyTotal) > tabTouchSlopPx)) {
                    tabTouchMoved = true
                }
                if (tabTouchMoved && (activeTabTouchTarget is TabTouchTarget.Background || activeTabTouchTarget is TabTouchTarget.SelectTab)) {
                    val maxOffset = getMaxTabScrollOffset()
                    if (maxOffset > 0f) {
                        val newOffset = (tabScrollOffsetX - dxTotal).coerceIn(0f, maxOffset)
                        if (newOffset != tabScrollOffsetX) {
                            tabScrollOffsetX = newOffset
                            requestRender()
                        }
                    }
                    tabTouchDownX = event.x
                    tabTouchDownY = event.y
                }
                requestRender()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                tabVelocityTracker?.addMovement(event)
                tabTouchMoved = true
                requestRender()
                return true
            }
            MotionEvent.ACTION_UP -> {
                tabVelocityTracker?.addMovement(event)
                tabTouchX = event.x
                tabTouchY = event.y
                val releaseTarget = if (withinTabBar) hitTestTabTarget(event.x, event.y) else TabTouchTarget.None
                if (!tabTouchMoved && isSameTabTarget(activeTabTouchTarget, releaseTarget)) {
                    executeTabTouchTarget(activeTabTouchTarget)
                } else if (tabTouchMoved && (activeTabTouchTarget is TabTouchTarget.Background || activeTabTouchTarget is TabTouchTarget.SelectTab)) {
                    val maxOffset = getMaxTabScrollOffset()
                    if (maxOffset > 0f) {
                        tabVelocityTracker?.computeCurrentVelocity(1000, tabMaxFlingVelocityPx)
                        val xVelocity = tabVelocityTracker?.xVelocity ?: 0f
                        if (abs(xVelocity) >= tabMinFlingVelocityPx) {
                            tabScroller.fling(
                                tabScrollOffsetX.toInt(),
                                0,
                                (-xVelocity).toInt(),
                                0,
                                0,
                                maxOffset.toInt(),
                                0,
                                0
                            )
                            requestRender()
                        }
                    }
                }
                recycleTabVelocityTracker()
                resetTabTouchState()
                requestRender()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                recycleTabVelocityTracker()
                resetTabTouchState()
                requestRender()
                return true
            }
            else -> return true
        }
    }

    // === 核心渲染方法 ===
    
    private fun drawTerminal(canvas: Canvas) {
        val em = emulator ?: return
        if (canvas.width <= 0 || canvas.height <= 0) return

        val viewportWidth = canvas.width.toFloat()
        val contentTop = getTerminalContentTop()
        val viewportHeight = getTerminalViewportHeight(canvas.height.toFloat())
        // 绘制能用的下沿是整窗，不是「视口底」：收键盘时内容被整体往下平移，键盘窗口还盖在
        // 画布上面，画到 height 不会露出来；下沿若停在 height - committed 会把内容拦腰截断。
        val windowBottom = canvas.height.toFloat()
        canvas.save()
        canvas.clipRect(0f, 0f, viewportWidth, canvas.height.toFloat())
        try {
            // 1. 先全屏清屏，防止前帧残留
            bgPaint.color = config.backgroundColor
            canvas.drawRect(0f, 0f, viewportWidth, canvas.height.toFloat(), bgPaint)


            // 2. 顶部标签栏
            drawTabBar(canvas)
            if (viewportHeight <= 0f) {
                return
            }

            canvas.save()
            canvas.clipRect(0f, contentTop, viewportWidth, windowBottom)
            try {
                // 提交帧残余位移：只从位移侧衰减，滚动不再缓动（见 commitResidualPx）。
                // 这里只负责在衰减跑完的那一帧把标记清掉，避免每帧都为它请求重绘。
                if (commitResidualPx != 0f) {
                    val residualElapsed =
                        android.os.SystemClock.uptimeMillis() - commitResidualStartMs
                    if (residualElapsed >= commitResidualDurationMs) {
                        commitResidualPx = 0f
                    } else {
                        isDirty = true
                    }
                }

                // 处理惯性滚动动画
                if (scroller.computeScrollOffset()) {
                    val newScrollY = clampScrollOffset(scroller.currY.toFloat())
                    if (newScrollY != scrollOffsetY) {
                        scrollOffsetY = newScrollY

                        // 保存滚动位置
                        sessionId?.let { id ->
                            onScrollOffsetChanged?.invoke(id, scrollOffsetY)
                        }

                        // 更新用户滚动状态
                        isUserScrolling = scrollOffsetY > 0f

                        // 继续请求渲染以保持动画流畅
                        isDirty = true
                    }
                }

                // 使用完整内容（历史 + 屏幕缓冲）
                val fullContent = em.getFullContent()
                val historySize = em.getHistorySize()

                val charWidth = textMetrics.charWidth
                val charHeight = textMetrics.charHeight
                val baseline = textMetrics.charBaseline
                if (charHeight <= 0f) return

                // 限制最大滚动偏移（不能超过内容高度）
                val maxScrollOffset = max(0f, fullContent.size * charHeight - viewportHeight)

                // 需要贴底时执行。注意这里必须「跟随光标行」而不是滚到缓冲区末尾：
                // 终端模拟器的缓冲区常常在光标下方还留着一堆空行，盲目贴底会把刚输出的内容
                // 整屏顶上去（用户看到的就是「一片黑，往下滑才露出提示符」）。
                if (needScrollToBottom) {
                    val cursorRow = em.getHistorySize() + em.getCursorY()
                    val cursorBottom = (cursorRow + 1) * charHeight
                    scrollOffsetY = (cursorBottom - viewportHeight).coerceIn(0f, maxScrollOffset)
                    needScrollToBottom = false
                }

                scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScrollOffset)

                // 可见行范围按「平移之后」的可见区间重算，不能再用 viewportHeight/ch + 2：
                // 那个式子是从视口上沿往下数的，收键盘把内容整体往下平移后，屏幕上沿会露出
                // 一批没被算进范围的行（上一版「中间空洞」就是这么来的），开键盘则是多画。
                //   firstVisible = 屏幕 y=contentTop 处对应的行（含边界行，故再留一行）
                //   lastVisible  = 屏幕 y=height 处对应的行（同上）
                // 行内还按平移后的精确 y 再判一次，这里多留 1~2 行只是防边缘半行漏画。
                val off = getTerminalVisualOffsetY().toFloat()
                val firstVisible = floor((scrollOffsetY + off - contentTop) / charHeight).toInt()
                val lastVisible =
                    ceil((windowBottom - contentTop + scrollOffsetY + off) / charHeight).toInt()
                val startRow = (firstVisible - 1).coerceAtLeast(0)
                val endRow = (lastVisible + 2).coerceAtLeast(startRow).coerceAtMost(fullContent.size)

                // 每 100ms 一条逐帧证据。唯一的机验不变量：键盘提交前后两帧的 cursorScreenY
                // 必须相等（差 ≤2px）——屏幕位置 = -scroll - O，两边对得上就不跳。
                val dbgNow = android.os.SystemClock.uptimeMillis()
                if (dbgNow - imeDbgFrameMs >= 100L) {
                    imeDbgFrameMs = dbgNow
                    val cursorScreenY =
                        contentTop + (historySize + em.getCursorY() + 1) * charHeight -
                            scrollOffsetY - off
                    imeDbg(
                        "vb.frame offY=" + off.toInt() +
                            " animated=" + imeAnimatedInsetPx +
                            " committed=" + committedImeBottomInsetPx +
                            " scroll=" + scrollOffsetY.toInt() +
                            " startRow=" + startRow +
                            " endRow=" + endRow +
                            " clipBottom=" + windowBottom.toInt() +
                            " cursorScreenY=" + cursorScreenY.toInt()
                    )
                }

                canvas.save()
                canvas.translate(0f, -off)
                try {
                    // 绘制每一行（包括背景）
                    for (row in startRow until endRow) {
                        if (row >= fullContent.size) break

                        val line = fullContent[row]

                        // 使用绝对坐标计算，避免 startRow 跳变导致的整体偏移
                        val exactY = contentTop + row * charHeight - scrollOffsetY
                        val y = kotlin.math.round(exactY)
                        // 可见性必须按「平移之后」的屏幕 y 判：收键盘时内容整体下移，用
                        // content 坐标判断会把屏幕上方新露出来的行全裁掉（中间空洞）。
                        val screenY = y - off
                        if (screenY + charHeight <= contentTop || screenY >= windowBottom) {
                            continue
                        }

                        // 绘制该行的所有字符
                        drawLine(canvas, line, row, 0f, y, charWidth, charHeight, baseline)
                    }

                    // 绘制选择区域
                    if (selectionManager.hasSelection()) {
                        drawSelection(canvas, charWidth, charHeight)
                    }

                    // 绘制光标（光标只在可见屏幕部分显示，需要考虑历史缓冲区偏移）
                    if (em.isCursorVisible()) {
                        val cursorRow = historySize + em.getCursorY()
                        val cursorCol = em.getCursorX()

                        // 只有当光标在可见区域内时才绘制
                        if (cursorRow >= startRow && cursorRow < endRow) {
                            val exactCursorY = contentTop + cursorRow * charHeight - scrollOffsetY
                            val cursorY = kotlin.math.round(exactCursorY)
                            // 光标和行一起被平移：判据与夹取都在屏幕坐标里做，下沿用整窗
                            // （不能停在视口底，否则收键盘时被截掉/画到窗口外）。
                            val screenCursorY = cursorY - off
                            if (screenCursorY + charHeight > contentTop && screenCursorY < windowBottom) {
                                // 计算光标的 x 坐标，考虑宽字符
                                val line = fullContent.getOrNull(cursorRow) ?: arrayOf()
                                var cursorX = 0f

                                // 遍历到光标列，累加每个字符的宽度
                                for (col in 0 until cursorCol.coerceAtMost(line.size)) {
                                    val cellWidth = textMetrics.getCellWidth(line[col].char)
                                    cursorX += charWidth * cellWidth
                                }

                                // 获取光标所在字符的宽度
                                val cursorCharWidth = if (cursorCol < line.size) {
                                    val cellWidth = textMetrics.getCellWidth(line[cursorCol].char)
                                    charWidth * cellWidth
                                } else {
                                    charWidth
                                }

                                // 夹完再换回平移前的坐标画（画布此刻是 translate 状态）
                                val top = screenCursorY.coerceAtLeast(contentTop) + off
                                val bottom =
                                    (screenCursorY + charHeight).coerceAtMost(windowBottom) + off
                                if (bottom > top) {
                                    cursorPaint.color = Color.GREEN
                                    cursorPaint.alpha = 180
                                    canvas.drawRect(
                                        cursorX,
                                        top,
                                        cursorX + cursorCharWidth,
                                        bottom,
                                        cursorPaint
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    canvas.restore()
                }

                if (selectionManager.hasSelection()) {
                    drawSelectionHandles(canvas, charWidth, charHeight)
                    if (isSelectionDragging) {
                        drawSelectionMagnifier(canvas)
                    }
                }
            } finally {
                canvas.restore()
            }
        } finally {
            canvas.restore()
        }
    }
    
    private fun drawLine(
        canvas: Canvas,
        line: Array<TerminalChar>,
        row: Int,
        startX: Float,
        y: Float,
        charWidth: Float,
        charHeight: Float,
        baseline: Float
    ) {
        if (charHeight <= 0f) return
        // 这里不再按 canvas.height 自裁：y 是平移前的 content 坐标，而键盘让位后整块画布被
        // translate 过，用 content 坐标判断会把明明可见的行（上方新露出的那几行）裁掉。
        // 可见性由调用方在屏幕坐标里判定，超出的部分由 clipRect 兜住。

        var x = startX
        
        // Pass 1: Draw Backgrounds (Batching consecutive same-color cells)
        var currentBgColor: Int? = null
        var bgStartX = x
        var bgRunWidth = 0f
        
        for (col in line.indices) {
            val termChar = line[col]
            val cellWidth = textMetrics.getCellWidth(termChar.char)
            val actualCharWidth = charWidth * cellWidth
            
            if (termChar.bgColor != config.backgroundColor) {
                if (currentBgColor != termChar.bgColor) {
                    // Flush previous BG
                    currentBgColor?.let {
                        bgPaint.color = it
                        canvas.drawRect(bgStartX, y, bgStartX + bgRunWidth, y + charHeight, bgPaint)
                    }
                    // Start new BG run
                    currentBgColor = termChar.bgColor
                    bgStartX = startX + (if (col == 0) 0f else getXOffsetForCol(line, col, charWidth))
                    bgRunWidth = 0f
                }
                bgRunWidth += actualCharWidth
            } else {
                // Flush previous BG
                currentBgColor?.let {
                    bgPaint.color = it
                    canvas.drawRect(bgStartX, y, bgStartX + bgRunWidth, y + charHeight, bgPaint)
                }
                currentBgColor = null
                bgRunWidth = 0f
            }
        }
        // Flush remaining BG
        currentBgColor?.let {
            bgPaint.color = it
            canvas.drawRect(bgStartX, y, bgStartX + bgRunWidth, y + charHeight, bgPaint)
        }

        // Pass 2: Draw Text (Batching consecutive compatible characters)
        x = startX
        val sb = StringBuilder()
        var runStartX = x
        
        // Current run attributes
        var currentFgColor = -1
        var currentFontType = -1
        var currentBold = false
        var currentItalic = false
        var currentUnderline = false
        var currentStrike = false
        
        for (col in line.indices) {
            val termChar = line[col]
            val char = termChar.char
            val cellWidth = textMetrics.getCellWidth(char)
            val actualCharWidth = charWidth * cellWidth
            
            if (char == ' ' || termChar.isHidden) {
                // Space/Hidden breaks the run
                if (sb.isNotEmpty()) {
                    drawTextRun(canvas, sb.toString(), runStartX, y + baseline, currentFgColor, currentFontType, currentBold, currentItalic, currentUnderline, currentStrike, charWidth)
                    sb.setLength(0)
                }
                x += actualCharWidth
                continue
            }

            // Calculate attributes
            var fgColor = termChar.fgColor
            if (termChar.isDim) {
                fgColor = Color.argb(180, Color.red(fgColor), Color.green(fgColor), Color.blue(fgColor))
            }
            if (termChar.isInverse) {
                fgColor = termChar.bgColor
            }
            
            val fontType = textMetrics.resolveFontType(char)
            val isBold = termChar.isBold
            val isItalic = termChar.isItalic
            val isUnderline = termChar.isUnderline
            val isStrike = termChar.isStrikethrough
            
            // Check if attributes match current run
            val matches = sb.isNotEmpty() &&
                    fgColor == currentFgColor &&
                    fontType == currentFontType &&
                    isBold == currentBold &&
                    isItalic == currentItalic &&
                    isUnderline == currentUnderline &&
                    isStrike == currentStrike
            
            if (!matches) {
                // Flush previous run
                if (sb.isNotEmpty()) {
                    drawTextRun(canvas, sb.toString(), runStartX, y + baseline, currentFgColor, currentFontType, currentBold, currentItalic, currentUnderline, currentStrike, charWidth)
                    sb.setLength(0)
                }
                // Start new run
                currentFgColor = fgColor
                currentFontType = fontType
                currentBold = isBold
                currentItalic = isItalic
                currentUnderline = isUnderline
                currentStrike = isStrike
                runStartX = x
            }
            
            if (cellWidth == 2) {
                // Wide char: Draw immediately to handle positioning correctly (centered in 2 cells)
                // Or just append? If we append, we rely on Paint to advance width. 
                // Paint.measureText might not match 2*charWidth exactly.
                // Safer to flush and draw individually for wide chars to ensure grid alignment.
                if (sb.isNotEmpty()) {
                    drawTextRun(canvas, sb.toString(), runStartX, y + baseline, currentFgColor, currentFontType, currentBold, currentItalic, currentUnderline, currentStrike, charWidth)
                    sb.setLength(0)
                }
                
                // Draw wide char
                drawTextRun(canvas, char.toString(), x + (charWidth / 2), y + baseline, fgColor, fontType, isBold, isItalic, isUnderline, isStrike, actualCharWidth)
                
                // Reset run
                runStartX = x + actualCharWidth
            } else {
                sb.append(char)
            }
            
            x += actualCharWidth
        }
        
        // Flush final run
        if (sb.isNotEmpty()) {
            drawTextRun(canvas, sb.toString(), runStartX, y + baseline, currentFgColor, currentFontType, currentBold, currentItalic, currentUnderline, currentStrike, charWidth)
        }
    }
    
    // Helper to calculate X offset for a column (for BG pass)
    private fun getXOffsetForCol(line: Array<TerminalChar>, col: Int, charWidth: Float): Float {
        var offset = 0f
        for (i in 0 until col) {
            offset += charWidth * textMetrics.getCellWidth(line[i].char)
        }
        return offset
    }

    private fun drawTextRun(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        fontType: Int,
        isBold: Boolean,
        isItalic: Boolean,
        isUnderline: Boolean,
        isStrike: Boolean,
        charWidth: Float // Only used for decorations on wide chars
    ) {
        textMetrics.applyStyle(isBold, isItalic)
        textMetrics.setFont(fontType)
        textPaint.color = color
        
        canvas.drawText(text, x, y, textPaint)
        
        val runWidth = if (text.length == 1) charWidth else text.length * textMetrics.charWidth
        
        if (isUnderline) {
            val underlineY = y + 2
            canvas.drawLine(x, underlineY, x + runWidth, underlineY, textPaint)
        }
        
        if (isStrike) {
            val strikeY = y - textMetrics.charHeight / 2
            canvas.drawLine(x, strikeY, x + runWidth, strikeY, textPaint)
        }
        
        textMetrics.resetStyle()
    }
    
    private fun drawChar(canvas: Canvas, termChar: TerminalChar, x: Float, y: Float, charWidth: Float = textMetrics.charWidth) {
        // Legacy method, kept if needed but drawLine now uses drawTextRun
        // We can remove it or redirect.
    }
    
    private fun drawSelection(canvas: Canvas, charWidth: Float, charHeight: Float) {
        val selection = selectionManager.selection?.normalize() ?: return
        val em = emulator ?: return
        val fullContent = em.getFullContent()
        val contentTop = getTerminalContentTop()
        // 选择区和行在同一段平移里画，下沿同样用整窗：收键盘时内容被往下推，停在视口底会把
        // 选区腰斩（多出的一段由 clipRect 兜住）。
        val windowBottom = canvas.height.toFloat()
        if (windowBottom <= contentTop || charHeight <= 0f) return
        // 这一段的 y 是平移前的坐标，夹取边界要加上同一个位移，等价于在屏幕坐标里夹。
        val off = getTerminalVisualOffsetY().toFloat()
        val minY = contentTop + off
        val maxY = windowBottom + off

        for (row in selection.startRow..selection.endRow) {
            val exactY = contentTop + row * charHeight - scrollOffsetY
            val y = kotlin.math.round(exactY)
            val top = y.coerceAtLeast(minY)
            val bottom = (y + charHeight).coerceAtMost(maxY)
            if (bottom <= top) continue
            
            val startCol = if (row == selection.startRow) selection.startCol else 0
            val endCol = if (row == selection.endRow) {
                selection.endCol
            } else {
                fullContent.getOrNull(row)?.size ?: 0
            }
            
            // 计算选择区域的 x 坐标，考虑宽字符
            val line = fullContent.getOrNull(row) ?: continue
            var x1 = 0f
            var x2 = 0f
            
            // 计算起始 x 坐标
            for (col in 0 until startCol.coerceAtMost(line.size)) {
                val cellWidth = textMetrics.getCellWidth(line[col].char)
                x1 += charWidth * cellWidth
            }
            
            // 计算结束 x 坐标
            for (col in 0..endCol.coerceAtMost(line.size - 1)) {
                val cellWidth = textMetrics.getCellWidth(line[col].char)
                x2 += charWidth * cellWidth
            }
            
            canvas.drawRect(x1, top, x2, bottom, selectionPaint)
        }
    }
    
    // === 无障碍支持 ===
    
    /**
     * 初始化无障碍节点信息
     * 确保View本身不会被无障碍服务选中，只通过虚拟节点访问内容
     */
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // 关键：不设置任何描述性文本，让View对TalkBack透明
        // 所有交互都通过虚拟节点（每一行）进行
        info.className = CanvasTerminalView::class.java.name
        info.isClickable = false
        info.isFocusable = false
        info.isLongClickable = false
        // 不设置 contentDescription，避免整个View被朗读
    }
    
    // === 触摸事件处理 ===
    
    /**
     * 处理悬停事件（用于无障碍触摸探索）
     * 当用户使用TalkBack触摸屏幕时，会触发此方法
     */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!isAccessibilityEnabled()) {
            return super.dispatchHoverEvent(event)
        }
        
        // 让accessibility delegate处理悬停事件
        // 这样触摸探索时能找到对应的虚拟节点（行）
        // 直接使用成员变量以兼容 API < 29 (getAccessibilityDelegate 是 API 29+)
        terminalAccessibilityDelegate.let { delegate ->
            val virtualViewId = delegate.findVirtualViewAt(event.x, event.y)
            
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    // 当触摸到一个虚拟节点时，主动请求该节点获得无障碍焦点
                    if (virtualViewId != -1) {
                        // 获取节点提供者并请求焦点
                        accessibilityNodeProvider?.let { provider ->
                            provider.performAction(
                                virtualViewId,
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                                null
                            )
                        }
                        
                        // 发送悬停事件
                        val hoverEvent = android.view.accessibility.AccessibilityEvent.obtain(
                            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
                        )
                        hoverEvent.setSource(this, virtualViewId)
                        try {
                            parent?.requestSendAccessibilityEvent(this, hoverEvent)
                        } catch (_: IllegalStateException) {
                        }
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    // 悬停退出时不清除焦点，让焦点保持在最后一个触摸的节点上
                }
            }
        }
        
        return super.dispatchHoverEvent(event)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleTabTouch(event)) {
            return true
        }
        var handled = gestureHandler.onTouchEvent(event)
        val action = event.actionMasked
        
        // 单击时请求焦点；显示输入法延后到 ACTION_UP，避免长按选择时提前弹出输入法造成卡手
        if (action == MotionEvent.ACTION_DOWN) {
            touchDownX = event.x
            touchDownY = event.y
            hasMovedBeyondTouchSlop = false
            hadMultiTouch = false

            if (!hasFocus()) {
                requestFocus()
            }

            if (selectionManager.hasSelection()) {
                val charWidth = textMetrics.charWidth
                val charHeight = textMetrics.charHeight
                val centers = getSelectionHandleCenters(charWidth, charHeight)
                if (centers != null) {
                    val (start, end) = centers
                    val r = selectionHandleRadius * 2.4f
                    val r2 = r * r
                    val ds = (event.x - start.x) * (event.x - start.x) + (event.y - start.y) * (event.y - start.y)
                    val de = (event.x - end.x) * (event.x - end.x) + (event.y - end.y) * (event.y - end.y)
                    activeDragHandle = when {
                        ds <= r2 && de <= r2 -> if (ds <= de) DragHandle.START else DragHandle.END
                        ds <= r2 -> DragHandle.START
                        de <= r2 -> DragHandle.END
                        else -> DragHandle.NONE
                    }
                } else {
                    activeDragHandle = DragHandle.NONE
                }
            } else {
                activeDragHandle = DragHandle.NONE
            }
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            hadMultiTouch = true
            hasMovedBeyondTouchSlop = true
        }
        
        // 处理选择移动
        if (action == MotionEvent.ACTION_MOVE) {
            if (!hasMovedBeyondTouchSlop) {
                val dx = abs(event.x - touchDownX)
                val dy = abs(event.y - touchDownY)
                if (dx > touchSlop || dy > touchSlop) {
                    hasMovedBeyondTouchSlop = true
                }
            }
        }

        if (selectionManager.hasSelection() && action == MotionEvent.ACTION_MOVE) {
            isSelectionDragging = true
            lastSelectionTouchX = event.x
            lastSelectionTouchY = event.y

            val (row, col) = screenToTerminalCoords(event.x, event.y)
            when (activeDragHandle) {
                DragHandle.START -> selectionManager.setSelectionStart(row, col)
                DragHandle.END -> selectionManager.setSelectionEnd(row, col)
                else -> selectionManager.updateSelection(row, col)
            }
            actionMode?.invalidate()
            requestRender()
            updateAutoScrollForSelectionDrag()
            handled = true
        }
        
        if (action == MotionEvent.ACTION_UP) {
            if (selectionManager.hasSelection()) {
                showTextSelectionMenu()
            } else {
                val isClickGesture =
                    !hasMovedBeyondTouchSlop &&
                        !gestureHandler.isScaling &&
                        !hadMultiTouch
                if (isClickGesture) {
                    if (isFullscreenMode) {
                        showSoftKeyboard()
                    } else {
                        onRequestShowKeyboard?.invoke()
                    }
                }
            }

            activeDragHandle = DragHandle.NONE
            isSelectionDragging = false
            autoScrollDirection = 0
            autoScrollRunnable?.let { handler.removeCallbacks(it) }
            autoScrollRunnable = null
            hasMovedBeyondTouchSlop = false
            hadMultiTouch = false
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            activeDragHandle = DragHandle.NONE
            isSelectionDragging = false
            autoScrollDirection = 0
            autoScrollRunnable?.let { handler.removeCallbacks(it) }
            autoScrollRunnable = null
            hasMovedBeyondTouchSlop = false
            hadMultiTouch = false
        }
        
        return handled || super.onTouchEvent(event)
    }
    
    /**
     * 屏幕坐标转换为终端坐标
     * 考虑宽字符的影响
     */
    private fun screenToTerminalCoords(x: Float, y: Float): Pair<Int, Int> {
        val em = emulator ?: return Pair(0, 0)
        val fullContent = em.getFullContent()
        if (fullContent.isEmpty() || textMetrics.charHeight <= 0f) {
            return Pair(0, 0)
        }

        val localY = (y + getTerminalVisualOffsetY() - getTerminalContentTop()).coerceAtLeast(0f)
        val row = ((localY + scrollOffsetY) / textMetrics.charHeight).toInt().coerceIn(0, fullContent.size - 1)
        
        // 获取该行的内容
        val line = fullContent.getOrNull(row) ?: return Pair(row, 0)
        
        // 遍历该行的字符，找到对应的列
        var currentX = 0f
        var col = 0
        val charWidth = textMetrics.charWidth
        
        for (i in line.indices) {
            val cellWidth = textMetrics.getCellWidth(line[i].char)
            val actualCharWidth = charWidth * cellWidth
            
            if (x < currentX + actualCharWidth / 2) {
                // 点击位置在这个字符的前半部分
                col = i
                break
            } else if (x < currentX + actualCharWidth) {
                // 点击位置在这个字符的后半部分（宽字符）
                col = i
                break
            }
            
            currentX += actualCharWidth
            col = i + 1
        }
        
        return Pair(row, col.coerceIn(0, max(0, line.size - 1)))
    }
    
    /**
     * 开始文本选择
     */
    private fun startTextSelection(x: Float, y: Float) {
        val (row, col) = screenToTerminalCoords(x, y)
        selectionManager.startSelection(row, col)
        requestRender()
    }
    
    /**
     * 显示文本选择菜单
     */
    private fun showTextSelectionMenu() {
        if (actionMode != null) return
        
        val callback = object : ActionMode.Callback2() {
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val selection = selectionManager.selection?.normalize()
                val em = emulator
                if (selection == null || em == null) {
                    outRect.set(0, 0, width, height)
                    return
                }

                val fullContent = em.getFullContent()
                val row = selection.endRow.coerceIn(0, fullContent.size - 1)
                val line = fullContent.getOrNull(row)
                if (line == null || line.isEmpty()) {
                    outRect.set(0, 0, width, height)
                    return
                }

                val col = selection.endCol.coerceIn(0, line.size - 1)
                val charWidth = textMetrics.charWidth
                val charHeight = textMetrics.charHeight

                val x = getXOffsetForCol(line, col, charWidth)
                val y =
                    kotlin.math.round(row * charHeight - scrollOffsetY) -
                        getTerminalVisualOffsetY()
                val cellWidth = textMetrics.getCellWidth(line[col].char)
                val w = (charWidth * cellWidth).coerceAtLeast(charWidth)

                val left = x.toInt().coerceIn(0, width)
                val top = y.toInt().coerceIn(0, getTerminalViewportBottom().toInt())
                val right = (x + w).toInt().coerceIn(left, width)
                val bottom =
                    (y + charHeight).toInt().coerceIn(top, getTerminalViewportBottom().toInt())

                outRect.set(left, top, right, bottom)
            }

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "复制")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return false
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> {
                        copySelectedText()
                        mode.finish()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                selectionManager.clearSelection()
                requestRender()
            }
        }

        actionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else {
            startActionMode(callback)
        }
    }
    
    /**
     * 复制选中的文本
     */
    private fun copySelectedText() {
        val selection = selectionManager.selection?.normalize() ?: return
        val buffer = emulator?.getFullContent() ?: return
        
        val text = buildString {
            for (row in selection.startRow..selection.endRow) {
                if (row >= buffer.size) break

                val line = buffer[row]
                val startCol = if (row == selection.startRow) selection.startCol else 0
                val endCol = if (row == selection.endRow) selection.endCol else line.size - 1
                if (line.isEmpty()) {
                    if (row < selection.endRow) {
                        append('\n')
                    }
                    continue
                }
                if (endCol < startCol) {
                    if (row < selection.endRow) {
                        append('\n')
                    }
                    continue
                }
                
                for (col in startCol..endCol) {
                    if (col < line.size) {
                        append(line[col].char)
                    }
                }
                
                if (row < selection.endRow) {
                    append('\n')
                }
            }
        }
        
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    
    // === 输入法支持 ===
    
    /**
     * 显示软键盘
     */
    private fun showSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    
    /**
     * 隐藏软键盘
     */
    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }
    
    /**
     * 创建输入连接
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection {
        outAttrs?.apply {
            inputType = EditorInfo.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        }
        
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.let {
                    val normalized = it.toString()
                        .replace("\r\n", "\r")
                        .replace('\n', '\r')
                    inputCallback?.invoke(normalized)
                }
                return true
            }
            
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    // 发送退格键
                    inputCallback?.invoke("\u007F") // DEL character
                }
                return true
            }
            
            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                event?.let {
                    when (it.action) {
                        KeyEvent.ACTION_DOWN -> {
                            when (it.keyCode) {
                                KeyEvent.KEYCODE_DEL -> {
                                    inputCallback?.invoke("\u007F")
                                    return true
                                }
                                KeyEvent.KEYCODE_ENTER -> {
                                    inputCallback?.invoke("\r")
                                    return true
                                }
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    // 全屏模式下处理方向键长按
                                    if (isFullscreenMode) {
                                        handleArrowKeyDown(it.keyCode)
                                        return true
                                    }
                                }
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            when (it.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    // 全屏模式下处理方向键释放
                                    if (isFullscreenMode) {
                                        handleArrowKeyUp()
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }
    
    /**
     * 处理方向键按下
     */
    private fun handleArrowKeyDown(keyCode: Int) {
        // 如果已经有其他方向键被按下，先释放
        if (isArrowKeyPressed && currentArrowKey != keyCode) {
            handleArrowKeyUp()
        }
        
        currentArrowKey = keyCode
        isArrowKeyPressed = true
        
        // 立即发送一次方向键
        sendArrowKey(keyCode)
        
        // 延迟后开始重复发送
        arrowKeyRepeatRunnable = object : Runnable {
            override fun run() {
                if (isArrowKeyPressed && currentArrowKey == keyCode) {
                    sendArrowKey(keyCode)
                    handler.postDelayed(this, repeatInterval)
                }
            }
        }
        handler.postDelayed(arrowKeyRepeatRunnable!!, longPressDelay)
    }
    
    /**
     * 处理方向键释放
     */
    private fun handleArrowKeyUp() {
        isArrowKeyPressed = false
        currentArrowKey = null
        arrowKeyRepeatRunnable?.let {
            handler.removeCallbacks(it)
            arrowKeyRepeatRunnable = null
        }
    }
    
    /**
     * 发送方向键的 ANSI 转义序列
     */
    private fun sendArrowKey(keyCode: Int) {
        val escapeSequence = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"    // ESC [ A
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"  // ESC [ B
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C" // ESC [ C
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"   // ESC [ D
            else -> return
        }
        inputCallback?.invoke(escapeSequence)
    }
    
    /**
     * 检查是否可以显示输入法
     */
    override fun onCheckIsTextEditor(): Boolean {
        return isFullscreenMode
    }
    
    /**
     * 更新终端窗口大小
     */
    private fun updateTerminalSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // 提交帧「改之前」的位移快照（见 offsetBeforeCommitPx）。在这里就取走：本函数中途
        // 有早退分支（行列数没变），留着会被下一次无关的 resize 误用。
        val offsetBeforeCommit = offsetBeforeCommitPx
        offsetBeforeCommitPx = null
        val contentTop = getTerminalContentTop()
        val contentHeight =
            (height - contentTop - committedImeBottomInsetPx).toInt().coerceAtLeast(1)

        // 1. 记录当前的滚动状态（基于行数，而不是像素）
        // 这样在缩放后可以恢复到相同的逻辑位置
        val oldCharHeight = textMetrics.charHeight
        val currentScrollRows = if (oldCharHeight > 0) scrollOffsetY / oldCharHeight else 0f

        // 视口变矮之前，用户是否正贴着内容底部（也就是光标行可见）。
        // 只有这种情况才允许把锚点从「顶部行」换成「底部行」：键盘弹出时内容整体让位，
        // 屏幕上用户看到的那几行必须原地不动，否则会先被顶走一屏、等 PTY 重绘后再跳回来。
        val previousContentHeight = previousTerminalContentHeightPx
        val pinnedToBottomBefore =
            previousContentHeight <= 0f ||
                scrollOffsetY >=
                    (emulator?.getFullContent()?.size ?: 0) * oldCharHeight -
                    previousContentHeight - 1f
        
        // 确保字体指标已更新（基于当前缩放因子）
        textMetrics.updateFromRenderConfig(config.copy(fontSize = config.fontSize * scaleFactor))

        // 计算终端尺寸（行和列）
        // 现在终端模拟器支持重排，可以正常根据字体大小调整列数
        val cols = (width / textMetrics.charWidth).toInt().coerceAtLeast(1)
        val rows = (contentHeight / textMetrics.charHeight).toInt().coerceAtLeast(1)
        
        // 只有当尺寸真正发生变化时才更新
        if (rows == cachedRows && cols == cachedCols) {
            // 行数没变 = 这次提交量不到一行，滚动不动。但 committed 一变，位移里的
            // (animated - committed) 就归零了，屏幕位置会差出这一帧原本的位移；把整份
            // 位移原样挪进残余，位置就还是连续的（几十像素以内，缓出一下就没了）。
            if (offsetBeforeCommit != null && imeFollowEnabled) {
                val residual = offsetBeforeCommit.toFloat()
                if (abs(residual) >= 2f) {
                    commitResidualPx = residual
                    commitResidualStartMs = android.os.SystemClock.uptimeMillis()
                }
            }
            previousTerminalContentHeightPx = contentHeight.toFloat()
            return
        }
        
        cachedRows = rows
        cachedCols = cols
        Log.d(
            "CanvasTerminalView",
            "terminal resize: ${cols}x${rows} viewport=${contentHeight.toInt()}px " +
                "committedIme=${committedImeBottomInsetPx}px pinnedBefore=$pinnedToBottomBefore"
        )

        // 更新模拟器尺寸
        emulator?.resize(cols, rows)
        
        // 2. 恢复滚动位置
        // 使用新的行高计算新的像素偏移量
        if (emulator != null) {
            val newCharHeight = textMetrics.charHeight
            // 重新计算最大滚动范围
            val fullContentSize = emulator?.getFullContent()?.size ?: 0
            val maxScrollOffset = max(0f, fullContentSize * newCharHeight - contentHeight)
            val viewportShrank =
                previousContentHeight > 0f && contentHeight < previousContentHeight

            val scrollBefore = scrollOffsetY
            // 「贴底」一律以**光标行**为准，不用缓冲区末尾：模拟器的缓冲区常把光标下方
            // 补成空行，按末尾贴底会把提示行顶到屏幕中间，紧接着 next draw 的
            // needScrollToBottom 再按光标纠正一次 —— 那一下就是用户看到的「突然补到正确位置」。
            val cursorRowAfter = (emulator?.getHistorySize() ?: 0) + (emulator?.getCursorY() ?: 0)
            val cursorPinnedScroll =
                ((cursorRowAfter + 1) * newCharHeight - contentHeight).coerceIn(0f, maxScrollOffset)
            val targetScroll =
                if (pinnedToBottomBefore) {
                    // 键盘压矮（开）和弹回变高（收）两个方向都用同一把尺子：
                    // 提交后光标行仍落在视口底部。
                    cursorPinnedScroll
                } else {
                    // 用户正在翻历史（没贴底）：保持顶部行不变，多出来的行在下方展开。
                    (currentScrollRows * newCharHeight).coerceIn(0f, maxScrollOffset)
                }
            // 只有「键盘沉降提交」这一条路径需要残余位移；旋转/缩放等 resize 没有这一帧，
            // 保持旧行为：滚动直接到位，不留拖尾。
            val offBefore =
                if (offsetBeforeCommit != null && imeFollowEnabled) {
                    offsetBeforeCommit.toFloat()
                } else {
                    null
                }
            // 滚动直接赋值，不再缓动：屏幕位置 = -scroll - O。沉降提交按定义就是「把动画
            // 终值写进 committed」，所以提交帧 (animated - committed) 恰为 0，基准位移归零，
            // 残余 = 改之前的屏幕位置 减 改之后的屏幕位置 = scrollBefore + offBefore - scroll。
            // 然后这 120ms 里残余衰减到 0，位置一丝不跳地落到新的贴底点上。
            // （缓动滚动会让每帧的行范围、命中映射都在变，观感是「先跳一下再滑过去」。）
            scrollOffsetY = targetScroll
            if (offBefore != null) {
                val residual = scrollBefore + offBefore - scrollOffsetY
                if (abs(residual) >= 2f) {
                    commitResidualPx = residual
                    commitResidualStartMs = android.os.SystemClock.uptimeMillis()
                } else {
                    // 贴底且行数刚好对齐时这里恒为 0：提交那一帧屏幕位置就已经完全相等。
                    commitResidualPx = 0f
                }
            }
            // (c) 滚动重锚点：scroll / 残余就是「提交帧会不会跳」的两个变量
            if (viewportShrank || scrollBefore != targetScroll || commitResidualPx != 0f) {
                imeDbg(
                    "vb.resize rows=" + rows + " contentH=" + contentHeight.toInt() +
                        " pinnedBefore=" + pinnedToBottomBefore +
                        " scroll=" + scrollBefore.toInt() + "->" + targetScroll.toInt() +
                        " max=" + maxScrollOffset.toInt() +
                        " offBefore=" + (offBefore ?: -1f).toInt() +
                        " residual=" + commitResidualPx.toInt() +
                        " offsetY=" + getTerminalVisualOffsetY()
                )
            }
        }
        previousTerminalContentHeightPx = contentHeight.toFloat()
        
        // 同步 PTY 窗口尺寸
        // 使用后台线程执行，避免ANR（特别是在SSH会话或PTY阻塞时）
        val targetPty = pty
        Thread {
            try {
                targetPty?.setWindowSize(rows, cols)
            } catch (e: Exception) {
                Log.e("CanvasTerminalView", "Failed to update PTY window size", e)
            }
        }.start()
    }
}
