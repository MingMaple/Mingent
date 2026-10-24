package com.zmf.agent.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zmf.agent.MainActivity
import com.zmf.agent.core.log.FileLog

/**
 * 结果通知：回合跑完 / 子代理收工 / 定时任务触发时弹一条系统通知，点回 App 继续看。
 *
 * 三类通知走同一个 [post]，差别只是通知 id 与文案：
 * 同类新结果会顶掉旧的（固定 id），不同类可以并存，避免长跑任务把通知栏刷成一片。
 * 是否该发（比如用户正在前台看这个会话）由调用方判断——这里只管「安静地发出去」。
 */
object Notifier {

    private const val TAG = "notify"

    private const val CHANNEL_ID = "agent_result_high"
    private const val CHANNEL_NAME = "任务结果"
    private const val CHANNEL_DESC = "AI 回合结束、子代理完成、定时任务触发的提醒"

    /**
     * 旧的 IMPORTANCE_DEFAULT 渠道。
     *
     * 渠道的 importance 建成即固定，代码改不了系统里已有的那条——想从「进通知栏」
     * 抬到「弹横幅」只能换一个新的渠道 id。老渠道顺手删掉，免得设置里留两条同名的。
     */
    private const val LEGACY_CHANNEL_ID = "agent_result"

    /** 点通知回 App 时带的会话 id；MainActivity 侧读这个 key 决定切到哪个会话。 */
    const val EXTRA_SESSION_ID = "session_id"

    // 固定 id：同一类通知只留最新一条，不同类互不覆盖（1001 已被前台服务的常驻通知占用）
    private const val ID_REPLY = 2001
    private const val ID_SUBAGENT = 2002
    private const val ID_SCHEDULE = 2003

    /** 折叠态正文最多这么多字（通知栏一行放不下更多），完整内容走 BigText。 */
    private const val PREVIEW_CHARS = 120

    /** 渠道建过一次就够了；用户手动删掉渠道后建不回来，这是系统行为，不做补救。 */
    @Volatile
    private var channelReady = false

    /** AI 回复完成。 */
    fun notifyReply(context: Context, title: String, text: String, sessionId: String = "") {
        post(context, ID_REPLY, title, text, sessionId)
    }

    /** 子代理（后台任务）完成。 */
    fun notifySubagent(context: Context, title: String, text: String, sessionId: String = "") {
        post(context, ID_SUBAGENT, title, text, sessionId)
    }

    /** 定时 / 计划任务触发。 */
    fun notifySchedule(context: Context, title: String, text: String, sessionId: String = "") {
        post(context, ID_SCHEDULE, title, text, sessionId)
    }

    /**
     * 三个入口的公共实现。
     *
     * 整段 runCatching：通知失败（权限被撤回、厂商魔改）绝不能把 Agent 循环带崩——
     * 通知只是顺带的提醒，丢了不影响回合结果。
     */
    private fun post(context: Context, id: Int, title: String, text: String, sessionId: String) {
        val app = context.applicationContext
        if (!canNotify(app, title)) return
        runCatching {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            ensureChannel(manager)
            val body = text.trim()
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(oneLine(body))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent(app, id, sessionId))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            manager.notify(id, notification)
            FileLog.log(TAG, "通知[" + id + "] " + title)
        }.onFailure { FileLog.log(TAG, "通知失败：" + it) }
    }

    /**
     * Android 13+ 没授 POST_NOTIFICATIONS 时系统会静默丢弃，这里先自己判断并跳过：
     * 少建一个渠道、少打一条「发了但没人看见」的日志。
     * 13 以下没有运行时权限，声明即生效。
     */
    private fun canNotify(context: Context, title: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) FileLog.log(TAG, "未授通知权限，跳过：" + title)
        return granted
    }

    /**
     * 渠道只建一次。
     *
     * IMPORTANCE_HIGH 是「能弹横幅」的前提（DEFAULT 只会安静进通知栏，也不会响）；
     * 声音与振动都留给新渠道，用户嫌吵可以在系统设置里单独改这一条，不影响别的功能。
     */
    @Synchronized
    private fun ensureChannel(manager: NotificationManager) {
        if (channelReady) return
        // minSdk 26：渠道 API 直接可用，不用判版本（同 AgentForegroundService）
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180)
        }
        manager.createNotificationChannel(channel)
        // 老渠道没有意义了（代码不再往它发），删掉省得设置里出现两条同名的「任务结果」
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        channelReady = true
    }

    /**
     * 点通知回 App。
     *
     * NEW_TASK 复用已有任务（聊天记录不重建）；CLEAR_TOP + SINGLE_TOP 让已经在前台的
     * MainActivity 走 onNewIntent，而不是在栈上再叠一个实例。
     * requestCode 用通知 id：三种通知各自的 sessionId 才不会被 FLAG_UPDATE_CURRENT 互相覆盖。
     */
    private fun contentIntent(context: Context, id: Int, sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 折叠态正文：压成一行再截前 [PREVIEW_CHARS] 字。
     * 多行文本在通知栏只会露出第一行，换行白占地方；超长截断加省略号，完整内容交给 BigText。
     */
    private fun oneLine(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS) + "…"
    }
}
