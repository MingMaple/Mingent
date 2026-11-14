package com.zmf.agent.core.prefs

import android.content.Context
import com.zmf.agent.core.model.BusySendMode

/**
 * 通用设置中部分开关的持久化。
 *
 * 发送流程的繁忙策略与设置页使用同一份数据，属于数据容器而非页面私有。
 *
 * 与 ThemeStore / FontSizeStore 共用 "app_prefs" 文件：备份导出的是整个 app_prefs.xml，
 * 独立文件会增加备份逻辑的登记项。
 */
object GeneralPrefs {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_TRACE_VIEW = "trace_view"
    private const val KEY_BUSY_SEND = "busy_send"

    /** 显示工作代码视图：默认关闭，工作区尚未配置时开启会展示满屏代码块。 */
    fun loadTraceView(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRACE_VIEW, false)

    /** 写入「显示工作代码视图」开关：apply() 异步落盘，不阻塞调用线程。 */
    fun saveTraceView(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRACE_VIEW, enabled).apply()
    }

    /** 读取繁忙时的发送策略；未设置过时回落 [BusySendMode.QUEUE]。 */
    fun loadBusyMode(context: Context): BusySendMode =
        BusySendMode.fromName(prefs(context).getString(KEY_BUSY_SEND, null))

    /** 写入繁忙发送策略：存储枚举名而非界面文案，修改文案不影响旧值读取。 */
    fun saveBusyMode(context: Context, mode: BusySendMode) {
        prefs(context).edit().putString(KEY_BUSY_SEND, mode.name).apply()
    }

    /** 取枚举自带的显示文案，界面与日志共用这一份。 */
    fun busyModeLabel(mode: BusySendMode): String = mode.label

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
