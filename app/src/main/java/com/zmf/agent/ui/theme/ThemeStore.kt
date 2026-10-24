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
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

object ThemeStore {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_MODE = "theme_mode"

    fun load(context: Context): ThemeMode =
        ThemeMode.fromName(prefs(context).getString(KEY_MODE, null))

    fun save(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
