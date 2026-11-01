package com.zmf.agent.capability.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 后台任务的进程内注册表。
 *
 * 语义对齐 DSH 的 jobs：模型可以用 `job_start` 起一个长任务、先去做别的、完成后再用 `job_output`
 * 把结果收回来。任务归属**绑定到启动它的会话**（[sessionId]），其他会话读不到也停不了。
 *
 * 关键边界（写在前面，不许含糊）：
 * - **进程内**：进程被系统杀掉，注册表与所有未完成任务一起消失。这与 DSH 的进程内注册表同源，
 *   不是缺陷。跨重启的续跑是另一条路（通知 + 点通知回会话），本版不做。
 * - **输出只留内存环**：每个任务一个有界环（[MAX_RING_BYTES]），超出就丢最旧的、尾部标注「已省略」。
 *   真正要落盘的长输出由生产方自己走 [com.zmf.agent.capability.tools.spill.SpillStore]，
 *   内存环只保证「最近一段」随手可读。
 * - **授权靠会话归属**：job id 可预测（kind + 短随机），不靠 id 保密，靠「只有本会话能碰」。
 */
object JobRegistry {

    /** 单个任务的内存输出环上限：超出丢最旧，防止一个刷屏命令把堆吃穿。 */
    private const val MAX_RING_BYTES = 256 * 1024

    private const val MAX_JOBS = 64

    private val lock = Any()

    /**
     * 后台任务的执行作用域：生产方在这里 launch 长任务，生命周期独立于「起任务那次工具调用」的协程。
     *
     * SupervisorJob 保证一个任务崩掉不影响别的任务；Dispatchers.IO 让阻塞的终端执行不占主线程。
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 全部任务；LinkedHashMap 记住创建顺序，`job_list` 按它输出。 */
    private val jobs = LinkedHashMap<String, Entry>()

    /**
     * 结算版本号：每有一个任务结束（completed / killed / failed）就 +1。
     *
     * 用 StateFlow 而不是 Compose 的 mutableStateOf：写入发生在生产方的 IO 线程，
     * StateFlow 天生线程安全，也不要求谁在组合里先持有它。唤醒逻辑订阅它来判断「有没有新结算」。
     */
    val finishedVersion = MutableStateFlow(0)

    /** 任务的稳定状态机。 */
    enum class Status { RUNNING, COMPLETED, KILLED, FAILED }

    /** 一个任务。输出是追加式，只有 [JobRegistry] 自己写。 */
    data class Entry(
        val id: String,
        val kind: String,
        val label: String,
        val sessionId: String,
        val status: Status,
        /** 内存环里的累计输出（已按 [MAX_RING_BYTES] 截断）。 */
        val output: String,
        /** 被截断前一共写入了多少字节（output 只留尾部）。 */
        val totalBytes: Long,
        /** 结算时附的一句话（失败原因 / 退出码摘要 / kill 理由）。 */
        val note: String,
        /** 记录「上次 job_output 读到哪」：下一次读只给增量。 */
        val deliveredBytes: Long,
        /** 是否已请求取消：结算时据此把结果改判成 killed。 */
        val killRequested: Boolean = false
    )

    /** 起一个任务。返回任务 id；会话归属从 [sessionId] 来。 */
    fun begin(sessionId: String, kind: String, label: String): String {
        val id = kind + "-" + shortId()
        synchronized(lock) {
            jobs[id] = Entry(
                id = id,
                kind = kind,
                label = label,
                sessionId = sessionId,
                status = Status.RUNNING,
                output = "",
                totalBytes = 0,
                note = "",
                deliveredBytes = 0
            )
            // 超过上限就丢最旧的一个（先排除还在跑的，实在没得删才删最旧的）
            while (jobs.size > MAX_JOBS) {
                val oldest = jobs.values.firstOrNull { it.status != Status.RUNNING }
                    ?: jobs.values.firstOrNull()
                    ?: break
                jobs.remove(oldest.id)
            }
        }
        return id
    }

    /** 追加一段输出；线程安全，生产方的读线程直接调。 */
    fun append(id: String, chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            val e = jobs[id] ?: return
            val merged = e.output + chunk
            val kept = if (merged.length > MAX_RING_BYTES) {
                merged.takeLast(MAX_RING_BYTES)
            } else {
                merged
            }
            jobs[id] = e.copy(output = kept, totalBytes = e.totalBytes + chunk.length)
        }
    }

    /** 结算为完成。 */
    fun complete(id: String, note: String = "") = settle(id, Status.COMPLETED, note)

    /** 结算为失败。 */
    fun fail(id: String, note: String = "") = settle(id, Status.FAILED, note)

    /** 结算为被杀（job_kill 主动取消）。 */
    fun kill(id: String, note: String = "") = settle(id, Status.KILLED, note)

    /**
     * 请求取消：标记「已请求取消」，由调用方随后向进程组发终止信号（见
     * [com.zmf.agent.infra.terminal.TerminalApi.killDetached]）。
     *
     * 标记与信号分两步，是因为「请求取消」是注册表的状态，「真的停掉」是生产方的事：
     * 进程可能在信号到达前自己退出，先到的结算结果说了算。返回是否成功标记。
     */
    fun requestKill(id: String, reason: String = ""): Boolean = synchronized(lock) {
        val e = jobs[id] ?: return false
        if (e.status != Status.RUNNING) return false
        jobs[id] = e.copy(killRequested = true, note = reason.trim())
        true
    }

    /**
     * 读一个任务的输出；[since] 之前的字节不再重复给，读完推进 [Entry.deliveredBytes]。
     * 返回「正文 + 现在总共交付到哪」；任务不存在返回 null。
     */
    fun read(id: String, since: Long): Pair<String, Long>? {
        synchronized(lock) {
            val e = jobs[id] ?: return null
            // 内存环已经丢了最旧的那段：since 比环里还早时，从环头整段给，并注明有省略
            val ringStart = (e.totalBytes - e.output.length).coerceAtLeast(0)
            val omitted = if (since < ringStart) {
                "\n…（更早的输出已从内存环丢弃，共省略 " + (ringStart - since).coerceAtLeast(0) + " 字节）…\n"
            } else {
                ""
            }
            val startInRing = (since - ringStart).coerceAtLeast(0).coerceAtMost(e.output.length.toLong()).toInt()
            val delta = omitted + e.output.substring(startInRing)
            jobs[id] = e.copy(deliveredBytes = e.totalBytes)
            return delta to e.totalBytes
        }
    }

    /** 列表快照（按创建顺序）。 */
    fun list(): List<Entry> = synchronized(lock) { jobs.values.toList() }

    /** 按 id 找；没有返回 null。 */
    fun find(id: String): Entry? = synchronized(lock) { jobs[id] }

    /** 会话能碰的任务：只认归属会话。 */
    fun forSession(sessionId: String): List<Entry> =
        synchronized(lock) { jobs.values.filter { it.sessionId == sessionId } }

    private fun settle(id: String, status: Status, note: String) {
        synchronized(lock) {
            val e = jobs[id] ?: return
            // 已结算的任务不再改判：看门狗判超时与进程「正好在这时自己退出」会先后到达，
            // 先写下的那个结果就是事实，后到的直接丢弃（也保证同一个任务只入队一条通知）
            if (e.status != Status.RUNNING) return
            // 已请求取消的任务，即使命令正常跑完也按「被杀」结算，兑现 job_kill 的语义
            val finalStatus = if (e.killRequested) Status.KILLED else status
            // 取消理由与退出码都要留下：只报 exit=143 看不出是人叫停的还是自己崩的
            val finalNote = when {
                e.note.isBlank() -> note
                note.isBlank() -> e.note
                else -> e.note + "，" + note
            }
            jobs[id] = e.copy(status = finalStatus, note = finalNote)
            // 结算即入「待通知」队列：由 AgentLoop 每轮开始（或空闲唤醒）取走并注入一条完成通知，
            // 每个结算恰好通知一次。这里记的是「结算结果」，不记状态机历史。
            pendingNotify += id
        }
        finishedVersion.value += 1
    }

    /** 待通知的任务 id 队列（按结算先后）。 */
    private val pendingNotify = ArrayList<String>()

    /**
     * 取走本会话「待通知」的任务，并清空。返回的每项是「任务快照」，用于拼通知文案。
     *
     * 为什么取走即清空：通知只该发一次；清空后这个结算不会再触发下一次注入，避免
     * 「被唤醒的一轮又读到同一个已完成任务」的自激。
     */
    fun drainNotifications(sessionId: String): List<Entry> {
        synchronized(lock) {
            val out = ArrayList<Entry>()
            val keep = ArrayList<String>()
            for (id in pendingNotify) {
                val e = jobs[id]
                if (e != null && e.sessionId == sessionId) {
                    out.add(e)
                } else if (e != null) {
                    // 别的会话的任务：留着，等那个会话来取
                    keep.add(id)
                }
                // e == null（任务已被挤出注册表）：直接丢弃通知，不再保留
            }
            pendingNotify.clear()
            pendingNotify.addAll(keep)
            return out
        }
    }

    private fun shortId(): String {
        val alpha = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(8)
        // 用 nanoTime 低位的抖动拼，避免同一毫秒内撞号；够 8 位就够区分同会话里的任务
        for (i in 0 until 8) {
            val n = (System.nanoTime() shr (i * 5)) and 0x7FFFFFFF
            sb.append(alpha[(n % alpha.length).toInt()])
        }
        return sb.toString()
    }

    /**
     * 完成通知的正文，对齐 DSH 的措辞：任务结算时，把「读它的输出」这个动作交给模型。
     *
     * 由 AgentLoop 与 SendController 共用，保证「繁忙注入」与「空闲唤醒」看到的是同一句话。
     */
    fun notificationText(entries: List<Entry>): String {
        val sb = StringBuilder()
        sb.append("你之前启动的后台任务已经结束：\n")
        for (e in entries) {
            sb.append("- ").append(e.id)
            if (e.label.isNotBlank()) sb.append("（").append(e.label).append("）")
            sb.append(" → ").append(e.status.name.lowercase())
            if (e.note.isNotBlank()) sb.append("，").append(e.note)
            sb.append('\n')
        }
        sb.append("用 job_output(job_id=\"<id>\") 读它们的输出；不再关心的用 job_kill 停掉（已结算的不用）。")
        return sb.toString()
    }
}
