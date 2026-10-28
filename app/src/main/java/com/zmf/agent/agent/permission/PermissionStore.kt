package com.zmf.agent.agent.permission

import android.content.Context
import com.zmf.agent.core.model.PermissionLevel

/** 权限三档的持久化。默认「工作区」：能读写工作区，不碰系统别处。 */
object PermissionStore {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LEVEL = "permission_level"

    /** 读取权限档；未设置或取值已不认识时返回 [PermissionLevel.WORKSPACE]。 */
    fun load(context: Context): PermissionLevel {
        val raw = prefs(context).getString(KEY_LEVEL, null) ?: return PermissionLevel.WORKSPACE
        return PermissionLevel.entries.firstOrNull { it.name == raw } ?: PermissionLevel.WORKSPACE
    }

    /** 保存权限档，按枚举名持久化。 */
    fun save(context: Context, level: PermissionLevel) {
        prefs(context).edit().putString(KEY_LEVEL, level.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
