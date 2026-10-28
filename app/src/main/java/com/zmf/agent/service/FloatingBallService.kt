package com.zmf.agent.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.zmf.agent.MainActivity
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.ui.float.ChatBridge
import com.zmf.agent.ui.float.FloatingChatWindow
import com.zmf.agent.ui.float.LocalFloatInputFocus
import com.zmf.agent.ui.float.SiriBall
import com.zmf.agent.ui.theme.AgentTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 系统悬浮球 + 小窗（形态与手势借鉴常见悬浮窗产品，代码为本项目重写）。
 *
 * 只有一个窗口：一个 Service、一份 WindowManager.LayoutParams、一个 ComposeView。
 * 球和小窗是同一个窗口的两种形态：原地改 width/height/x/y/flags，绝不给窗口尺寸做动画；
 * 视觉过渡交给 Compose 的 AnimatedContent（球→窗 400ms / 窗→球 350ms）。
 *
 * 球态：52dp 精确像素，flags = NOT_FOCUSABLE | LAYOUT_NO_LIMITS；
 *       可跟手拖动（松手位置存进 prefs，重启回原处），点击展开；关闭只走小窗的 ✕，没有磁吸。
 * 小窗态：默认 240x320dp（最小 150x200dp，最大 = 屏幕 − 8dp），可拖标题栏中间那段空白移动、
 *       可拖右边缘 / 底边改宽高（锚点一律左上角：resize 只改 width/height，不碰 x/y）。
 *       展开时**一次性**把 flags 设成可获焦
 *       （NOT_TOUCH_MODAL | WATCH_OUTSIDE_TOUCH，清掉 NOT_FOCUSABLE），整个展开期不再随焦点改
 *       上一版就是随焦点来回切 flags 才让输入法一闪一闪的，见 onInputFocusChanged。
 *
 * 位置的唯一真相是**球心**（ballCenterX/Y），球和小窗都不记自己的左上角：
 *       球态：球画在球心，可自由拖动，松手落盘；
 *       展开：以球心为中心反推窗口左上角（球心 - 尺寸/2），再把整个窗口夹进屏幕；
 *       折叠：球心先对齐到小窗中心，球自然停在小窗正中（不再回到折叠前的旧位置）；
 *       首次启动没有记录：球心取屏幕正中，于是小窗水平 + 竖直都居中。
 *       重启回原位靠的也是球心（ball_x / ball_y），window_x / window_y 已不再读。
 *
 * 窗口不依赖任何 Activity：App 退后台/被划掉都不影响它，进程活着窗口就在
 * （进程本身由 M7 的 AgentForegroundService 钉住，这里只负责开，不负责关）。
 * 本轮不做 onTaskRemoved 重拉、不做崩溃自愈、不加 wakeLock：不做后台兜底。
 *
 * 与主 App 同进程直连：消息、标题、发送都走 ChatBridge（ChatViewModel 的引用），没有 AIDL。
 */
class FloatingBallService : Service() {

    companion object {
        /** 悬浮球服务是否在跑（设置页「悬浮球」那一行读这个）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 球态尺寸：52dp（参考实现窗口 60dp 但内容只画到画布 86%，视觉更小；这里直接缩窗口）。 */
        private const val BALL_DP = 52
        /** 小窗默认尺寸：240x320dp（= 参考实现默认 300x400dp × 它的 scale 0.8；这里不做 scale）。 */
        private const val WINDOW_W_DP = 240
        private const val WINDOW_H_DP = 320
        /** 小窗最小尺寸：150x200dp（参考实现拖动 resize 用的那一套；它落盘那套 200/250 是 bug，不抄）。 */
        private const val WINDOW_MIN_W_DP = 150
        private const val WINDOW_MIN_H_DP = 200
        /**
         * 状态持久化：prefs 名 + key。
         *
         * 只存两样：**球的位置**（用户拖完球 / 小窗折叠时都记成球心）和**小窗尺寸**。
         * 小窗的展开坐标不再存：它永远由「球心」当场反推（见 applyExpandedLayout），
         * 所以 window_x / window_y 这条读路径已删：老版本写进去的值留在 prefs 里不读，
         * 既不会影响行为，也不用写迁移代码。
         */
        private const val PREF_NAME = "floating_window"
        private const val KEY_WINDOW_W = "window_width"
        private const val KEY_WINDOW_H = "window_height"
        private const val KEY_BALL_X = "ball_x"
        private const val KEY_BALL_Y = "ball_y"
        /** 贴边留白：球与小窗离屏幕边缘的最小距离，clamp 系列的上界都从它推。 */
        private const val MARGIN_DP = 8
        /** 球→窗：Compose 动画先起，窗口参数晚 100ms 再改。 */
        private const val EXPAND_LAYOUT_DELAY_MS = 100L
        /** 窗→球：窗口参数晚 150ms 再改。 */
        private const val COLLAPSE_LAYOUT_DELAY_MS = 150L
        /** 输入框获焦后请求 IME 的防抖窗口：200ms 内的重复请求直接忽略。 */
        private const val SHOW_IME_DEBOUNCE_MS = 200L

        /** WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION（@hide，只能写字面量）。 */
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040

        /** 球态：不可获焦 + 允许越界布局（球可拖到屏幕边缘，越界布局只为让光晕/涟漪不被系统裁）。 */
        private const val BALL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /**
         * 小窗态：**可获焦**（不再有 NOT_FOCUSABLE）+ 不吞外部触摸。
         *
         * 只在 applyExpandedLayout 里设这一次，输入框获焦/失焦都不再改。
         * 上一版在 NOT_FOCUSABLE 与可获焦之间来回切：窗口一失焦系统立刻收键盘，
         * 代码又 showSoftInput 叫起来，于是「弹出→关闭→再弹出」地闪。
         */
        private const val EXPANDED_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        /** 用户从 App 里点「悬浮小窗」时用 expand = true：直接展开成小窗，而不是先给一颗球。 */
        fun start(context: Context, expand: Boolean = false) {
            // 同步记一份「这次要直接展开」：服务可能已经在跑，只在 onCreate 里读 Intent 会漏；
            // 而 Service.getIntent() 在本工程的编译期解析不到（实测 Unresolved），所以走静态标记。
            pendingExpand = expand
            val intent = Intent(context, FloatingBallService::class.java)
                .putExtra(EXTRA_EXPAND, expand)
            // 没悬浮窗权限时 addView 会失败，onCreate 里已经兜住；这里只防 startService 自身抛
            runCatching { context.startService(intent) }
        }

        /** 启动参数：true = 一上来就展开成小窗。 */
        private const val EXTRA_EXPAND = "extra_expand"

        /** 下一次 onCreate 是否直接以小窗形态建窗（由 [start] 写入）。 */
        @Volatile
        private var pendingExpand = false

        /** 停掉悬浮球服务（用户关闭悬浮窗时调用）；失败静默。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingBallService::class.java)) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    /**
     * Service 里给 ComposeView 用的最小 LifecycleOwner。
     *
     * 注意：`androidx.lifecycle.ViewTreeLifecycleOwner` 在本工程**编译期不可见**
     * （依赖在运行期类路径上，Kotlin 解析不到），所以只能运行时反射挂到 View 树上。
     */
    private var lifecycleOwner: FloatLifecycleOwner? = null

    private var density = 0f
    private var ballSizePx = 0
    private var windowWidthPx = 0
    private var windowHeightPx = 0
    private var marginPx = 0
    private var screenW = 0
    private var screenH = 0
    private var touchSlop = 0

    /** Compose 侧状态：true = 小窗，false = 球。ComposeView 直接读这两个状态重组。 */
    private var expanded by mutableStateOf(false)
    /** onCreate 里是否已经按「直接展开」初始化过窗口参数（避免 onStartCommand 再切一次）。 */
    private var startExpandedHandled = false
    /** 球被按下（按住后滑开超过 slop 就撤掉），驱动球的 0.85 缩放。 */
    private var pressed by mutableStateOf(false)
    /**
     * 球动画开关：球态 + 屏幕亮着 + 窗口可见，三者同时成立才让 SiriBall 跑无限动画。
     *
     * 球是常驻窗口，看不见的时候（展开成小窗 / 锁屏息屏 / 被系统藏起来）继续每帧重绘
     * 整块 BlendMode.Screen 渐变画布是纯功耗，所以当形参传下去，不可见时 SiriBall
     * 连 InfiniteTransition 都不注册。
     */
    private var ballAnimating by mutableStateOf(true)
    /** 屏幕是否亮着：由 ACTION_SCREEN_ON / OFF 维护。 */
    private var screenOn = true

    /** 输入框是否获焦（只用来去重焦点回调，**不再**决定窗口 flags）。 */
    private var inputFocused = false
    /** 上一次请求 IME 的时刻（uptimeMillis），做 200ms 防抖用。 */
    private var lastShowImeAt = 0L
    /** IME 顶起来的高度（取不到就是 0，位置原地不动）。 */
    private var imeShift = 0
    /** 小窗展开时的基准 y（左上角，不含 IME 顶起的偏移）。 */
    private var expandedBaseY = 0
    /** 小窗展开时的基准 x（左上角）。和 expandedBaseY 成对：折叠时用它反推球心。 */
    private var expandedLeftX = 0
    /**
     * 球心（屏幕坐标）：球位置的**唯一真相**。
     *
     * 布局时球的左上角 = 球心 - BALL/2，小窗左上角 = 球心 - 窗口尺寸/2，
     * 所以「折叠时球停在小窗正中」和「展开时窗口以球心为中心」这两条规则
     * 是同一个中心点的两种用法，不需要再各存一份坐标。
     */
    private var ballCenterX = 0
    private var ballCenterY = 0
    /**
     * 手指位移的浮点残量：Compose 每帧可能只给零点几 px，直接取整会被吃掉（慢拖时窗口不动），
     * 所以攒够 1px 才真正挪，小数留到下一帧。球和小窗不会同时拖，共用这一组。
     */
    private var moveCarryX = 0f
    private var moveCarryY = 0f
    /** 调宽 / 调高的浮点残量（同上，每帧攒够 1px 才写 LayoutParams）。 */
    private var resizeCarryW = 0f
    private var resizeCarryH = 0f

    // ---- 球态手势的临时量（拖动 / 按下缩放 / 点击展开） ----
    private var downRawX = 0f
    private var downRawY = 0f
    /** 按下瞬间的球心（拖动时以它为基准算增量，避免累计误差）。 */
    private var startBallCenterX = 0
    private var startBallCenterY = 0
    private var dragging = false

    /** 球→窗：延后 100ms 才真正改窗口参数。 */
    private val expandLayoutTask = Runnable { applyExpandedLayout() }

    /** 窗→球：延后 150ms 才真正改窗口参数。 */
    private val collapseLayoutTask = Runnable { applyBallLayout() }

    /**
     * 屏幕亮灭：息屏 / 锁屏时用户根本看不见球，而系统不一定给 overlay 派发可见性回调，
     * 所以这两个系统广播单独盯一下。亮屏后 updateBallAnimation 会重新读一次窗口可见性。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            updateBallAnimation()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 从 App 里点「悬浮小窗」进来的这一路：**第一次加窗就按小窗尺寸加**，
        // 不先挂一颗球再切，否则会看到「球闪一下 → 变成小窗」。
        val startsExpanded = pendingExpand
        pendingExpand = false
        startExpandedHandled = startsExpanded
        if (startsExpanded) {
            expanded = true
        }
        try {
            addRoot()
        } catch (t: Throwable) {
            // 权限被收回 / 系统拒绝加窗：别留一个空转的服务
            FileLog.log("float", "加悬浮窗失败：" + t)
            isRunning = false
            runCatching { stopSelf() }
            return
        }
        // 息屏 / 锁屏时把球的动画停掉（见 screenReceiver）；注册失败不影响球本身，兜住就行
        runCatching {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }
        // 悬浮球自己是普通后台服务，Android 8+ 会在 App 退后台后回收它（球消失）。
        // 加窗成功后顺手拉起常驻前台服务，让整个进程处于「前台服务」状态，窗口才能一直在屏幕上。
        // 只负责开不负责关：onDestroy 不停它，用户关球不该连带关掉「后台运行」。
        if (!AgentForegroundService.isRunning) AgentForegroundService.start(this)
        FileLog.log("float", "悬浮窗已挂上，球态 " + ballSizePx + "px")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 「一进来就展开」已经在 onCreate 里处理过了；这里只兜「服务已在跑、又收到一次展开请求」。
        if (!startExpandedHandled) {
            if (intent?.getBooleanExtra(EXTRA_EXPAND, false) == true) {
                handler.post { if (root != null && !expanded) openChat() }
            }
        } else {
            startExpandedHandled = false
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        // 注册可能没成功过（onCreate 里的 runCatching 兜住的失败路径），unregister 会抛
        // IllegalArgumentException：销毁路径不该因为收尾失败而崩
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        removeRoot()
        FileLog.log("float", "悬浮窗已移除")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏 / 分屏后屏幕尺寸变了：重算尺寸与边界，别让窗口停在屏幕外
        readScreen()
        computeExpandedSize()
        if (expanded) {
            // 展开态转屏：先把球心对齐到「当前小窗中心」，否则 applyExpandedLayout 会按
            // 折叠前那颗球的中心重新摆位，窗口看着像「跳回上一处」。对齐后新屏幕只是重新夹一遍。
            syncBallCenterToWindowCenter()
            applyExpandedLayout()
            return
        }
        // 球态转屏：球心原地不动，只按新屏幕重新夹一次（别把用户拖到的位置当「固定在右边」）
        ballCenterX = clampBallCenterX(ballCenterX)
        ballCenterY = clampBallCenterY(ballCenterY)
        val lp = params ?: return
        lp.x = ballLeftX()
        lp.y = ballTopY()
        updateLayout()
    }

    // ---- 窗口 ----

    private fun addRoot() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        density = resources.displayMetrics.density
        ballSizePx = (BALL_DP * density).roundToInt()
        marginPx = (MARGIN_DP * density).roundToInt()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        readScreen()
        computeExpandedSize()
        // 读回上次的尺寸 / 球心（第一次装取不到，球心取屏幕正中，见 restoreWindowState）
        restoreWindowState()
        // 直接以小窗形态启动时，先把窗口左上角按球心反推出来（addRoot 里要用）
        if (expanded) computeExpandedOrigin()

        // Service 里没有 Activity，ComposeView 在 attach 时会找 ViewTreeLifecycleOwner，
        // 找不到就抛 IllegalStateException，所以必须自己挂一个（反射，见 attachLifecycleOwner）。
        val owner = FloatLifecycleOwner()
        owner.start()
        lifecycleOwner = owner

        // 普通的 ComposeView（ComposeView 在 1.12 里是 final，不能匿名继承去重写可见性回调）。
        // 可见性通知挂 ViewTreeObserver：系统改了这条窗口的可见性（切到全屏 App、锁屏、下拉通知栏……）时
        // 看不见就把球的动画停掉，恢复可见再打开；球被折叠成小窗时由 applyExpandedLayout 关掉。
        val view = ComposeView(this).apply {
            attachLifecycleOwner(this, owner)
            // 不用池化策略：dispose 只跟 detach 走
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = true
            isFocusableInTouchMode = true
            setContent {
                AgentTheme {
                    CompositionLocalProvider(
                        LocalFloatInputFocus provides { focused -> onInputFocusChanged(focused) }
                    ) {
                        FloatRoot()
                    }
                }
            }
        }
        view.viewTreeObserver.addOnWindowVisibilityChangeListener { updateBallAnimation() }
        // 球的按下/点击由这里统一派发（不移动窗口）；展开后一律放行给 Compose
        view.setOnTouchListener { _, event -> handleTouch(event) }
        bindImeInsets(view)

        val initialWidth = if (expanded) windowWidthPx else ballSizePx
        val initialHeight = if (expanded) windowHeightPx else ballSizePx
        val lp = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (expanded) EXPANDED_FLAGS else BALL_FLAGS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // 键盘永远不让系统去推窗口：位置由我们自己按 ime inset 顶
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        // 初始位置：球态用球心，小窗态用球心反推出来的左上角
        lp.x = if (expanded) expandedLeftX else ballLeftX()
        lp.y = if (expanded) expandedBaseY else ballTopY()
        noMoveAnimation(lp)

        wm.addView(view, lp)
        root = view
        params = lp
        // 再算一次开关：addView 过程中就可能派发过 onWindowVisibilityChanged，
        // 那时 root 还没赋值，算出来必然是 false
        updateBallAnimation()
    }

    private fun removeRoot() {
        val view = root ?: return
        root = null
        params = null
        // 先让 recomposer 停下来，再把 View 摘掉（DisposeOnDetachedFromWindow 会跟着收尾）
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        val wm = windowManager
        windowManager = null
        if (view.isAttachedToWindow) runCatching { wm?.removeView(view) }
    }

    /** 统一的 updateViewLayout（每次都可能被系统拒，一律兜住）。 */
    private fun updateLayout() {
        val wm = windowManager ?: return
        val view = root ?: return
        val lp = params ?: return
        runCatching { wm.updateViewLayout(view, lp) }
    }

    /**
     * privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION：形变时系统别自己插一段位移动画，
     * 否则每次 updateViewLayout 都能看到系统自带的移动过渡，跟 Compose 的过渡打架。
     * 这个字段是 @hide，编译期拿不到，只能运行时反射加；失败就当没加（功能不受影响）。
     */
    private fun noMoveAnimation(lp: WindowManager.LayoutParams) {
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(lp, field.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    /**
     * 重算球的动画开关，写进 Compose 状态。
     *
     * 可见性不另存一份标志位，每次直接读 view.windowVisibility：
     * 广播和 onWindowVisibilityChanged 的到达顺序不保证，存两份迟早对不上。
     */
    private fun updateBallAnimation() {
        ballAnimating = !expanded && screenOn && root?.windowVisibility == View.VISIBLE
    }

    // ---- Compose 内容 ----

    @Composable
    private fun FloatRoot() {
        // 形态切换的视觉全交给 AnimatedContent：球→窗 400ms（delay 100）、窗→球 350ms（delay 150）
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                if (targetState) {
                    // 球 → 窗：新形态 400ms、delay 100（正好是窗口参数改成小窗尺寸的时刻）；
                    // 旧形态只在原尺寸里 100ms 淡出，不会拖到窗口变大以后
                    (
                        fadeIn(tween(durationMillis = 400, delayMillis = 100)) +
                            scaleIn(tween(durationMillis = 400, delayMillis = 100), initialScale = 0f)
                        ).togetherWith(fadeOut(tween(durationMillis = 100)))
                } else {
                    // 窗 → 球：新形态 350ms、delay 150（正好是窗口缩回球尺寸的时刻）；
                    // 旧形态在原地 150ms 淡出，窗口缩小时屏幕上已经只剩空气，不会看到被拉伸的内容
                    (
                        fadeIn(tween(durationMillis = 350, delayMillis = 150)) +
                            scaleIn(tween(durationMillis = 350, delayMillis = 150), initialScale = 0f)
                        ).togetherWith(fadeOut(tween(durationMillis = 150)))
                }
            },
            label = "float-morph"
        ) { isExpanded ->
            if (isExpanded) {
                FloatingChatWindow(
                    messages = ChatBridge.messages(),
                    title = ChatBridge.title(),
                    onSend = { text -> ChatBridge.send(text) },
                    // 「回主页」：先把主界面拉到前台（NEW_TASK 复用已有任务，不重建），
                    // 再把整套悬浮窗收掉：回到主页后小窗和球一起消失
                    onHome = {
                        openApp()
                        closeEverything()
                    },
                    // 「∨」折叠：窗口原地缩回球，服务不停
                    onCollapse = { collapseChat() },
                    // 「✕」关闭：整套收掉（窗口移除 + 服务停止，球也不留）
                    onClose = { closeEverything() },
                    // 标题栏中间空白拖出来的位移（px，1:1 跟手）→ 服务只改 x/y，松手才落盘
                    onMoveBy = { dx, dy -> moveWindowBy(dx, dy) },
                    // 右边缘竖条 → 只改 width；底边横条 → 只改 height（锚点都是左上角）
                    onResizeWidth = { dx -> resizeWindowWidth(dx) },
                    onResizeHeight = { dy -> resizeWindowHeight(dy) },
                    // 一次手势松手：服务在这里写一次 prefs（不学参考实现每帧写盘）
                    onGestureEnd = { onWindowGestureEnd() }
                )
            } else {
                // 球永远画在 60dp 自己的盒子里：过渡期窗口可能还是小窗尺寸，
                // 不固定尺寸的话 Canvas 会按窗口大小画出一个 150dp 的巨型球
                Box(
                    modifier = Modifier
                        .size(BALL_DP.dp)
                        // 球态拖动必须走 Compose：ComposeView 上的 setOnTouchListener 在球态
                        // **收不到事件**（SiriBall 的 clickable 先把 DOWN 消费掉了），
                        // 旧 handleTouch 那条拖动路径永远不动，这里补上真正的跟手拖动。
                        // detectDragGestures 过了 touchSlop 才算拖动，球自己的点击不受影响。
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { pressed = true },
                                onDragEnd = {
                                    pressed = false
                                    saveBallPosition()
                                },
                                onDragCancel = { pressed = false }
                            ) { _, dragAmount ->
                                moveBallBy(dragAmount.x, dragAmount.y)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // visible = 球真的看得见吗（球态 + 屏幕亮 + 窗口可见）；不可见时它不注册任何动画
                    SiriBall(pressed = pressed, visible = ballAnimating, onClick = { openChat() })
                }
            }
        }
    }

    // ---- 交互：按下缩放 / 点击展开（球拖动在 FloatRoot 的 pointerInput 里） ----

    /**
     * 球态触摸派发（**兜底路径**）。
     *
     * 注意：ComposeView 的 setOnTouchListener 在球态其实**收不到事件**：ViewGroup 先把触摸
     * 派给子 View（AndroidComposeView），SiriBall 的 clickable 消费掉 DOWN 之后，父 View 的
     * listener 就不会被调用了。所以真正的球拖动在 FloatRoot 的 pointerInput 里
     * （见 moveBallBy / saveBallPosition），这里保留是为了：
     * 展开态直接放行、以及万一某个系统版本把事件派到父 View 时不至于完全没反应。
     *
     * 球态：可自由拖动（跟手移动，松手停在原地并落盘）；没拖动就是点击 = 展开小窗。
     *       球上不再有任何关闭手势，关闭只走小窗里的 ✕（见 closeEverything）。
     * 展开态：位置 / 尺寸交给小窗自己的手势（见 FloatingChatWindow），这里直接 return false。
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        if (expanded) {
            // 展开态：小窗里的滚动、点按、输入框全归 Compose，这里一个事件都不吃
            pressed = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startBallCenterX = ballCenterX
                startBallCenterY = ballCenterY
                dragging = false
                pressed = true
            }

            MotionEvent.ACTION_MOVE -> {
                val lp = params
                if (lp != null) {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        // 一开拖就撤掉按压的缩小态：跟手移动的时候球该是原尺寸
                        pressed = false
                    }
                    if (dragging) {
                        // 跟手：以按下时的球心为基准加位移，夹在屏幕内（只动中心点，左上角由中心算）
                        ballCenterX = clampBallCenterX((startBallCenterX + dx).toInt())
                        ballCenterY = clampBallCenterY((startBallCenterY + dy).toInt())
                        lp.x = ballLeftX()
                        lp.y = ballTopY()
                        updateLayout()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val tapped = pressed && !dragging
                pressed = false
                if (dragging) {
                    // 松手停在原地：球心已经是最新值，直接落盘（折叠回球 / 重启都回这里）
                    dragging = false
                    saveBallPosition()
                } else if (tapped) {
                    // 没拖动 = 点一下球 → 展开小窗
                    openChat()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = false
            }
        }
        return true
    }

    /**
     * 首次启动（prefs 里没有球坐标）时的球心 X：屏幕水平正中。
     *
     * 旧版是「贴右侧边」，但新规则下小窗位置由球心反推，球心居中才得到居中的小窗。
     */
    private fun defaultBallCenterX(): Int = clampBallCenterX(screenW / 2)

    /** 首次启动时的球心 Y：屏幕竖直正中（配合 X 就是窗口居中）。 */
    private fun defaultBallCenterY(): Int = clampBallCenterY(screenH / 2)

    // ---- 回到 App / 整体关闭 ----

    /** 「回主页」：FLAG_ACTIVITY_NEW_TASK 拉回 MainActivity，复用已有任务（不重建，聊天记录还在）。 */
    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 整套收掉：摘窗口 + 停服务，球也不留下。「✕」和「回主页」都走这里。
     * 只停自己，不碰 M7 的 AgentForegroundService（关悬浮窗不该连带关掉「后台运行」）。
     */
    private fun closeEverything() {
        expanded = false
        inputFocused = false
        handler.removeCallbacks(expandLayoutTask)
        handler.removeCallbacks(collapseLayoutTask)
        // 窗口马上要被摘掉：先主动收一次键盘，别让 IME 悬在那等
        hideIme()
        FileLog.log("float", "用户关闭悬浮窗")
        stopSelf()
    }

    // ---- 形态切换（窗口参数延后改） ----

    /** 点球：立刻切 Compose 形态，窗口参数延后 100ms 改（先让动画起来）。 */
    private fun openChat() {
        if (expanded) return
        expanded = true
        // 小窗位置不读历史，只由球心反推。这里先算一次逻辑值：万一下次点击早于
        // 100ms 的窗口参数延迟（用户点开就立刻折叠），折叠那条路径也不会用到过期坐标。
        computeExpandedOrigin()
        handler.removeCallbacks(collapseLayoutTask)
        handler.postDelayed(expandLayoutTask, EXPAND_LAYOUT_DELAY_MS)
    }

    /**
     * 折叠回球：立刻切 Compose 形态，窗口参数延后 150ms 改。
     *
     * 球心在**切换这一瞬间**对齐到小窗中心（不是每帧算，也不回折叠前的旧位置），
     * 顺手落一次盘，于是「拖完小窗再折叠」之后重启，球还在那一处。
     */
    private fun collapseChat() {
        if (!expanded) return
        expanded = false
        inputFocused = false
        // 只算一次：球心 = 当前小窗中心（键盘收起时的位置）
        syncBallCenterToWindowCenter()
        persistBallPosition()
        // 窗口马上要变回不可获焦，主动收一次键盘比等系统收更干净
        hideIme()
        handler.removeCallbacks(expandLayoutTask)
        handler.postDelayed(collapseLayoutTask, COLLAPSE_LAYOUT_DELAY_MS)
    }

    /**
     * 以球心反推小窗左上角并夹进屏幕：纯计算，不写窗口参数。
     *
     *     左 = 球心X - 窗口宽/2
     *     上 = 球心Y - 窗口高/2
     *     再夹到 [margin, 屏幕 - 尺寸 - margin]（见 clampWindowLeftInScreen / clampWindowTopInScreen）
     *
     * 展开时算一次存进 expandedLeftX / expandedBaseY，折叠时再用它们反推球心，
     * 于是「折叠→球在窗口正中」与「展开→窗口以球心为中心」互为逆运算。
     */
    private fun computeExpandedOrigin() {
        expandedLeftX = clampWindowLeftInScreen(ballCenterX - windowWidthPx / 2, windowWidthPx)
        expandedBaseY = clampWindowTopInScreen(ballCenterY - windowHeightPx / 2, windowHeightPx)
    }

    /**
     * 把球心对齐到「当前小窗的中心」。
     *
     * 用 expandedLeftX / expandedBaseY / windowWidthPx / windowHeightPx 这四个**逻辑值**，
     * 而不是直接读 lp：lp 里可能还是球尺寸（展开参数延后 100ms 才写），
     * 也可能带着 IME 顶起的临时 y 偏移，读取它们会把球心算歪。
     */
    private fun syncBallCenterToWindowCenter() {
        ballCenterX = clampBallCenterX(expandedLeftX + windowWidthPx / 2)
        ballCenterY = clampBallCenterY(expandedBaseY + windowHeightPx / 2)
    }

    /**
     * 改成小窗尺寸与位置。
     *
     * 位置：**不读历史坐标**，一律以球心为中心反推左上角：
     *       winLeft = 球心X - 宽/2，winTop = 球心Y - 高/2，
     *       再把左上角夹到 [margin, 屏幕 - 尺寸 - margin]，夹完整个窗口仍在屏内
     *       （窄屏上界可能小于下界，那时落在贴 margin 处，见 clampWindowLeftInScreen）。
     *       首次启动球心 = 屏幕中心，于是小窗水平 + 竖直都居中。
     * 尺寸：用当前 windowWidthPx / windowHeightPx（默认 240x320dp；拖过就是拖到的大小）。
     * 这里仍是展开期**唯一**设 flags 的地方，之后焦点怎么变都不再动。
     */
    private fun applyExpandedLayout() {
        if (!expanded) return
        val lp = params ?: return
        computeExpandedSize()
        lp.width = windowWidthPx
        lp.height = windowHeightPx
        // 左上角锚点：x/y 就是窗口左上角（gravity = TOP|START），resize 只改 width/height、不碰 x/y
        computeExpandedOrigin()
        lp.x = expandedLeftX
        // IME 顶起的偏移照旧：只在逻辑基准 y 上减，expandedBaseY 始终是「键盘收起时」的位置
        lp.y = expandedBaseY - imeShift
        // 整个展开期就设这一次：可获焦 + 不吞外部触摸；之后焦点怎么变都不再动 flags
        lp.flags = EXPANDED_FLAGS
        noMoveAnimation(lp)
        updateLayout()
        // 窗口此时已经是可获焦的，直接把焦点要到窗口上（只此一次）。
        // 不再用「改 flags 间接拿焦点」：那条路每次都连带把 IME 收掉，键盘就闪。
        root?.post { runCatching { root?.requestFocus() } }
        // 小窗铺开了，球在淡出：把球的无限动画关掉（它随后就不再参与组成了）
        updateBallAnimation()
        FileLog.log("float", "展开小窗 " + windowWidthPx + "x" + windowHeightPx + "@" + lp.x + "," + lp.y)
    }

    /** 改回球尺寸与位置（球心就是折叠时记下的「小窗中心」，与拖动共用同一套夹取）。 */
    private fun applyBallLayout() {
        if (expanded) return
        val lp = params ?: return
        imeShift = 0
        lp.width = ballSizePx
        lp.height = ballSizePx
        // 球心已经夹过一次，这里再夹一次兜底（转屏 / 折叠途中屏幕尺寸变了的情况）
        ballCenterX = clampBallCenterX(ballCenterX)
        ballCenterY = clampBallCenterY(ballCenterY)
        lp.x = ballLeftX()
        lp.y = ballTopY()
        // 折叠成球才设回「不可获焦 + 越界布局」
        lp.flags = BALL_FLAGS
        noMoveAnimation(lp)
        updateLayout()
        // 球又要画了：动画打开。此时是新 remember 出来的 InfiniteTransition，
        // 时钟从 0 起，所以不会停在展开前那一帧静态画面上
        updateBallAnimation()
    }

    // ---- 小窗移动 / 尺寸调整（锚点一律左上角）----

    /**
     * 小窗整体移动：dx/dy 是手指位移的 px（热区是标题栏中间那块空白）。
     *
     * 没有 scale，所以参考实现的 x += dx * s 在这里就是 x += dx（1:1 跟手）。
     * 每帧只改 LayoutParams、**不落盘**；位置也不再持久化：折叠那一刻球心记成窗口中心，
     * 重启后小窗位置由球心反推回来，所以「拖过的小窗」照样回原位。
     */
    private fun moveWindowBy(dx: Float, dy: Float) {
        if (!expanded) return
        val lp = params ?: return
        moveCarryX += dx
        moveCarryY += dy
        val stepX = moveCarryX.toInt()
        val stepY = moveCarryY.toInt()
        if (stepX == 0 && stepY == 0) return
        moveCarryX -= stepX
        moveCarryY -= stepY
        lp.x = clampWindowLeftInScreen(lp.x + stepX, windowWidthPx)
        lp.y = clampWindowTopInScreen(lp.y + stepY, windowHeightPx)
        // 逻辑基准跟着走（折叠时靠它们反推球心，转屏时靠它们保持原地），
        // x 走「键盘无关」的 lp.x；y 记「键盘收起时」的基准，别把 IME 的临时上移当成用户位置
        expandedLeftX = lp.x
        expandedBaseY = lp.y + imeShift
        updateLayout()
    }

    /**
     * 右侧手柄：**只改宽**，锚点左上角：绝不碰 x/y（左边缘固定，右边缘向右长）。
     * 每帧夹到 [150dp, 屏宽 − 8dp]；松手才落盘。
     */
    private fun resizeWindowWidth(dx: Float) {
        if (!expanded) return
        val lp = params ?: return
        resizeCarryW += dx
        val step = resizeCarryW.toInt()
        if (step == 0) return
        resizeCarryW -= step
        val next = (windowWidthPx + step).coerceIn(minWindowWidthPx(), maxWindowWidthPx())
        if (next == windowWidthPx) {
            // 已经顶到上下限：清掉余量，反向拖时不用先「还债」
            resizeCarryW = 0f
            return
        }
        windowWidthPx = next
        lp.width = next
        updateLayout()
    }

    /**
     * 底部手柄：**只改高**，锚点左上角：上边缘固定，下边缘向下长。
     * 每帧夹到 [200dp, 屏高 − 8dp]；松手才落盘。
     */
    private fun resizeWindowHeight(dy: Float) {
        if (!expanded) return
        val lp = params ?: return
        resizeCarryH += dy
        val step = resizeCarryH.toInt()
        if (step == 0) return
        resizeCarryH -= step
        val next = (windowHeightPx + step).coerceIn(minWindowHeightPx(), maxWindowHeightPx())
        if (next == windowHeightPx) {
            resizeCarryH = 0f
            return
        }
        windowHeightPx = next
        lp.height = next
        updateLayout()
    }

    /**
     * Compose 侧一次手势（移动 / 调宽 / 调高）松手：到这里才写一次 prefs。
     * 参考实现是每个 drag 帧都 apply() 一次（拖 1 秒写 60 次），这条**不抄**。
     */
    private fun onWindowGestureEnd() {
        // 残量清零：下一次手势从头累积，别把上一段的零头带过去
        moveCarryX = 0f
        moveCarryY = 0f
        resizeCarryW = 0f
        resizeCarryH = 0f
        persistWindowState()
    }

    // ---- 球移动 ----

    /**
     * 球拖动：跟手移动窗口，只改**球心**（整个球留在屏内），左上角每帧由球心算。
     * dx/dy 是手指位移 px；拖动中不落盘，松手在 [saveBallPosition] 里存一次。
     */
    private fun moveBallBy(dx: Float, dy: Float) {
        if (expanded) return
        val lp = params ?: return
        moveCarryX += dx
        moveCarryY += dy
        val stepX = moveCarryX.toInt()
        val stepY = moveCarryY.toInt()
        if (stepX == 0 && stepY == 0) return
        moveCarryX -= stepX
        moveCarryY -= stepY
        // 手势只改中心点，左上角每次由中心算，拖动过程中不落盘
        ballCenterX = clampBallCenterX(ballCenterX + stepX)
        ballCenterY = clampBallCenterY(ballCenterY + stepY)
        lp.x = ballLeftX()
        lp.y = ballTopY()
        updateLayout()
    }

    /** 球松手：球心就是新位置，落一次盘（折叠回球 / 重启都回这里）。 */
    private fun saveBallPosition() {
        if (expanded) return
        moveCarryX = 0f
        moveCarryY = 0f
        persistBallPosition()
    }

    // ---- 持久化（prefs = floating_window）----

    private fun prefs() = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

    /**
     * 存小窗尺寸：只在一次手势松手时调用一次。
     *
     * 位置**不存了**（window_x / window_y 这条读路径已删）：小窗位置永远由球心反推，
     * 而球心在折叠那一下才落盘：拖完小窗、折叠、重启，位置照样回得来。
     */
    private fun persistWindowState() {
        if (!expanded) return
        runCatching {
            prefs().edit()
                .putInt(KEY_WINDOW_W, windowWidthPx)
                .putInt(KEY_WINDOW_H, windowHeightPx)
                .apply()
        }
    }

    /**
     * 存球的位置：球拖动松手 / 小窗折叠时各调用一次。
     *
     * 落盘的仍是**左上角**（ball_x / ball_y 的历史语义），所以老版本存下的坐标升级后含义不变，
     * 读回来加半个球就是球心。
     */
    private fun persistBallPosition() {
        runCatching {
            prefs().edit()
                .putInt(KEY_BALL_X, ballLeftX())
                .putInt(KEY_BALL_Y, ballTopY())
                .apply()
        }
    }

    /**
     * addRoot 时读回上次的小窗尺寸 / 球心；读到的值一律再夹一次。
     *
     * 小窗位置完全不读：它由球心当场反推（applyExpandedLayout）。
     * 球心没存过（首次启动）就取屏幕正中，于是小窗也是水平 + 竖直居中。
     */
    private fun restoreWindowState() {
        val p = prefs()
        // 尺寸：与拖动时同一套 min/max（不学参考实现「拖到 150dp、存进去变 200dp」的两套上下限）；
        // 上限现在是「屏幕 − 8dp」，旧版本按 0.8 屏存下的尺寸读到这里会被新上限夹一次
        windowWidthPx = p.getInt(KEY_WINDOW_W, windowWidthPx)
            .coerceIn(minWindowWidthPx(), maxWindowWidthPx())
        windowHeightPx = p.getInt(KEY_WINDOW_H, windowHeightPx)
            .coerceIn(minWindowHeightPx(), maxWindowHeightPx())
        // 球位置：存的是左上角，加半个球还原成球心；没存过（Int.MIN_VALUE 哨兵）就走默认居中
        val left = p.getInt(KEY_BALL_X, Int.MIN_VALUE)
        val top = p.getInt(KEY_BALL_Y, Int.MIN_VALUE)
        ballCenterX = if (left == Int.MIN_VALUE) {
            defaultBallCenterX()
        } else {
            clampBallCenterX(left + ballSizePx / 2)
        }
        ballCenterY = if (top == Int.MIN_VALUE) {
            defaultBallCenterY()
        } else {
            clampBallCenterY(top + ballSizePx / 2)
        }
    }

    // ---- 焦点 / 键盘 ----

    /**
     * 输入框获焦/失焦：这里**只负责在真获焦时补一次 IME，绝不改窗口 flags**。
     *
     * 上一版的闪烁循环正是出在这里：焦点一变就把窗口在 NOT_FOCUSABLE ↔ 可获焦之间来回切，
     * 窗口一失焦系统立刻把键盘收掉，代码随后又 showSoftInput 把它叫起来，于是
     * 「弹出→关闭→再弹出」无限闪。现在：
     * 1) 展开态的 flags 在 applyExpandedLayout 里一次性设成可获焦，整个展开期不变；
     * 2) 这里只在输入框真正获焦时调一次 showSoftInput，并用 200ms 防抖吃掉连续重复回调；
     * 3) 失焦什么都不做（不 hideSoftInput），收键盘交给系统按窗口焦点自己处理。
     */
    private fun onInputFocusChanged(focused: Boolean) {
        if (inputFocused == focused) return
        inputFocused = focused
        // 失焦：不动 flags、不主动收键盘（收键盘 = 打断用户，也是上一版闪烁的一环）
        if (!focused) return
        if (!expanded) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShowImeAt < SHOW_IME_DEBOUNCE_MS) return
        lastShowImeAt = now
        FileLog.log("float", "输入框获焦，请求一次 IME")
        showIme()
    }

    private fun showIme() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
            val view = root ?: return
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideIme() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
            val view = root ?: return
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    /**
     * IME inset：系统按 SOFT_INPUT_ADJUST_NOTHING 不会动窗口，位置只能自己顶上去。
     * 取不到（inset 为 0 / 系统不给 overlay 窗口发 inset）就原地不动，绝不能因为取不到而崩。
     */
    private fun bindImeInsets(view: ComposeView) {
        runCatching {
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                runCatching {
                    val bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    // inset 是在布局流程里派发的，改窗口参数推到下一个消息，别在布局里重排
                    handler.post { runCatching { applyImeInset(bottom) } }
                }
                insets
            }
        }
    }

    private fun applyImeInset(bottomPx: Int) {
        val next = if (bottomPx > 0) bottomPx else 0
        if (next == imeShift) return
        imeShift = next
        if (!expanded) return
        val lp = params ?: return
        lp.y = (expandedBaseY - imeShift).coerceAtLeast(marginPx)
        updateLayout()
    }

    // ---- 位置 / 尺寸 ----

    private fun readScreen() {
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
    }

    /**
     * 小窗尺寸：默认 240x320dp；已经有尺寸（用户拖过 / 转屏前算过）就沿用，
     * 只按当前屏幕重新夹一遍：转屏后不会把拖到的大小忘掉。
     */
    private fun computeExpandedSize() {
        val defW = (WINDOW_W_DP * density).roundToInt()
        val defH = (WINDOW_H_DP * density).roundToInt()
        windowWidthPx = (if (windowWidthPx > 0) windowWidthPx else defW)
            .coerceIn(minWindowWidthPx(), maxWindowWidthPx())
        windowHeightPx = (if (windowHeightPx > 0) windowHeightPx else defH)
            .coerceIn(minWindowHeightPx(), maxWindowHeightPx())
    }

    /** 小窗最小宽：150dp。 */
    private fun minWindowWidthPx(): Int = (WINDOW_MIN_W_DP * density).roundToInt()

    /** 小窗最小高：200dp。 */
    private fun minWindowHeightPx(): Int = (WINDOW_MIN_H_DP * density).roundToInt()

    /**
     * 小窗最大宽：真实屏宽 − 8dp。
     *
     * 上一版是屏幕 × 0.8：1080 宽的机器最宽只到 864px，真机上不够用，所以上限改成
     * 「离屏幕边缘固定 8dp」（与 MARGIN_DP 同值同义，直接复用 marginPx）。
     * 与 clampWindowLeftInScreen 配套：宽度取到这个上限时，左上角夹取的上界正好落在 marginPx，
     * 于是窗口左右各留 8dp、严丝合缝贴在屏内。
     * 地板取最小宽：极小屏（或分屏）上「屏宽 − 8dp」可能比 150dp 还小，不能让 coerceIn 出现 min > max。
     */
    private fun maxWindowWidthPx(): Int =
        maxOf(minWindowWidthPx(), screenW - marginPx)

    /** 小窗最大高：真实屏高 − 8dp（地板取最小高，理由同上，避免 min > max）。 */
    private fun maxWindowHeightPx(): Int =
        maxOf(minWindowHeightPx(), screenH - marginPx)

    /** 球的左上角（布局时写进 lp 的就是它）：由球心减去半个球，落盘也用这一对值。 */
    private fun ballLeftX(): Int = ballCenterX - ballSizePx / 2

    private fun ballTopY(): Int = ballCenterY - ballSizePx / 2

    /** 球心的夹取：整个球留在屏内（球很小，不必用「留 2/3」那套）。 */
    private fun clampBallCenterX(cx: Int): Int {
        val min = marginPx + ballSizePx / 2
        val max = maxOf(min, screenW - ballSizePx / 2 - marginPx)
        return cx.coerceIn(min, max)
    }

    private fun clampBallCenterY(cy: Int): Int {
        val min = marginPx + ballSizePx / 2
        val max = maxOf(min, screenH - ballSizePx / 2 - marginPx)
        return cy.coerceIn(min, max)
    }

    /**
     * 小窗左上角横向夹取：x ∈ [margin, screenW - w - margin]，**整个窗口**留在屏内。
     *
     * 窄屏（窗口比屏幕还宽）时上界会小于下界，这里用 maxOf 抬到下界，
     * 结果是「贴 margin、右边超出屏幕」，至少不会算出负坐标或反向区间。
     */
    private fun clampWindowLeftInScreen(x: Int, w: Int): Int =
        x.coerceIn(marginPx, maxOf(marginPx, screenW - w - marginPx))

    /** 小窗左上角纵向夹取：y ∈ [margin, screenH - h - margin]，含义同上。 */
    private fun clampWindowTopInScreen(y: Int, h: Int): Int =
        y.coerceIn(marginPx, maxOf(marginPx, screenH - h - marginPx))
}

/**
 * 把 LifecycleOwner / SavedStateRegistryOwner 挂到 View 树上。
 *
 * 这两个类的 set 在本工程**编译期解析不到**（androidx.lifecycle.ViewTreeLifecycleOwner
 * 在运行期类路径上存在，Kotlin 编译器却看不到），所以用反射调用；
 * 反射失败不抛，交给上层兜（加窗失败会 stopSelf，不会留空转服务）。
 */
private fun attachLifecycleOwner(view: android.view.View, owner: Any) {
    runCatching {
        val cls = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
        cls.getMethod("set", android.view.View::class.java, LifecycleOwner::class.java)
            .invoke(null, view, owner as LifecycleOwner)
    }
    runCatching {
        val cls = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
        cls.getMethod("set", android.view.View::class.java, SavedStateRegistryOwner::class.java)
            .invoke(null, view, owner as SavedStateRegistryOwner)
    }
}

/**
 * Service 里给 ComposeView 用的最小 LifecycleOwner + SavedStateRegistryOwner。
 *
 * 没有 LifecycleOwner：ComposeView 一 attach 就抛
 * 「Composed into the View which doesn't propagate ViewTreeLifecycleOwner」。
 * 没有 SavedStateRegistryOwner：内部用到 rememberSaveable 的地方拿不到注册表。
 */
private class FloatLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** 挂上时推到 RESUMED：常驻动画需要它处于 runnable 状态。 */
    fun start() {
        runCatching {
            savedStateController.performAttach()
            savedStateController.performRestore(null as Bundle?)
        }
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** 摘窗口前推到 DESTROYED：recomposer 停掉，ComposeView 自己 dispose。 */
    fun destroy() {
        runCatching { registry.currentState = Lifecycle.State.DESTROYED }
    }
}

