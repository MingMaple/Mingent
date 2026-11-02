package com.zmf.agent.feature.float
import com.zmf.agent.capability.runtime.AgentForegroundService

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
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.feature.float.FloatingChatWindow
import com.zmf.agent.feature.float.LocalFloatInputFocus
import com.zmf.agent.feature.float.SiriBall
import com.zmf.agent.uikit.theme.AgentTheme
import com.zmf.agent.core.prefs.ThemeMode
import com.zmf.agent.core.prefs.ThemeStore
import kotlin.math.abs
import kotlin.math.roundToInt
import com.zmf.agent.capability.runtime.ChatBridge

/**
 * 系统悬浮球与小窗（形态与手势参考常见悬浮窗产品，代码为本项目实现）。
 *
 * 仅使用一个窗口：一个 Service、一份 WindowManager.LayoutParams、一个 ComposeView。
 * 球与小窗是同一窗口的两种形态：直接修改 width/height/x/y/flags，不对窗口尺寸做动画；
 * 视觉过渡由 Compose 的 AnimatedContent 完成（球→窗 400ms，窗→球 350ms）。
 *
 * 球态：52dp 精确像素，flags = NOT_FOCUSABLE | LAYOUT_NO_LIMITS；
 *       可跟随手指拖动（松手位置写入 prefs，重启后恢复），点击展开；关闭仅通过小窗的 ✕，不提供边缘吸附。
 * 小窗态：默认 240x320dp（最小 150x200dp，最大为屏幕尺寸减 8dp），可拖动标题栏中间空白区域移动、
 *       可拖动右边缘或底边调整宽高（锚点固定为左上角：resize 仅修改 width/height，不改变 x/y）。
 *       展开时一次性将 flags 设为可获焦
 *       （NOT_TOUCH_MODAL | WATCH_OUTSIDE_TOUCH，清除 NOT_FOCUSABLE），整个展开期不随焦点变化；
 *       若随焦点反复切换 flags，会导致输入法反复闪烁，详见 onInputFocusChanged。
 *
 * 位置的唯一基准是球心（ballCenterX/Y），球与小窗均不记录自身左上角：
 *       球态：球绘制在球心，可自由拖动，松手时写入 prefs；
 *       展开：以球心为中心反推窗口左上角（球心减尺寸一半），再将整个窗口限制在屏幕内；
 *       折叠：球心先对齐到小窗中心，球自然停在小窗正中（不回到折叠前的位置）；
 *       首次启动无记录：球心取屏幕中心，因此小窗水平与竖直均居中。
 *       重启后恢复位置同样依据球心（ball_x / ball_y），window_x / window_y 不读取。
 *
 * 窗口不依赖任何 Activity：应用退至后台或从最近任务中移除均不影响，进程存活时窗口即存在
 * （进程由 AgentForegroundService 维持，此处只负责启动，不负责停止）。
 * 本实现不处理 onTaskRemoved 重启、崩溃自愈与唤醒锁，不提供后台兜底。
 *
 * 与主应用同进程直连：消息、标题与发送均通过 ChatBridge（ChatViewModel 的引用）完成，不使用 AIDL。
 */
class FloatingBallService : Service() {

    companion object {
        /** 悬浮球服务是否正在运行（设置页「悬浮球」一行读取该值）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 球态尺寸：52dp（参考实现窗口为 60dp，但内容仅绘制到画布的 86%，视觉更小；此处直接缩小窗口）。 */
        private const val BALL_DP = 52
        /** 小窗默认尺寸：240x320dp（即参考实现默认 300x400dp 乘以其 scale 0.8；此处不使用 scale）。 */
        private const val WINDOW_W_DP = 240
        private const val WINDOW_H_DP = 320
        /** 小窗最小尺寸：150x200dp（与参考实现拖动 resize 时一致；其持久化使用的 200/250 存在缺陷，不予采用）。 */
        private const val WINDOW_MIN_W_DP = 150
        private const val WINDOW_MIN_H_DP = 200
        /**
         * 状态持久化：prefs 名与 key。
         *
         * 仅保存两项：球的位置（拖动结束与小窗折叠时均记录为球心）和小窗尺寸。
         * 小窗的展开坐标不保存：始终由球心即时反推（见 applyExpandedLayout），
         * 因此 window_x / window_y 读取路径已移除；旧版本写入的值保留在 prefs 中但不读取，
         * 既不影响行为，也无需编写迁移代码。
         */
        private const val PREF_NAME = "floating_window"
        private const val KEY_WINDOW_W = "window_width"
        private const val KEY_WINDOW_H = "window_height"
        private const val KEY_BALL_X = "ball_x"
        private const val KEY_BALL_Y = "ball_y"
        /** 边缘留白：球与小窗距屏幕边缘的最小距离，各 clamp 方法的上界由此推导。 */
        private const val MARGIN_DP = 8
        /** 球→窗：Compose 动画先启动，窗口参数延后 100ms 修改。 */
        private const val EXPAND_LAYOUT_DELAY_MS = 100L
        /** 窗→球：窗口参数延后 150ms 修改。 */
        private const val COLLAPSE_LAYOUT_DELAY_MS = 150L
        /** 输入框获焦后请求 IME 的防抖窗口：200ms 内的重复请求直接忽略。 */
        private const val SHOW_IME_DEBOUNCE_MS = 200L

        /** WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION（@hide，只能写字面量）。 */
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040

        /** 球态：不可获焦并允许越界布局（球可拖至屏幕边缘，越界布局仅为避免光晕与涟漪被系统裁剪）。 */
        private const val BALL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /**
         * 小窗态：可获焦（移除 NOT_FOCUSABLE）且不拦截外部触摸。
         *
         * 仅在 applyExpandedLayout 中设置一次，输入框获焦或失焦时均不修改。
         * 若在 NOT_FOCUSABLE 与可获焦之间反复切换，窗口失焦时系统会立即收起键盘，
         * 代码又调用 showSoftInput 弹出键盘，造成「弹出→关闭→再弹出」的闪烁。
         */
        private const val EXPANDED_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        /** 用户从应用内点击「悬浮小窗」时传入 expand = true：直接展开为小窗，而非先显示球。 */
        fun start(context: Context, expand: Boolean = false) {
            // 同步记录「本次需直接展开」：服务可能已在运行，仅在 onCreate 中读取 Intent 会遗漏；
            // 且 Service.getIntent() 在本工程编译期无法解析（实测 Unresolved），因此使用静态标记。
            pendingExpand = expand
            val intent = Intent(context, FloatingBallService::class.java)
                .putExtra(EXTRA_EXPAND, expand)
            // 无悬浮窗权限时 addView 会失败，onCreate 中已做兜底；此处仅防止 startService 自身抛出异常
            runCatching { context.startService(intent) }
        }

        /** 启动参数：true 表示立即展开为小窗。 */
        private const val EXTRA_EXPAND = "extra_expand"

        /** 下次 onCreate 是否直接以小窗形态创建窗口（由 [start] 写入）。 */
        @Volatile
        private var pendingExpand = false

        /** 停止悬浮球服务（用户关闭悬浮窗时调用）；失败时静默。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingBallService::class.java)) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    /**
     * Service 中供 ComposeView 使用的最小 LifecycleOwner。
     *
     * 注意：`androidx.lifecycle.ViewTreeLifecycleOwner` 在本工程编译期不可见
     * （依赖位于运行期类路径，Kotlin 无法解析），因此只能在运行时通过反射挂载到 View 树上。
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

    /** Compose 侧状态：true 表示小窗，false 表示球。ComposeView 直接读取该状态进行重组。 */
    private var expanded by mutableStateOf(false)
    /** onCreate 中是否已按「直接展开」初始化窗口参数（避免 onStartCommand 再次切换）。 */
    private var startExpandedHandled = false
    /** 球处于按下状态（按住后滑动超过 slop 即取消），驱动球的 0.85 缩放。 */
    private var pressed by mutableStateOf(false)
    /**
     * 球动画开关：球态、屏幕点亮与窗口可见三者同时成立时才让 SiriBall 运行动画。
     *
     * 球为常驻窗口，不可见时（展开为小窗、锁屏息屏或被系统隐藏）继续逐帧重绘整块
     * BlendMode.Screen 渐变画布会持续耗电，因此作为参数传递，不可见时 SiriBall 不注册 InfiniteTransition。
     */
    private var ballAnimating by mutableStateOf(true)
    /** 屏幕是否点亮：由 ACTION_SCREEN_ON / OFF 维护。 */
    private var screenOn = true

    /** 输入框是否获焦（仅用于去重焦点回调，不决定窗口 flags）。 */
    private var inputFocused = false
    /** 上一次请求 IME 的时刻（uptimeMillis），用于 200ms 防抖。 */
    private var lastShowImeAt = 0L
    /** IME 顶起的高度（无法获取时为 0，窗口位置不变）。 */
    private var imeShift = 0
    /** 小窗展开时的基准 y（左上角，不含 IME 顶起的偏移）。 */
    private var expandedBaseY = 0
    /** 小窗展开时的基准 x（左上角）。与 expandedBaseY 成对使用：折叠时据此反推球心。 */
    private var expandedLeftX = 0
    /**
     * 球心（屏幕坐标）：球位置的唯一基准。
     *
     * 布局时球左上角为球心减 BALL/2，小窗左上角为球心减窗口尺寸/2，
     * 因此「折叠时球停在小窗正中」与「展开时窗口以球心为中心」是同一中心点的两种用法，
     * 无需分别保存坐标。
     */
    private var ballCenterX = 0
    private var ballCenterY = 0
    /**
     * 手指位移的浮点残量：Compose 每帧可能仅提供不足 1px 的位移，直接取整会丢失
     * （慢速拖动时窗口不移动），因此累积到 1px 才实际移动，小数保留到下一帧。
     * 球与小窗不会同时拖动，共用该组变量。
     */
    private var moveCarryX = 0f
    private var moveCarryY = 0f
    /** 调宽与调高的浮点残量（同上，每帧累积到 1px 才写入 LayoutParams）。 */
    private var resizeCarryW = 0f
    private var resizeCarryH = 0f

    // ---- 球态手势的临时变量（拖动、按下缩放、点击展开） ----
    private var downRawX = 0f
    private var downRawY = 0f
    /** 按下瞬间的球心（拖动时以此为基准计算增量，避免累计误差）。 */
    private var startBallCenterX = 0
    private var startBallCenterY = 0
    private var dragging = false

    /** 球→窗：延后 100ms 才实际修改窗口参数。 */
    private val expandLayoutTask = Runnable { applyExpandedLayout() }

    /** 窗→球：延后 150ms 才实际修改窗口参数。 */
    private val collapseLayoutTask = Runnable { applyBallLayout() }

    /**
     * 屏幕亮灭：息屏或锁屏时用户无法看见球，而系统不一定会为 overlay 派发可见性回调，
     * 因此单独监听这两个系统广播。亮屏后 updateBallAnimation 会重新读取窗口可见性。
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
        // 从应用内点击「悬浮小窗」进入时：首次添加窗口即按小窗尺寸创建，
        // 不先添加球再切换，否则会出现「球闪现后变为小窗」。
        val startsExpanded = pendingExpand
        pendingExpand = false
        startExpandedHandled = startsExpanded
        if (startsExpanded) {
            expanded = true
        }
        try {
            addRoot()
        } catch (t: Throwable) {
            // 权限被收回或系统拒绝加窗：不保留空转服务
            FileLog.log("float", "加悬浮窗失败：" + t)
            isRunning = false
            runCatching { stopSelf() }
            return
        }
        // 息屏或锁屏时停止球的动画（见 screenReceiver）；注册失败不影响球本身，做异常兜底即可
        runCatching {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }
        // 悬浮球服务属于普通后台服务，Android 8 及以上会在应用退至后台后回收（球随之消失）。
        // 窗口添加成功后启动常驻前台服务，使整个进程处于前台服务状态，窗口才能持续显示。
        // 此处只负责启动不负责停止：onDestroy 中不停止该服务，关闭悬浮球不应连带关闭后台运行。
        if (!AgentForegroundService.isRunning) AgentForegroundService.start(this)
        FileLog.log("float", "悬浮窗已挂上，球态 " + ballSizePx + "px")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 「启动即展开」已在 onCreate 中处理；此处仅处理服务已在运行且再次收到展开请求的情况。
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
        // 注册可能未成功（onCreate 中的 runCatching 已捕获失败路径），unregister 会抛出
        // IllegalArgumentException：销毁路径不应因收尾失败而崩溃
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        removeRoot()
        FileLog.log("float", "悬浮窗已移除")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏或分屏后屏幕尺寸变化：重新计算尺寸与边界，避免窗口停留在屏幕外
        readScreen()
        computeExpandedSize()
        if (expanded) {
            // 展开态转屏：先将球心对齐到当前小窗中心，否则 applyExpandedLayout 会按
            // 折叠前的球心重新定位，窗口会跳回上一位置。对齐后仅按新屏幕重新夹取一次。
            syncBallCenterToWindowCenter()
            applyExpandedLayout()
            return
        }
        // 球态转屏：球心保持不变，仅按新屏幕重新夹取一次
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
        // 读取上次的尺寸与球心（首次安装无记录时球心取屏幕中心，见 restoreWindowState）
        restoreWindowState()
        // 直接以小窗形态启动时，先按球心反推窗口左上角（addRoot 中需要使用）
        if (expanded) computeExpandedOrigin()

        // Service 中没有 Activity，ComposeView 在 attach 时会查找 ViewTreeLifecycleOwner，
        // 找不到则抛出 IllegalStateException，因此必须自行挂载一个（通过反射，见 attachLifecycleOwner）。
        val owner = FloatLifecycleOwner()
        owner.start()
        lifecycleOwner = owner

        // 使用普通 ComposeView（ComposeView 1.12 中为 final，无法匿名继承并重写可见性回调）。
        // 可见性通知挂载到 ViewTreeObserver：当系统改变该窗口的可见性（切换至全屏应用、锁屏、下拉通知栏等）时，
        // 不可见则停止球的动画，恢复可见后重新开启；折叠为小窗时由 applyExpandedLayout 关闭。
        // 小窗的深浅色需跟随应用设置：AgentTheme 的 darkTheme 默认值仅依据系统
        // （isSystemInDarkTheme），应用内选择「深色」而系统为浅色时小窗会始终保持浅色。
        // 此处创建视图前读取一次应用设置，切换主题后重新打开小窗即生效。
        val appThemeMode = ThemeStore.load(this)

        val view = ComposeView(this).apply {
            attachLifecycleOwner(this, owner)
            // 不使用池化策略：dispose 仅在 detach 时执行
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = true
            isFocusableInTouchMode = true
            setContent {
                val dark = when (appThemeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                AgentTheme(darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalFloatInputFocus provides { focused -> onInputFocusChanged(focused) }
                    ) {
                        FloatRoot()
                    }
                }
            }
        }
        view.viewTreeObserver.addOnWindowVisibilityChangeListener { updateBallAnimation() }
        // 球的按下与点击由此处统一派发（不移动窗口）；展开后统一交由 Compose 处理
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
        // 键盘始终不由系统推动窗口：位置由本服务按 IME inset 调整
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        // 初始位置：球态使用球心，小窗态使用由球心反推的左上角
        lp.x = if (expanded) expandedLeftX else ballLeftX()
        lp.y = if (expanded) expandedBaseY else ballTopY()
        noMoveAnimation(lp)

        wm.addView(view, lp)
        root = view
        params = lp
        // 再次计算动画开关：addView 过程中可能已派发 onWindowVisibilityChanged，
        // 此时 root 尚未赋值，计算结果必然为 false
        updateBallAnimation()
    }

    private fun removeRoot() {
        val view = root ?: return
        root = null
        params = null
        // 先停止 recomposer，再移除 View（DisposeOnDetachedFromWindow 会随之一并收尾）
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        val wm = windowManager
        windowManager = null
        if (view.isAttachedToWindow) runCatching { wm?.removeView(view) }
    }

    /** 统一调用 updateViewLayout（每次均可能被系统拒绝，统一做兜底）。 */
    private fun updateLayout() {
        val wm = windowManager ?: return
        val view = root ?: return
        val lp = params ?: return
        runCatching { wm.updateViewLayout(view, lp) }
    }

    /**
     * privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION：形变时避免系统附加位移动画，
     * 否则每次 updateViewLayout 都会出现系统自带的移动过渡，与 Compose 过渡冲突。
     * 该字段为 @hide，编译期无法访问，只能运行时通过反射设置；失败时视为未设置（不影响功能）。
     */
    private fun noMoveAnimation(lp: WindowManager.LayoutParams) {
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(lp, field.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    /**
     * 重新计算球的动画开关，写入 Compose 状态。
     *
     * 可见性不单独保存标志位，每次直接读取 view.windowVisibility：
     * 广播与 onWindowVisibilityChanged 的到达顺序无法保证，保存两份最终会不一致。
     */
    private fun updateBallAnimation() {
        ballAnimating = !expanded && screenOn && root?.windowVisibility == View.VISIBLE
    }

    // ---- Compose 内容 ----

    @Composable
    private fun FloatRoot() {
        // 形态切换的视觉过渡全部交由 AnimatedContent：球→窗 400ms（延迟 100ms），窗→球 350ms（延迟 150ms）
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                if (targetState) {
                    // 球→窗：新形态时长 400ms、延迟 100ms（对应窗口参数改为小窗尺寸的时刻）；
                    // 旧形态仅在原尺寸内淡出 100ms，不会延续到窗口放大之后
                    (
                        fadeIn(tween(durationMillis = 400, delayMillis = 100)) +
                            scaleIn(tween(durationMillis = 400, delayMillis = 100), initialScale = 0f)
                        ).togetherWith(fadeOut(tween(durationMillis = 100)))
                } else {
                    // 窗→球：新形态时长 350ms、延迟 150ms（对应窗口缩回球尺寸的时刻）；
                    // 旧形态原地淡出 150ms，窗口缩小时屏幕上已无内容，不会出现被拉伸的画面
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
                    // 会话切换列表：桥接层每次重组均取最新快照，切换后消息列表随之更新
                    sessions = ChatBridge.sessions(),
                    onSend = { text -> ChatBridge.send(text) },
                    // 在会话切换面板中点击某条：切换至该会话（面板自动收起）
                    onPickSession = { id -> ChatBridge.switchSession(id) },
                    // 「回主页」：先将主界面拉至前台（NEW_TASK 复用已有任务，不重建），
                    // 再关闭整套悬浮窗：回到主页后小窗与球同时消失
                    onHome = {
                        openApp()
                        closeEverything()
                    },
                    // 「∨」折叠：窗口原地缩回球态，服务不停止
                    onCollapse = { collapseChat() },
                    // 「✕」关闭：整套关闭（移除窗口并停止服务，球不保留）
                    onClose = { closeEverything() },
                    // 标题栏中间空白区域拖动的位移（px，1:1 跟随手指）→ 服务仅修改 x/y，松手时才持久化
                    onMoveBy = { dx, dy -> moveWindowBy(dx, dy) },
                    // 右边缘竖条 → 仅修改 width；底边横条 → 仅修改 height（锚点均为左上角）
                    onResizeWidth = { dx -> resizeWindowWidth(dx) },
                    onResizeHeight = { dy -> resizeWindowHeight(dy) },
                    // 一次手势结束：服务在此写入一次 prefs（参考实现每帧写盘，此处不采用）
                    onGestureEnd = { onWindowGestureEnd() }
                )
            } else {
                // 球始终绘制在自身 60dp 的盒子内：过渡期间窗口可能仍为小窗尺寸，
                // 若不固定尺寸，Canvas 会按窗口大小绘制出 150dp 的大球
                Box(
                    modifier = Modifier
                        .size(BALL_DP.dp)
                        // 球态拖动必须通过 Compose 实现：ComposeView 上的 setOnTouchListener 在球态
                        // 无法接收事件（SiriBall 的 clickable 先消费了 DOWN），handleTouch 中的拖动路径不会触发，
                        // 因此在此实现实际的手指跟随拖动。detectDragGestures 超过 touchSlop 后才判定为拖动，
                        // 不影响球自身的点击。
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
                    // visible 表示球是否实际可见（球态、屏幕点亮与窗口可见）；不可见时不注册任何动画
                    SiriBall(pressed = pressed, visible = ballAnimating, onClick = { openChat() })
                }
            }
        }
    }

    // ---- 交互：按下缩放与点击展开（球拖动在 FloatRoot 的 pointerInput 中） ----

    /**
     * 球态触摸派发（兜底路径）。
     *
     * 注意：ComposeView 的 setOnTouchListener 在球态无法接收事件：ViewGroup 先将触摸事件
     * 派发给子 View（AndroidComposeView），SiriBall 的 clickable 消费 DOWN 后，父 View 的
     * listener 不会被调用。实际的球拖动在 FloatRoot 的 pointerInput 中
     * （见 moveBallBy / saveBallPosition），此处保留用于：
     * 展开态直接放行，以及个别系统版本将事件派发到父 View 时仍有响应。
     *
     * 球态：可自由拖动（跟随手指移动，松手停留并持久化）；未拖动则为点击，触发展开小窗。
     *       球上没有任何关闭手势，关闭仅通过小窗中的 ✕（见 closeEverything）。
     * 展开态：位置与尺寸由小窗自身手势处理（见 FloatingChatWindow），此处直接 return false。
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        if (expanded) {
            // 展开态：小窗内的滚动、点按与输入框均交由 Compose 处理，此处不拦截任何事件
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
                        // 一旦开始拖动即取消按压缩放状态：跟随移动时球应保持原尺寸
                        pressed = false
                    }
                    if (dragging) {
                        // 跟随手指：以按下时的球心为基准叠加位移并限制在屏幕内（仅移动中心点，左上角由中心计算）
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
                    // 松手后停留原地：球心已是最新值，直接持久化（折叠回球与重启均恢复至此处）
                    dragging = false
                    saveBallPosition()
                } else if (tapped) {
                    // 未拖动即点击球 → 展开小窗
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
     * 首次启动（prefs 无球坐标）时的球心 X：屏幕水平中心。
     *
     * 小窗位置由球心反推，球心居中才能得到居中的小窗。
     */
    private fun defaultBallCenterX(): Int = clampBallCenterX(screenW / 2)

    /** 首次启动时的球心 Y：屏幕竖直中心（与 X 配合即为窗口居中）。 */
    private fun defaultBallCenterY(): Int = clampBallCenterY(screenH / 2)

    // ---- 返回应用 / 整体关闭 ----

    /** 「回主页」：通过 FLAG_ACTIVITY_NEW_TASK 返回 MainActivity，复用已有任务（不重建，聊天记录保留）。 */
    private fun openApp() {
        runCatching {
            // 使用类名而非 MainActivity::class.java：feature 层不应依赖装配层的 Activity。
            // 全工程仅两处此类字符串耦合（此处与 Notifier），修改 MainActivity 的包名或类名时需同时搜索两处。
            startActivity(
                Intent().setClassName(this, com.zmf.agent.core.AppEntry.ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 整套关闭：移除窗口并停止服务，球不保留。「✕」与「回主页」均走此路径。
     * 仅停止自身，不涉及 AgentForegroundService（关闭悬浮窗不应连带关闭后台运行）。
     */
    private fun closeEverything() {
        expanded = false
        inputFocused = false
        handler.removeCallbacks(expandLayoutTask)
        handler.removeCallbacks(collapseLayoutTask)
        // 窗口即将移除：先主动收起键盘，避免 IME 滞留
        hideIme()
        FileLog.log("float", "用户关闭悬浮窗")
        stopSelf()
    }

    // ---- 形态切换（窗口参数延后改） ----

    /** 点击球：立即切换 Compose 形态，窗口参数延后 100ms 修改（先启动动画）。 */
    private fun openChat() {
        if (expanded) return
        expanded = true
        // 小窗位置不读取历史坐标，仅由球心反推。此处先计算一次逻辑值：若用户在 100ms 窗口参数延迟内
        // 立即折叠，折叠路径也不会使用过期坐标。
        computeExpandedOrigin()
        handler.removeCallbacks(collapseLayoutTask)
        handler.postDelayed(expandLayoutTask, EXPAND_LAYOUT_DELAY_MS)
    }

    /**
     * 折叠回球：立即切换 Compose 形态，窗口参数延后 150ms 修改。
     *
     * 球心在切换瞬间对齐到小窗中心（不逐帧计算，也不回到折叠前的位置），并写入一次 prefs，
     * 因此拖动小窗后折叠再重启，球仍停留在该位置。
     */
    private fun collapseChat() {
        if (!expanded) return
        expanded = false
        inputFocused = false
        // 仅计算一次：球心等于当前小窗中心（键盘收起时的位置）
        syncBallCenterToWindowCenter()
        persistBallPosition()
        // 窗口即将恢复为不可获焦，主动收起键盘优于等待系统处理
        hideIme()
        handler.removeCallbacks(expandLayoutTask)
        handler.postDelayed(collapseLayoutTask, COLLAPSE_LAYOUT_DELAY_MS)
    }

    /**
     * 由球心反推小窗左上角并限制在屏幕内：仅计算，不写入窗口参数。
     *
     *     左 = 球心X - 窗口宽/2
     *     上 = 球心Y - 窗口高/2
     *     再限制到 [margin, 屏幕 - 尺寸 - margin]（见 clampWindowLeftInScreen / clampWindowTopInScreen）
     *
     * 展开时计算一次并存入 expandedLeftX / expandedBaseY，折叠时据此反推球心，
     * 因此「折叠后球位于窗口正中」与「展开后窗口以球心为中心」互为逆运算。
     */
    private fun computeExpandedOrigin() {
        expandedLeftX = clampWindowLeftInScreen(ballCenterX - windowWidthPx / 2, windowWidthPx)
        expandedBaseY = clampWindowTopInScreen(ballCenterY - windowHeightPx / 2, windowHeightPx)
    }

    /**
     * 将球心对齐到当前小窗中心。
     *
     * 使用 expandedLeftX、expandedBaseY、windowWidthPx、windowHeightPx 四个逻辑值，
     * 而非直接读取 lp：lp 中可能仍为球尺寸（展开参数延后 100ms 写入），
     * 也可能包含 IME 顶起的临时 y 偏移，直接读取会导致球心计算错误。
     */
    private fun syncBallCenterToWindowCenter() {
        ballCenterX = clampBallCenterX(expandedLeftX + windowWidthPx / 2)
        ballCenterY = clampBallCenterY(expandedBaseY + windowHeightPx / 2)
    }

    /**
     * 切换为小窗尺寸与位置。
     *
     * 位置：不读取历史坐标，统一以球心为中心反推左上角：
     *       winLeft = 球心X - 宽/2，winTop = 球心Y - 高/2，
     *       再将左上角限制到 [margin, 屏幕 - 尺寸 - margin]，限制后整个窗口仍在屏幕内
     *       （窄屏上界可能小于下界，此时位于 margin 处，见 clampWindowLeftInScreen）。
     *       首次启动球心为屏幕中心，因此小窗水平与竖直均居中。
     * 尺寸：使用当前 windowWidthPx / windowHeightPx（默认 240x320dp，拖动后为实际大小）。
     * 此处仍是展开期唯一设置 flags 的位置，之后焦点变化不修改 flags。
     */
    private fun applyExpandedLayout() {
        if (!expanded) return
        val lp = params ?: return
        computeExpandedSize()
        lp.width = windowWidthPx
        lp.height = windowHeightPx
        // 左上角锚点：x/y 即窗口左上角（gravity = TOP|START），resize 仅修改 width/height，不改变 x/y
        computeExpandedOrigin()
        lp.x = expandedLeftX
        // IME 顶起偏移保持不变：仅在逻辑基准 y 上减去，expandedBaseY 始终为键盘收起时的位置
        lp.y = expandedBaseY - imeShift
        // 整个展开期仅设置一次：可获焦且不拦截外部触摸；之后焦点变化不修改 flags
        lp.flags = EXPANDED_FLAGS
        noMoveAnimation(lp)
        updateLayout()
        // 窗口此时已可获焦，直接为窗口请求焦点（仅此一次）。
        // 不通过修改 flags 间接获取焦点：该方式每次都会连带收起 IME，导致键盘闪烁。
        root?.post { runCatching { root?.requestFocus() } }
        // 小窗已展开，球正在淡出：关闭球的无限动画（此后不参与组合）
        updateBallAnimation()
        FileLog.log("float", "展开小窗 " + windowWidthPx + "x" + windowHeightPx + "@" + lp.x + "," + lp.y)
    }

    /** 恢复为球尺寸与位置（球心为折叠时记录的小窗中心，与拖动共用同一套夹取逻辑）。 */
    private fun applyBallLayout() {
        if (expanded) return
        val lp = params ?: return
        imeShift = 0
        lp.width = ballSizePx
        lp.height = ballSizePx
        // 球心已夹取一次，此处再次夹取作为兜底（转屏或折叠过程中屏幕尺寸变化的情况）
        ballCenterX = clampBallCenterX(ballCenterX)
        ballCenterY = clampBallCenterY(ballCenterY)
        lp.x = ballLeftX()
        lp.y = ballTopY()
        // 折叠为球时才恢复「不可获焦 + 越界布局」
        lp.flags = BALL_FLAGS
        noMoveAnimation(lp)
        updateLayout()
        // 球重新绘制：开启动画。此时为重新 remember 的 InfiniteTransition，
        // 时钟从 0 开始，不会停留在展开前的静态画面
        updateBallAnimation()
    }

    // ---- 小窗移动 / 尺寸调整（锚点一律左上角）----

    /**
     * 小窗整体移动：dx/dy 为手指位移的 px（触控区域为标题栏中间空白）。
     *
     * 不存在 scale，因此参考实现的 x += dx * s 在此为 x += dx（1:1 跟随）。
     * 每帧仅修改 LayoutParams，不持久化；位置也不单独保存：折叠时球心记录为窗口中心，
     * 重启后小窗位置由球心反推，拖动过的小窗同样恢复原位置。
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
        // 逻辑基准同步更新（折叠时据此反推球心，转屏时据此保持位置），
        // x 使用与键盘无关的 lp.x；y 记录键盘收起时的基准，避免将 IME 临时上移视为用户位置
        expandedLeftX = lp.x
        expandedBaseY = lp.y + imeShift
        updateLayout()
    }

    /**
     * 右侧手柄：仅修改宽度，锚点固定左上角，不改变 x/y（左边缘固定，右边缘向右扩展）。
     * 每帧限制到 [150dp, 屏宽 − 8dp]；松手后才持久化。
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
            // 已达到上下限：清除余量，反向拖动时无需先抵消累积量
            resizeCarryW = 0f
            return
        }
        windowWidthPx = next
        lp.width = next
        updateLayout()
    }

    /**
     * 底部手柄：仅修改高度，锚点固定左上角，上边缘固定，下边缘向下扩展。
     * 每帧限制到 [200dp, 屏高 − 8dp]；松手后才持久化。
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
     * Compose 侧一次手势（移动、调宽、调高）结束：此时才写入一次 prefs。
     * 参考实现每个 drag 帧均调用 apply() 一次（拖动 1 秒写入 60 次），此处不采用。
     */
    private fun onWindowGestureEnd() {
        // 残量清零：下一次手势从头累积，避免带入上一次的余量
        moveCarryX = 0f
        moveCarryY = 0f
        resizeCarryW = 0f
        resizeCarryH = 0f
        persistWindowState()
    }

    // ---- 球移动 ----

    /**
     * 球拖动：跟随手指移动窗口，仅修改球心（整个球保持在屏幕内），左上角每帧由球心计算。
     * dx/dy 为手指位移 px；拖动过程中不持久化，松手时在 [saveBallPosition] 中写入一次。
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
        // 手势仅修改中心点，左上角每次由中心计算，拖动过程中不持久化
        ballCenterX = clampBallCenterX(ballCenterX + stepX)
        ballCenterY = clampBallCenterY(ballCenterY + stepY)
        lp.x = ballLeftX()
        lp.y = ballTopY()
        updateLayout()
    }

    /** 球松手：球心即新位置，写入一次 prefs（折叠回球与重启均恢复至此处）。 */
    private fun saveBallPosition() {
        if (expanded) return
        moveCarryX = 0f
        moveCarryY = 0f
        persistBallPosition()
    }

    // ---- 持久化（prefs = floating_window）----

    private fun prefs() = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

    /**
     * 保存小窗尺寸：仅在每次手势结束时调用一次。
     *
     * 位置不保存（window_x / window_y 读取路径已移除）：小窗位置始终由球心反推，
     * 球心在折叠时写入 prefs，因此拖动小窗后折叠再重启，位置仍可恢复。
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
     * 保存球的位置：球拖动松手与小窗折叠时各调用一次。
     *
     * 写入的仍为左上角（ball_x / ball_y 的历史语义），因此旧版本保存的坐标升级后含义不变，
     * 读取后加半个球即为球心。
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
     * addRoot 时读取上次的小窗尺寸与球心；读取到的值统一再次夹取。
     *
     * 小窗位置完全不读取：由球心即时反推（applyExpandedLayout）。
     * 球心未保存（首次启动）时取屏幕中心，因此小窗水平与竖直均居中。
     */
    private fun restoreWindowState() {
        val p = prefs()
        // 尺寸：与拖动时使用同一套 min/max（参考实现的「拖动到 150dp、保存后变为 200dp」两套上下限不采用）；
        // 上限为「屏幕 − 8dp」，旧版本按屏幕 0.8 保存的尺寸在此会被新上限夹取一次
        windowWidthPx = p.getInt(KEY_WINDOW_W, windowWidthPx)
            .coerceIn(minWindowWidthPx(), maxWindowWidthPx())
        windowHeightPx = p.getInt(KEY_WINDOW_H, windowHeightPx)
            .coerceIn(minWindowHeightPx(), maxWindowHeightPx())
        // 球位置：保存的是左上角，加半个球还原为球心；未保存时（Int.MIN_VALUE 哨兵）使用默认居中
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
     * 输入框获焦与失焦：仅负责在真正获焦时补一次 IME，不修改窗口 flags。
     *
     * 若在焦点变化时反复切换 NOT_FOCUSABLE 与可获焦，窗口失焦时系统会立即收起键盘，
     * 代码随后又调用 showSoftInput 弹出键盘，会造成「弹出→关闭→再弹出」的循环闪烁。当前规则：
     * 1) 展开态的 flags 在 applyExpandedLayout 中一次性设为可获焦，整个展开期不变；
     * 2) 仅在输入框真正获焦时调用一次 showSoftInput，并通过 200ms 防抖忽略连续重复回调；
     * 3) 失焦时不执行任何操作（不调用 hideSoftInput），键盘收起由系统根据窗口焦点处理。
     */
    private fun onInputFocusChanged(focused: Boolean) {
        if (inputFocused == focused) return
        inputFocused = focused
        // 失焦：不修改 flags、不主动收起键盘（主动收起会打断用户，也是闪烁循环的成因之一）
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
     * IME inset：系统在 SOFT_INPUT_ADJUST_NOTHING 模式下不会移动窗口，位置只能由自身调整。
     * 无法获取时（inset 为 0 或系统不为 overlay 窗口派发 inset）保持原位，不能因获取失败而崩溃。
     */
    private fun bindImeInsets(view: ComposeView) {
        runCatching {
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                runCatching {
                    val bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    // inset 在布局流程中派发，修改窗口参数推迟到下一个消息，避免在布局过程中重排
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
     * 小窗尺寸：默认 240x320dp；已有尺寸（用户拖动过或转屏前计算过）时沿用，
     * 仅按当前屏幕重新夹取一次，转屏后仍保留拖动得到的大小。
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
     * 小窗最大宽度：实际屏宽 − 8dp。
     *
     * 上限为距屏幕边缘固定 8dp（与 MARGIN_DP 同值同义，直接复用 marginPx），以保证宽屏设备可用。
     * 与 clampWindowLeftInScreen 配套：宽度达到该上限时，左上角夹取的上界正好为 marginPx，
     * 窗口左右各留 8dp 并贴合屏幕。下限取最小宽度：极小屏或分屏下「屏宽 − 8dp」可能小于 150dp，
     * 需避免 coerceIn 出现 min > max。
     */
    private fun maxWindowWidthPx(): Int =
        maxOf(minWindowWidthPx(), screenW - marginPx)

    /** 小窗最大高度：实际屏高 − 8dp（下限取最小高度，理由同上，避免 min > max）。 */
    private fun maxWindowHeightPx(): Int =
        maxOf(minWindowHeightPx(), screenH - marginPx)

    /** 球的左上角（布局时写入 lp 的值）：由球心减去半个球，持久化同样使用这一对值。 */
    private fun ballLeftX(): Int = ballCenterX - ballSizePx / 2

    private fun ballTopY(): Int = ballCenterY - ballSizePx / 2

    /** 球心夹取：整个球保持在屏幕内（球尺寸较小，无需使用「保留 2/3」规则）。 */
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
     * 小窗左上角横向夹取：x ∈ [margin, screenW - w - margin]，使整个窗口保持在屏幕内。
     *
     * 窄屏（窗口宽度大于屏幕）时上界小于下界，此处用 maxOf 提升至下界，
     * 结果为贴 margin 且右侧超出屏幕，避免产生负坐标或反向区间。
     */
    private fun clampWindowLeftInScreen(x: Int, w: Int): Int =
        x.coerceIn(marginPx, maxOf(marginPx, screenW - w - marginPx))

    /** 小窗左上角纵向夹取：y ∈ [margin, screenH - h - margin]，含义同上。 */
    private fun clampWindowTopInScreen(y: Int, h: Int): Int =
        y.coerceIn(marginPx, maxOf(marginPx, screenH - h - marginPx))
}

/**
 * 将 LifecycleOwner 与 SavedStateRegistryOwner 挂载到 View 树上。
 *
 * 二者在本工程编译期无法解析（androidx.lifecycle.ViewTreeLifecycleOwner 在运行期类路径中存在，
 * Kotlin 编译器却无法访问），因此通过反射调用；反射失败不抛出，由上层兜底
 * （加窗失败会调用 stopSelf，不保留空转服务）。
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
 * Service 中供 ComposeView 使用的最小 LifecycleOwner 与 SavedStateRegistryOwner。
 *
 * 缺少 LifecycleOwner 时，ComposeView 在 attach 时会抛出
 * 「Composed into the View which doesn't propagate ViewTreeLifecycleOwner」；
 * 缺少 SavedStateRegistryOwner 时，使用 rememberSaveable 的位置无法获取注册表。
 */
private class FloatLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** 挂载时推进至 RESUMED：常驻动画需要处于可运行状态。 */
    fun start() {
        runCatching {
            savedStateController.performAttach()
            savedStateController.performRestore(null as Bundle?)
        }
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** 移除窗口前推进至 DESTROYED：recomposer 停止，ComposeView 自行 dispose。 */
    fun destroy() {
        runCatching { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
