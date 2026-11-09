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
import com.zmf.agent.core.log.FileLog

/**
 * 结果通知：回合结束、定时任务触发时发送系统通知，点击可回到 App 继续查看。
 *
 * 两类通知共用 [post]，仅通知 id 与文案不同：同类新结果覆盖旧通知（固定 id），
 * 不同类可并存，避免长任务占满通知栏。是否发送（如用户正在前台查看该会话）由调用方判断，
 * 此处仅负责发送。子智能体不向用户发通知：它归属于父会话，结果只回给父会话与面板。
 */
object Notifier {

    private const val TAG = "notify"

    private const val CHANNEL_ID = "agent_result_high"
    private const val CHANNEL_NAME = "任务结果"
    private const val CHANNEL_DESC = "AI 回合结束、定时任务触发的提醒"

    /**
     * 历史 IMPORTANCE_DEFAULT 渠道。
     *
     * 渠道的 importance 创建后即固定，无法修改系统中已有的渠道：从「进入通知栏」提升到
     * 「弹出横幅」必须使用新的渠道 id。该渠道已删除，避免设置中出现两条同名的「任务结果」。
     */
    private const val LEGACY_CHANNEL_ID = "agent_result"

    /**
     * 点通知回 App 时带的一次性令牌；MainActivity 用它向本进程换取会话 id。
     *
     * 不直接传会话 id：MainActivity 是 `exported=true` 的启动入口，若只认 id，任何本机应用
     * 都能显式启动它并指定任意会话，形成「参数未校验的被动导航」。令牌由本进程随机生成、
     * 登记在私有偏好里，外部应用既猜不到也无法预先登记，且兑换一次即作废。
     * 缺失、伪造或已用过的令牌一律按「没有目标会话」处理。
     */
    const val EXTRA_SESSION_TOKEN = "session_token"

    /** 令牌偏好文件名（应用私有沙箱）与同时保留的上限。 */
    private const val TOKEN_PREFS = "notify_tokens"
    private const val TOKEN_MAX = 16

    /**
     * 回到 App 的目标 Activity 类名。
     *
     * 使用字符串而非 `MainActivity::class.java`：core 为底层数据与工具层，
     * 不应反向依赖壳层的 Activity。代价是类名或包名变更时必须同步此处；
     * 该字符串为全工程唯一的耦合点。
     */
    private const val LAUNCH_ACTIVITY = com.zmf.agent.core.AppEntry.ACTIVITY

    // 固定 id：同一类通知只留最新一条，不同类互不覆盖（1001 已被前台服务的常驻通知占用）
    // 2002 曾用于子智能体通知，已停用且不复用，避免旧通知被误认成新类别
    private const val ID_REPLY = 2001
    private const val ID_SCHEDULE = 2003

    /** 折叠态正文最多这么多字（通知栏一行放不下更多），完整内容走 BigText。 */
    private const val PREVIEW_CHARS = 120

    /** 渠道仅需创建一次；用户手动删除渠道后无法重建，属于系统行为，不做补救。 */
    @Volatile
    private var channelReady = false

    /** AI 回复完成。 */
    fun notifyReply(context: Context, title: String, text: String, sessionId: String = "") {
        post(context, ID_REPLY, title, text, sessionId)
    }

    /**
     * 定时 / 计划任务触发。
     *
     * 每次分配新 id：固定 id 会使后一条通知覆盖前一条，用户尚未查看的内容将无法再看到。
     * id 在 3001–3199 间轮转，与前台服务（1001）、回复（2001）均不冲突。
     */
    fun notifySchedule(context: Context, title: String, text: String, sessionId: String = "") {
        post(context, nextScheduleId(), title, text, sessionId)
    }

    private val scheduleSeq = java.util.concurrent.atomic.AtomicInteger(ID_SCHEDULE)

    private fun nextScheduleId(): Int {
        val next = scheduleSeq.incrementAndGet()
        if (next > ID_SCHEDULE + 199) {
            scheduleSeq.set(ID_SCHEDULE)
            return scheduleSeq.incrementAndGet()
        }
        return next
    }

    /**
     * 三个入口的公共实现。
     *
     * 整体使用 runCatching：通知失败（权限被撤回、厂商定制）不得导致 Agent 循环崩溃；
     * 通知仅为附加提醒，丢失不影响回合结果。
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
                // 锁屏可见：定时任务常在后台完成，用户需要不解锁也能看到结果摘要
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            manager.notify(id, notification)
            FileLog.log(TAG, "通知[" + id + "] " + title)
        }.onFailure { FileLog.log(TAG, "通知失败：" + it) }
    }

    /**
     * Android 13+ 未授予 POST_NOTIFICATIONS 时系统会静默丢弃，此处先行判断并跳过：
     * 避免创建无用的渠道与记录「已发送但无接收方」的日志。
     * 13 以下无运行时权限，声明即生效。
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
     * IMPORTANCE_HIGH 是「能够弹出横幅」的前提（DEFAULT 仅进入通知栏且无提示音）；
     * 声音与振动由该渠道控制，用户可在系统设置中单独调整，不影响其他功能。
     */
    @Synchronized
    private fun ensureChannel(manager: NotificationManager) {
        if (channelReady) return
        // minSdk 26：渠道 API 直接可用，无需判断版本（同 AgentForegroundService）
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
        // 该渠道已无用途，删除以避免设置中出现两条同名的「任务结果」
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        channelReady = true
    }

    /**
     * 点通知回 App。
     *
     * NEW_TASK 复用已有任务（不重建聊天记录）；CLEAR_TOP + SINGLE_TOP 使已在前台的
     * MainActivity 执行 onNewIntent，避免在栈上重复创建实例。
     * requestCode 使用通知 id：各类通知各自的令牌才不会被 FLAG_UPDATE_CURRENT 互相覆盖。
     */
    private fun contentIntent(context: Context, id: Int, sessionId: String): PendingIntent {
        val intent = Intent().setClassName(context, LAUNCH_ACTIVITY)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        // 只发令牌不发会话 id：没有目标会话时不带任何参数，点击仅回到 App 默认界面
        issueSessionToken(context, sessionId)?.let { intent.putExtra(EXTRA_SESSION_TOKEN, it) }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 登记一次性令牌并返回它；会话 id 为空（无目标会话）时不登记，返回 null。
     *
     * 偏好文件位于应用私有沙箱，其他应用读不到；值格式「登记时间|会话 id」，
     * 用于按时间淘汰，避免通知长期累积把偏好越堆越大。登记失败只表示少了跳转能力，
     * 通知本身照发，因此用 runCatching 兜住。
     *
     * 用 commit 而非 apply 同步落盘：定时任务由闹钟在进程刚被拉起时执行，回合结束发完通知后
     * 进程可能马上被系统回收；apply 的异步写在这条路径上有丢令牌的可能，表现为点通知只回到
     * 默认界面。这里只在发通知时写一次、内容只有几十字节，阻塞代价可以忽略。
     */
    private fun issueSessionToken(context: Context, sessionId: String): String? {
        if (sessionId.isBlank()) return null
        return runCatching {
            val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            val token = java.util.UUID.randomUUID().toString()
            val editor = prefs.edit()
                .putString(token, System.currentTimeMillis().toString() + "|" + sessionId)
            // 超出上限时先淘汰登记最早的；正常使用同时挂着的通知远少于上限
            val all = prefs.all
            if (all.size >= TOKEN_MAX) {
                all.entries
                    .sortedBy { (it.value as? String)?.substringBefore('|')?.toLongOrNull() ?: 0L }
                    .take(all.size - TOKEN_MAX + 1)
                    .forEach { editor.remove(it.key) }
            }
            editor.commit()
            token
        }.getOrNull()
    }

    /**
     * 兑换并作废令牌，返回登记的会话 id。
     *
     * 兑换即删除：同一条通知被点第二次、或调用方用旧令牌重放，都只会得到 null。
     * 任何异常都按「没有目标会话」处理，不能让可疑参数把启动流程带崩。
     *
     * 删除同样用 commit：作废必须落盘，否则进程在删除后立刻被杀，旧令牌会留到下一次启动仍可用。
     */
    fun consumeSessionToken(context: Context, token: String?): String? {
        if (token.isNullOrBlank()) return null
        return runCatching {
            val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            val value = prefs.getString(token, null) ?: return null
            prefs.edit().remove(token).commit()
            value.substringAfter('|', "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 折叠态正文：压成一行再截前 [PREVIEW_CHARS] 字。
     * 多行文本在通知栏仅显示第一行，换行会浪费空间；超长时截断并添加省略号，完整内容由 BigText 展示。
     */
    private fun oneLine(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS) + "…"
    }
}
