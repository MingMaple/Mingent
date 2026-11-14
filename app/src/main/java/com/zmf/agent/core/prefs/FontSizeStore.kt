package com.zmf.agent.core.prefs

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 全局界面基准字号，单位为 sp。
 *
 * 只调整文字尺寸，不改变任何 dp 尺寸：图标、卡片高度、边距与圆角在任何字号下保持不变。
 * 持久层只存绝对基准字号，界面倍率由主题入口一次性换算 `baseFontSp / [DESIGN_BASE_SP]`。
 *
 * 区间 12–16sp、步长 1sp：12/14 ≈ 0.857、16/14 ≈ 1.143，接近旧版「小」「大」两档，旧「特大」档并入 16sp。
 * 常量集中在本文件，后续调整只改这一处。
 *
 * 旧键 `ui_scale` 只读保留：新键 `base_font_sp` 不存在时按 `floor(14 × 旧值)` 迁移并夹取到区间，
 * 之后不再读取旧键；旧键保持原值，供降级回滚。不沿用旧键的原因：1.3 既是合法旧倍率、
 * 又是会被夹成下限的合法 sp，同一个键无法区分两种语义。
 */
object FontSizeStore {

    /** 设计基准字号：全仓正文为 14sp，倍率 1.0 即此值。 */
    const val DESIGN_BASE_SP = 14f

    /** 可调下限（sp）。 */
    const val MIN_SP = 12

    /** 可调上限（sp）。 */
    const val MAX_SP = 16

    /** 单次微调步长（sp）。 */
    const val STEP_SP = 1

    /** 默认基准字号（sp），与设计基准一致。 */
    const val DEFAULT_SP = 14

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_FONT_SP = "base_font_sp"
    private const val KEY_LEGACY_SCALE = "ui_scale"

    /** 进程级观察者；用普通回调而非 Compose，`core` 层不引入 Compose。 */
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()

    /** 进程内当前基准字号；由 [load] 与 [save] 更新，供没有主题参数的组件（浮窗）读取。 */
    @Volatile
    var current: Int = DEFAULT_SP
        private set

    /**
     * 读取基准字号。
     *
     * 优先读新键并取整、夹取到区间；新键不存在时按旧键一次性迁移；两者都不存在或值非法时取默认值。
     * 读取不写入磁盘，首次保存时才落新键。
     */
    fun load(context: Context): Int {
        val prefs = prefs(context)
        val value = when {
            prefs.contains(KEY_FONT_SP) -> normalize(prefs.getFloat(KEY_FONT_SP, DEFAULT_SP.toFloat()))
            prefs.contains(KEY_LEGACY_SCALE) ->
                migrate(prefs.getFloat(KEY_LEGACY_SCALE, Float.NaN))

            else -> DEFAULT_SP
        }
        current = value
        return value
    }

    /**
     * 归一化新键的原始浮点值：非有限值回落默认，其余取整后夹取到区间。
     *
     * 取整是为了避免 `14.999` 这类浮点噪声进入显示与比较。
     */
    fun normalize(raw: Float): Int =
        if (!raw.isFinite()) DEFAULT_SP else raw.roundToInt().coerceIn(MIN_SP, MAX_SP)

    /**
     * 把旧档位倍率迁移为基准字号：`floor(14 × 旧值)` 后夹取到区间。
     *
     * 向下取整后旧「小」档因下限会略升到 12sp；
     * 旧值被手工改成非四档时按同一公式处理，不做特殊分支。
     */
    fun migrate(legacyScale: Float): Int =
        if (!legacyScale.isFinite()) DEFAULT_SP
        else floor(DESIGN_BASE_SP * legacyScale).toInt().coerceIn(MIN_SP, MAX_SP)

    /**
     * 保存基准字号：先夹取到区间，再写入磁盘并通知观察者。
     *
     * 使用 `apply()`：内存立即可见、磁盘异步写入，符合连续点击微调的场景。
     */
    fun save(context: Context, sp: Int) {
        val value = sp.coerceIn(MIN_SP, MAX_SP)
        prefs(context).edit().putFloat(KEY_FONT_SP, value.toFloat()).apply()
        current = value
        listeners.forEach { runCatching { it(value) } }
    }

    /** 订阅基准字号变化；同一回调重复注册只保留一份。 */
    fun addListener(listener: (Int) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    /** 取消订阅；组件销毁时必须调用，避免服务持有失效状态。 */
    fun removeListener(listener: (Int) -> Unit) {
        listeners.remove(listener)
    }

    /** 把基准字号换算为 Compose 的 fontScale；默认取进程内当前值。 */
    fun fontScaleOf(sp: Int = current): Float = sp / DESIGN_BASE_SP

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
