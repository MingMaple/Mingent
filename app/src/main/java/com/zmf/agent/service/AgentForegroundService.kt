package com.zmf.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zmf.agent.MainActivity

/**
 * 常驻前台服务：挂一条低调的常驻通知，把进程钉在「前台服务」状态，
 * 这样 App 退到后台也不容易被系统回收，Agent 正在跑的回合能继续跑完。
 *
 * 只有用户在设置里打开「后台运行」才会起来；onDestroy 不主动 stopSelf
 * （START_STICKY，被系统杀掉时由系统按需重建）。用完只在用户关开关时 stop。
 */
class AgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "agent_alive"
        private const val CHANNEL_NAME = "后台运行"
        private const val NOTIFICATION_ID = 1001

        /** 服务是否在跑（设置页「后台运行」那一行读这个）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 拉起前台服务；系统拒绝前台启动时退回普通 startService，不向上抛。 */
        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // 少数机型/系统策略下前台上限会拒；退回普通 startService 至少别崩
                runCatching { context.startService(intent) }
            }
        }

        /** 停止前台服务（用户关掉「后台运行」时调用）；失败静默。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // minSdk 26，通知渠道 API 直接可用，不用判版本
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
        // 这里不 stopSelf：用户关开关走的是 stopService，系统回收走 START_STICKY 重建
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

    /** 点通知回 App：NEW_TASK 复用已有任务，不会重建 MainActivity（聊天记录还在）。 */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
