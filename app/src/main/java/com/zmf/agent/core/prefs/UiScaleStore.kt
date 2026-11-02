package com.zmf.agent.core.prefs

import android.content.Context
import kotlin.math.abs

/**
 * 界面字号档位，只放大 sp，不改变 dp。
 *
 * 仅调整字体尺寸是有意设计：若图标、间距与按钮高度一并放大，最高档位会使整屏元素溢出；
 * 用户需要的是文字可辨识，而非全部元素放大。
 *
 * 采用档位而非滑块：字号并非连续量，档位数量更少、更易记忆，也避免 1.037 这类数值
 * 存入 prefs 后与档位名称不一致。
 */
object UiScaleStore {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_SCALE = "ui_scale"

    /** 可选档位，顺序即由小到大。 */
    val STEPS = listOf(0.85f, 1.0f, 1.15f, 1.3f)

    /**
     * 读取档位。
     *
     * 存储值不在 [STEPS] 中时回落至 1f，包括旧版本写入其他比例、备份来自其他环境或 xml 被手动修改；
     * 宁可回到标准字号，也不使 [label] 显示与实际比例不符的名称。
     */
    fun load(context: Context): Float {
        val raw = prefs(context).getFloat(KEY_SCALE, 1f)
        // float 在 xml 中以十进制字符串往返，理论上精确；保留少量容差，避免依赖该特性。
        return STEPS.firstOrNull { abs(it - raw) < 0.001f } ?: 1f
    }

    /** 保存档位；不做校验，非 [STEPS] 内的值会在下次 [load] 时回落成 1f。 */
    fun save(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_SCALE, v).apply()
    }

    /** 档位对应的中文名；不在档位上时返回「标准」，包括旧值与 xml 被手动修改的情况。 */
    fun label(v: Float): String = when {
        abs(v - 0.85f) < 0.001f -> "小"
        abs(v - 1.15f) < 0.001f -> "大"
        abs(v - 1.3f) < 0.001f -> "特大"
        else -> "标准"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
