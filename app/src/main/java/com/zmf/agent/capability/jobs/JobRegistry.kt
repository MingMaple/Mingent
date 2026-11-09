package com.zmf.agent.capability.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 后台任务的进程内注册表。
 *
 * 语义对齐 DSH 的 jobs：模型可使用 `job_start` 启动长任务，先行处理其他事项，完成后通过 `job_output`
 * 获取结果。任务归属**绑定到启动它的会话**（[sessionId]），其他会话既无法读取也无法停止。
 *
 * 关键边界如下：
 * - **进程内**：进程被系统终止后，注册表与所有未完成任务一并消失。这与 DSH 的进程内注册表同源，
 *   并非缺陷。跨重启的续跑属于另一条路径（通知 + 点击通知返回会话），本版本不实现。
 * - **输出仅保留内存环**：每个任务一个有界环（[MAX_RING_BYTES]），超出时丢弃最旧内容，并在尾部标注「已省略」。
 *   需要落盘的长输出由生产方自行写入 [com.zmf.agent.capability.tools.spill.SpillStore]，
 *   内存环仅保证「最近一段」可直接读取。
 * - **授权依据会话归属**：job id 可预测（kind + 短随机），不依赖 id 保密，而依赖「仅本会话可访问」。
 */
object JobRegistry {

    /** 结算通知的固定抬头：界面据此把通知与用户发言区分开（旧数据没有 origin 标记也认得出）。 */
    const val NOTICE_PREFIX = "你之前启动的后台任务已经结束："

    /** 单个任务的内存输出环上限：超出时丢弃最旧内容，防止单个高频输出命令耗尽内存。 */
    private const val MAX_RING_BYTES = 256 * 1024

    private const val MAX_JOBS = 64

    private val lock = Any()

    /**
     * 后台任务的执行作用域：生产方在这里 launch 长任务，生命周期独立于「起任务那次工具调用」的协程。
     *
     * SupervisorJob 保证单个任务崩溃不影响其他任务；Dispatchers.IO 使阻塞的终端执行不占用主线程。
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 全部任务；LinkedHashMap 记住创建顺序，`job_list` 按它输出。 */
    private val jobs = LinkedHashMap<String, Entry>()

    /**
     * 结算版本号：每有一个任务结束（completed / killed / failed）就 +1。
     *
     * 使用 StateFlow 而非 Compose 的 mutableStateOf：写入发生在生产方的 IO 线程，
     * StateFlow 本身线程安全，也不要求调用方在组合中先持有它。唤醒逻辑订阅它判断「是否有新结算」。
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
        /** 记录「上次 job_output 读取到的位置」：下一次读取仅返回增量。 */
        val deliveredBytes: Long,
        /** 是否已请求取消：结算时据此把结果改判成 killed。 */
        val killRequested: Boolean = false
    )

    /** 启动一个任务。返回任务 id；会话归属由 [sessionId] 指定。 */
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
            // 超过上限时淘汰最旧的任务：优先淘汰已结算的，全部运行中时才淘汰最旧的一条
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
     * 标记与信号分两步，是因为「请求取消」属于注册表的状态，「实际停止」由生产方负责：
     * 进程可能在信号到达前自行退出，先到达的结算结果为准。返回是否成功标记。
     */
    fun requestKill(id: String, reason: String = ""): Boolean = synchronized(lock) {
        val e = jobs[id] ?: return false
        if (e.status != Status.RUNNING) return false
        jobs[id] = e.copy(killRequested = true, note = reason.trim())
        true
    }

    /**
     * 读一个任务的输出；[since] 之前的字节不再重复返回，读取后推进 [Entry.deliveredBytes]。
     * 返回「正文 + 当前总交付位置」；任务不存在返回 null。
     */
    fun read(id: String, since: Long): Pair<String, Long>? {
        synchronized(lock) {
            val e = jobs[id] ?: return null
            // 内存环已丢弃最旧部分：since 早于环起点时，从环头整段返回，并注明存在省略
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
            // 已结算的任务不再改判：看门狗判定超时与进程「恰在此时自行退出」会先后到达，
            // 先写入的结果即为事实，后到者直接丢弃（同时保证同一任务只入队一条通知）
            if (e.status != Status.RUNNING) return
            // 已请求取消的任务，即使命令正常结束也按「被杀」结算，以满足 job_kill 的语义
            val finalStatus = if (e.killRequested) Status.KILLED else status
            // 取消理由与退出码都需保留：仅报告 exit=143 无法区分是用户停止还是进程自身崩溃
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
     * 为什么取走即清空：通知只应发送一次；清空后该结算不再触发下一次注入，避免
     * 「被唤醒的一轮再次读取同一已完成任务」的自激。
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
                    // 其他会话的任务：保留，等待对应会话取走
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
        // 利用 nanoTime 低位的抖动生成，避免同一毫秒内编号重复；8 位足以区分同会话内的任务
        for (i in 0 until 8) {
            val n = (System.nanoTime() shr (i * 5)) and 0x7FFFFFFF
            sb.append(alpha[(n % alpha.length).toInt()])
        }
        return sb.toString()
    }

    /**
     * 完成通知的正文，措辞与 DSH 对齐：任务结算时，将「读取其输出」这一动作交由模型执行。
     *
     * 由 AgentLoop 与 SendController 共用，保证「繁忙注入」与「空闲唤醒」看到的是同一句话。
     */
    fun notificationText(entries: List<Entry>): String {
        val sb = StringBuilder()
        sb.append(NOTICE_PREFIX).append('\n')
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
