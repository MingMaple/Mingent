package com.zmf.agent.core.prefs

import android.content.Context
import com.zmf.agent.core.model.BusySendMode

/**
 * 通用设置里两个新开关的落盘（2026-10-30 从 feature/settings 上移 core：
 * 发送流程的繁忙策略与设置页读的是同一份，属于数据容器，不是页面私有）。
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
