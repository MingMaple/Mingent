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
    /** 提问通知：等待用户作答期间常驻，提交、超时或回到前台后取消。 */
    private const val ID_QUESTION = 2004

    /** 工具主动提醒：与回合完成通知分开，避免相互覆盖。 */
    private const val ID_USER = 2005

    /** 折叠态正文最多显示的字数（通知栏一行无法容纳更多），完整内容由 BigText 展示。 */
    private const val PREVIEW_CHARS = 120

    /** 渠道仅需创建一次；用户手动删除渠道后无法重建，属于系统行为，不做补救。 */
    @Volatile
    private var channelReady = false

    /** AI 回复完成（X02）：id 按会话分配，多会话并行收尾各出各的通知，同会话只留最新一条。 */
    fun notifyReply(context: Context, title: String, text: String, sessionId: String = "") {
        if (sessionId.isBlank()) {
            post(context, ID_REPLY, title, text, sessionId, group = null)
        } else {
            post(context, replyIdOf(sessionId), title, text, sessionId, group = sessionId)
        }
    }

    /**
     * 回复通知的会话维度 id（X02）：`5001 + (会话 id 稳定散列 % 500)`，即 5001–5500。
     *
     * id 空间注释（改动 id 分配前先读）：1001＝前台服务常驻；2001＝无会话归属的回复；
     * 2003–2199＝定时任务轮转；2004＝提问；2005＝工具提醒；5001–5500＝按会话的回复通知。
     * 选 5001 起是为了与上述区间都不重叠。散列冲突概率低但非零：两条会话散列同槽时
     * 退化为互相覆盖，与引入会话维度之前的行为一致；要求绝对不冲突需自增槽位表，代价是多一份映射。
     */
    private fun replyIdOf(sessionId: String): Int =
        ID_REPLY_BASE + (sessionId.hashCode().mod(REPLY_ID_SPAN))

    private const val ID_REPLY_BASE = 5001
    private const val REPLY_ID_SPAN = 500

    /**
     * 定时 / 计划任务触发。
     *
     * 每次分配新 id：固定 id 会使后一条通知覆盖前一条，用户尚未查看的内容将无法再看到。
     * id 在 2003–2199 之间轮转，与前台服务（1001）、回复（2001）均不冲突。
     */
    fun notifySchedule(context: Context, title: String, text: String, sessionId: String = "") {
        post(context, nextScheduleId(), title, text, sessionId)
    }

    /**
     * 工具主动提醒。
     *
     * 仅在应用不在前台时调用；固定 id 使同一类提醒只保留最新一条。
     */
    fun notifyUser(context: Context, title: String, text: String, sessionId: String = "") {
        post(context, ID_USER, title, text, sessionId)
    }

    /**
     * 提问待作答。
     *
     * 正文应包含问题摘要与可选项；点击回到对应会话的提问卡片。
     */
    fun notifyQuestion(context: Context, title: String, text: String, sessionId: String = "") {
        post(context, ID_QUESTION, title, text, sessionId)
    }

    /** 取消指定通知；不存在时无副作用。 */
    fun cancel(context: Context, id: Int) {
        runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            manager.cancel(id)
        }
    }

    /** 提问通知的 id；界面在提交或超时后据此取消。 */
    fun questionId(): Int = ID_QUESTION

    /** 当前是否允许发送系统通知。 */
    fun isAllowed(context: Context): Boolean = canNotify(context.applicationContext, "权限检查")

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
     *
     * [group] 非空时设置通知分组（X02）：同组通知在通知栏折叠展示，不同会话各自成组互不覆盖。
     */
    private fun post(context: Context, id: Int, title: String, text: String, sessionId: String, group: String? = null) {
        val app = context.applicationContext
        if (!canNotify(app, title)) return
        runCatching {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            ensureChannel(manager)
            val body = text.trim()
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
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
            // 会话维度的通知进会话自己的分组：多条会话并行收尾时，通知栏各出各的，不互相顶掉
            if (group != null) builder.setGroup(group)
            manager.notify(id, builder.build())
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
     * 用 apply 异步落盘，不阻塞发通知的主线程：令牌一次性且短期有效，进程在写盘前被杀最多丢失
     * 一次跳转能力，通知本身照发；这里只是在发通知时写一次、内容只有几十字节，异步写足够。
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
            editor.apply()
            token
        }.getOrNull()
    }

    /**
     * 兑换并作废令牌，返回登记的会话 id。
     *
     * 兑换即删除：同一条通知被点第二次、或调用方用旧令牌重放，都只会得到 null。
     * 任何异常都按「没有目标会话」处理，不能让可疑参数把启动流程带崩。
     *
     * 删除同样用 apply 异步作废，避免兑换路径（启动阶段、主线程）同步刷盘；
     * 进程若在写盘前被杀，可能留下一条旧令牌，但令牌一次性且短期有效，风险可接受。
     */
    fun consumeSessionToken(context: Context, token: String?): String? {
        if (token.isNullOrBlank()) return null
        return runCatching {
            val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            val value = prefs.getString(token, null) ?: return null
            prefs.edit().remove(token).apply()
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
