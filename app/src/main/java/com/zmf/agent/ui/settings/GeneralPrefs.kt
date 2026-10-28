package com.zmf.agent.ui.settings

import android.content.Context

/**
 * 繁忙时（AI 正在跑这一轮）用户又发了消息，这条消息怎么处理。
 *
 * 只留两种：排队等本轮结束，或者插进正在跑的那一轮。名字写在枚举上，
 * 界面、落盘、日志共用同一份文案，避免三处各写一个中文词。
 */
enum class BusySendMode(val label: String) {
    QUEUE("排队发送"),
    STEER("插话发送");

    companion object {
        /** 存的是枚举名；xml 被改坏或来自旧备份时回落 QUEUE。 */
        fun fromName(name: String?): BusySendMode = entries.firstOrNull { it.name == name } ?: QUEUE
    }
}

/**
 * 通用设置里两个新开关的落盘。
 *
 * 和 ThemeStore / UiScaleStore 共用 "app_prefs" 这一个文件：备份导出的是整个
 * app_prefs.xml，多开一个文件反而要在备份逻辑里多登记一处。
 */
object GeneralPrefs {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_TRACE_VIEW = "trace_view"
    private const val KEY_BUSY_SEND = "busy_send"

    /** 显示工作代码视图：默认关，工作区还没配好时开着一屏代码块太吵。 */
    fun loadTraceView(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRACE_VIEW, false)

    /** 写入「显示工作代码视图」开关：apply() 异步落盘，不阻塞调用线程。 */
    fun saveTraceView(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRACE_VIEW, enabled).apply()
    }

    /** 读取繁忙时的发送策略；未设置过时回落 [BusySendMode.QUEUE]。 */
    fun loadBusyMode(context: Context): BusySendMode =
        BusySendMode.fromName(prefs(context).getString(KEY_BUSY_SEND, null))

    /** 写入繁忙发送策略：存的是枚举名，不是界面文案，改文案不会读坏旧值。 */
    fun saveBusyMode(context: Context, mode: BusySendMode) {
        prefs(context).edit().putString(KEY_BUSY_SEND, mode.name).apply()
    }

    /** 取枚举自带的显示文案，界面与日志共用这一份。 */
    fun busyModeLabel(mode: BusySendMode): String = mode.label

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
