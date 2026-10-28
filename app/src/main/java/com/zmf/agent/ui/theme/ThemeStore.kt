package com.zmf.agent.ui.theme

import android.content.Context

/** 深浅色模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    /** 侧边栏右上角小按钮上的字形。 */
    val glyph: String
        get() = when (this) {
            SYSTEM -> "◐"
            LIGHT -> "☀"
            DARK -> "☾"
        }

    companion object {
        /** 按枚举名还原模式；名称缺失或无法识别时回落到跟随系统。 */
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** 深浅色模式的持久化入口：读写 `app_prefs` 中的 `theme_mode`。 */
object ThemeStore {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_MODE = "theme_mode"

    /** 读取已保存的模式；未保存过时返回 [ThemeMode.SYSTEM]。 */
    fun load(context: Context): ThemeMode =
        ThemeMode.fromName(prefs(context).getString(KEY_MODE, null))

    /**
     * 保存模式：写进 `app_prefs` 的 `theme_mode`，用 apply 异步落盘，不阻塞调用线程。
     * 不广播：调用方拿到新值后自己重组（`AgentTheme(darkTheme = ...)` 的入参才是渲染依据）。
     */
    fun save(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
