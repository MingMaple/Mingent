package com.zmf.agent.feature.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zmf.agent.core.log.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 系统事件后的闹钟重排：开机完成与应用覆盖更新。
 *
 * AlarmManager 的闹钟不会跨重启保留，应用更新时也会被系统清除；缺少这条路径时，
 * 已设置的任务会在重启或更新后停止触发，直到用户下次手动打开应用。
 *
 * 此处只重排闹钟，不启动前台服务、不执行任务；实际执行仍由闹钟到点后的 [ScheduleReceiver] 触发。
 * 部分国产 ROM 的自启动开关会在系统层拦截开机广播；该状态无法通过代码查询，只能在界面上提示用户。
 */
class ScheduleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        // 重排需读取任务表并逐条写入闹钟：交由后台线程执行，并通过 goAsync 延长广播的生命周期
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FileLog.log(TAG, "收到 " + action + "，开始重排定时任务")
                ScheduleRunner.rescheduleAll(app)
            } catch (t: Throwable) {
                FileLog.log(TAG, "系统事件后重排失败：" + t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "schedule"
    }
}
