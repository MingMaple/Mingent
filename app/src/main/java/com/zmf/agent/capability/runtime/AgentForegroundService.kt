package com.zmf.agent.capability.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 常驻前台服务：显示一条低优先级的常驻通知，使进程处于「前台服务」状态，
 * 从而在 App 退至后台时不易被系统回收，Agent 正在执行的回合得以继续完成。
 *
 * 仅在用户于设置中开启「后台运行」时启动；onDestroy 不主动调用 stopSelf
 * （START_STICKY，被系统回收时由系统按需重建）。仅在用户关闭开关时调用 stop。
 */
class AgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "agent_alive"
        private const val CHANNEL_NAME = "后台运行"
        private const val NOTIFICATION_ID = 1001

        /** 服务是否正在运行（设置页「后台运行」一行读取该值）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 启动前台服务；系统拒绝前台启动时退回普通 startService，不向上抛出异常。 */
        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // 少数机型或系统策略会拒绝前台启动；退回普通 startService 以避免崩溃
                runCatching { context.startService(intent) }
            }
        }

        /** 停止前台服务（用户关闭「后台运行」时调用）；失败时静默处理。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // minSdk 26，通知渠道 API 可直接使用，无需判断版本
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mingent 在后台运行时保持常驻"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        // START_STICKY 要求 onStartCommand 里保证进入前台状态
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        // 此处不调用 stopSelf：用户关闭开关时由 stopService 停止，系统回收时由 START_STICKY 重建
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Mingent 正在后台运行")
        .setContentText("点此返回")
        .setContentIntent(contentIntent())
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()

    /** 点击通知返回 App：NEW_TASK 复用已有任务，不会重建 MainActivity（聊天记录得以保留）。 */
    private fun contentIntent(): PendingIntent {
        val intent = Intent().setClassName(this, com.zmf.agent.core.AppEntry.ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
