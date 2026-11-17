package com.zmf.agent.core.prefs

import android.content.Context

/**
 * 分享目标的短期记忆：上一次分享落到的会话与时刻。
 *
 * 规则：距上次分享不超过 [WINDOW_MS]（1 小时）时，新的分享回到同一个会话；
 * 超出窗口、会话已删除或用户改选目标时，按新的目标处理。用户随时可在分享输入框或侧边栏改选。
 *
 * 与 GeneralPrefs 共用 `app_prefs`，随备份整体导出，不增加新的备份登记项。
 */
object ShareTargetPrefs {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_SESSION = "share_target_session"
    private const val KEY_AT = "share_target_at"

    /** 再次分享回到上次会话的时间窗：1 小时。 */
    const val WINDOW_MS = 60L * 60L * 1000L

    /** 记住本次分享的会话与时刻，供下一次分享判断是否回到它。 */
    fun remember(context: Context, sessionId: String, at: Long = System.currentTimeMillis()) {
        if (sessionId.isEmpty()) return
        prefs(context).edit().putString(KEY_SESSION, sessionId).putLong(KEY_AT, at).apply()
    }

    /** 窗口内上一次分享的会话 id；没有记录、记录损坏或已超时都返回 null。 */
    fun recentSession(context: Context, now: Long = System.currentTimeMillis()): String? {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_SESSION, null).orEmpty()
        val at = prefs.getLong(KEY_AT, 0L)
        if (id.isEmpty() || at <= 0L) return null
        if (now - at > WINDOW_MS) return null
        return id
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
