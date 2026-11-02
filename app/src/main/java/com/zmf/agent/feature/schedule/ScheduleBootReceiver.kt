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
 * AlarmManager 的闹钟不跨重启，应用更新时也会被系统清掉；没有这条路径，
 * 用户设好的任务会在重启或更新后静默停摆，直到下次手动打开 App。
 *
 * 这里只重排，不启动前台服务、不执行任务：真正的执行仍由闹钟到点后的 [ScheduleReceiver] 触发。
 * 国产 ROM 的「自启动」开关会在系统层拦截开机广播，代码查不到该状态，只能在界面上提示用户。
 */
class ScheduleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        // 重排要读任务表并逐条写闹钟：交给后台线程，用 goAsync 撑住广播的生命周期
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
