package com.zmf.agent.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 闹钟到点的落点。
 *
 * 只做转发：广播的 onReceive 只有几秒预算，而一轮 Agent 可能跑几分钟，
 * 真正的工作交给 [ScheduleRunner]（它会先拉常驻前台服务钉住进程，再开应用级协程）。
 *
 * 在 Manifest 里 exported=false 注册，action 又是自定义串：别的 App 既发不进来、也不用担心系统广播撞上。
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (taskId.isEmpty()) return
        ScheduleRunner.fire(context, taskId)
    }

    companion object {
        /** 自定义 action：和系统闹钟广播分开，只有本 App 的 PendingIntent 会带它。 */
        const val ACTION_FIRE = "com.zmf.agent.schedule.FIRE"

        /** 要跑的任务 id。 */
        const val EXTRA_TASK_ID = "task_id"
    }
}
