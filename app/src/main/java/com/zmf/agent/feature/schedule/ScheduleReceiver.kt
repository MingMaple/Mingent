package com.zmf.agent.feature.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 闹钟到点后的入口。
 *
 * 仅负责转发：广播的 onReceive 执行预算只有数秒，而一轮 Agent 可能持续数分钟，
 * 实际工作交给 [ScheduleRunner]（由它启动常驻前台服务保持进程存活，再开启应用级协程）。
 *
 * 在 Manifest 中以 exported=false 注册，并使用自定义 action，其他应用无法发送该广播，也不会与系统广播冲突。
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (taskId.isEmpty()) return
        ScheduleRunner.fire(context, taskId)
    }

    companion object {
        /** 自定义 action：与系统闹钟广播区分，仅本应用的 PendingIntent 会携带。 */
        const val ACTION_FIRE = "com.zmf.agent.feature.schedule.FIRE"

        /** 待执行任务的 id。 */
        const val EXTRA_TASK_ID = "task_id"
    }
}
