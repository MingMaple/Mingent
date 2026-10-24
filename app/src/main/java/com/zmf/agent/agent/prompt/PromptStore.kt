package com.zmf.agent.agent.prompt

import android.content.Context

/**
 * 系统提示词相关的用户设置（和权限档、API 配置共用 app_prefs）。
 *
 * 三个键各管一件事：
 * - prompt_extra：附加指令，拼在默认提示词后面；
 * - prompt_system_override：整份覆盖，非空时默认提示词一条都不出；
 * - prompt_skills_enabled：是否把 Skill 目录写进提示词。
 */
object PromptStore {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_EXTRA = "prompt_extra"
    private const val KEY_OVERRIDE = "prompt_system_override"
    private const val KEY_SKILLS_ENABLED = "prompt_skills_enabled"

    /** 用户附加指令；没设置过就是空串。 */
    fun loadExtra(context: Context): String =
        prefs(context).getString(KEY_EXTRA, "").orEmpty()

    fun saveExtra(context: Context, text: String) {
        prefs(context).edit().putString(KEY_EXTRA, text).apply()
    }

    /** 整份覆盖；null / 空白都表示「不覆盖」，走默认提示词。 */
    fun loadOverride(context: Context): String? =
        prefs(context).getString(KEY_OVERRIDE, null)?.takeIf { it.isNotBlank() }

    fun saveOverride(context: Context, text: String?) {
        val editor = prefs(context).edit()
        if (text.isNullOrBlank()) editor.remove(KEY_OVERRIDE) else editor.putString(KEY_OVERRIDE, text)
        editor.apply()
    }

    /** Skill 目录开关，默认打开（没有 Skill 时这段本来也不会出）。 */
    fun loadSkillsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SKILLS_ENABLED, true)

    fun saveSkillsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SKILLS_ENABLED, enabled).apply()
    }

    /** 三个键一起清掉，回到「默认提示词 + 空附加指令 + Skill 开」。 */
    fun resetAll(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_EXTRA)
            .remove(KEY_OVERRIDE)
            .remove(KEY_SKILLS_ENABLED)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
