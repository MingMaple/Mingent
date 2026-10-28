package com.zmf.agent.ui.theme

import android.content.Context
import kotlin.math.abs

/**
 * 界面字号档位（只放大 sp，不动 dp）。
 *
 * 只动字体是有意的：图标、间距、按钮高度跟着一起放会把「特大」变成整屏撑爆，
 * 用户要的是「字看得清」，不是「所有东西都变大」。
 *
 * 档位制而不是滑杆：字号不是连续量，多一档不如少一档好记，也避免了 1.037 这种
 * 存进 prefs、下次显示时跟档位名对不上的尴尬。
 */
object UiScaleStore {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_SCALE = "ui_scale"

    /** 可选档位，顺序即由小到大。 */
    val STEPS = listOf(0.85f, 1.0f, 1.15f, 1.3f)

    /**
     * 读档位。
     *
     * 存进来的值不落在 STEPS 上（旧版本写过别的比例 / 备份是从别处还原的 / 有人手改了 xml）
     * 就回落 1f：宁可回到标准字号，也不要让 label() 显示一个跟实际比例对不上的名字。
     */
    fun load(context: Context): Float {
        val raw = prefs(context).getFloat(KEY_SCALE, 1f)
        // float 在 xml 里是十进制字符串往返，理论上精确；留一点容差只为不依赖这一点。
        return STEPS.firstOrNull { abs(it - raw) < 0.001f } ?: 1f
    }

    /** 保存档位；不做校验，非 [STEPS] 内的值会在下次 [load] 时回落成 1f。 */
    fun save(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_SCALE, v).apply()
    }

    /** 档位 → 界面上的中文名；不在档位上（旧值 / 手改过 xml）时返回「标准」。 */
    fun label(v: Float): String = when {
        abs(v - 0.85f) < 0.001f -> "小"
        abs(v - 1.15f) < 0.001f -> "大"
        abs(v - 1.3f) < 0.001f -> "特大"
        else -> "标准"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
