package com.zmf.agent.capability.subagent

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 子智能体的内存登记表，只服务主页右上角的「子任务」面板。
 *
 * 不落盘、不分会话，也不参与成败判定：SubAgentRunner 负责写入，ChatScreen 订阅 [version] 后读取快照，仅用于展示。
 * 写入发生在 IO 协程、读取发生在 Compose 主线程，因此整张表由一把锁保护，对外仅提供不可变快照，
 * 读取结果始终一致（不会出现面板中某条记录仅更新一半的情况）。
 */
object SubAgentRegistry {

    /** 运行中。 */
    const val RUNNING = "running"

    /** 已结束并拿到结论。 */
    const val DONE = "done"

    /** 结束了但失败（超时 / 异常 / 空结论），原因记在 [Entry.error]。 */
    const val FAILED = "failed"

    /** 面板中单条结论的最大字符数：完整结论仍通过工具结果传递，此处仅用于了解概要。 */
    const val RESULT_LIMIT = 400

    /** 续聊记录最多留多少条：超出丢最早的，但首条 user 必须保留（否则模型看不到最初的委托）。 */
    private const val MAX_TRANSCRIPT = 200

    /** 单条记录正文的上限，防止超长工具输出把内存撑大。 */
    private const val MAX_TURN_CHARS = 4000

    /** 一条子任务的登记信息。 */
    data class Entry(
        val id: String,
        val description: String,
        /** [RUNNING] / [DONE] / [FAILED]。 */
        val state: String,
        /** 已执行的轮数（AgentLoop 的轮次，从 1 开始；0 = 尚未开始）。 */
        val rounds: Int,
        val startedAt: Long,
        /** 结束时间；仍在运行时为 0。 */
        val finishedAt: Long,
        /** 成功结论，只保留前 [RESULT_LIMIT] 字符。 */
        val result: String,
        /** 失败原因，只保留前 [RESULT_LIMIT] 字符。 */
        val error: String,
        /** 最初的委托内容，续聊时让模型知道它在做什么。 */
        val prompt: String = "",
        /** 多轮子智能体执行完仍可续聊；单轮的收尾即结束，协议上不再接受消息。 */
        val multiTurn: Boolean = false,
        /** 可续跑的对话记录，供面板展示与 resume 重建上下文。 */
        val transcript: List<SubAgentTurn> = emptyList(),
        /** 这条记录用的模型：续聊沿用同一个，不中途换模型。 */
        val model: String = "",
        /** 最后一次写入时间。 */
        val updatedAt: Long = 0L
    )

    private val lock = Any()

    /**
     * 快照版本号：每次快照**实际发生变化**时 +1。
     *
     * 采用订阅 StateFlow 而非定时轮询：空闲时不会周期性唤醒并分配列表快照。
     * 使用 StateFlow 而非 Compose 的 mutableStateOf：写入发生在 IO 线程（Runner 中），
     * StateFlow 本身线程安全，也不要求调用方在组合中先持有它。
     */
    val version = MutableStateFlow(0)

    /** 对外快照：每次修改都换成新的 List，读者手里的旧引用不会被后续写入改掉。 */
    @Volatile
    private var entries: List<Entry> = emptyList()

    private var seq = 0

    /** 登记一条「运行中」，返回它在面板里的 id。 */
    fun begin(description: String, multiTurn: Boolean = false): String = synchronized(lock) {
        seq++
        val id = "sa" + seq
        val now = System.currentTimeMillis()
        entries = entries + Entry(
            id = id,
            description = description,
            state = RUNNING,
            rounds = 0,
            startedAt = now,
            finishedAt = 0L,
            result = "",
            error = "",
            multiTurn = multiTurn,
            updatedAt = now
        )
        bump()
        id
    }

    /** 这条记录是否可续聊：记录不存在或不是多轮，一律返回 false。 */
    fun canContinue(id: String): Boolean = find(id)?.multiTurn == true

    /** 记录本轮轮次；已结束的记录不再修改（收尾后迟到的进度回调不得将状态改回运行中）。 */
    fun progress(id: String, rounds: Int) {
        replace(id) { entry -> if (entry.state == RUNNING) entry.copy(rounds = rounds) else entry }
    }

    /** 成功收尾：结论截到 [RESULT_LIMIT] 字符。 */
    fun finish(id: String, result: String) {
        val now = System.currentTimeMillis()
        replace(id) { entry ->
            entry.copy(state = DONE, finishedAt = now, result = result.take(RESULT_LIMIT), updatedAt = now)
        }
    }

    /** 失败收尾：原因截到 [RESULT_LIMIT] 字符。 */
    fun fail(id: String, error: String) {
        val now = System.currentTimeMillis()
        replace(id) { entry ->
            entry.copy(state = FAILED, finishedAt = now, error = error.take(RESULT_LIMIT), updatedAt = now)
        }
    }

    /** 用实际委托内容覆盖登记信息（执行至收尾才能取得 prompt/model/transcript）；不修改 state。 */
    fun attach(id: String, prompt: String, model: String, turns: List<SubAgentTurn>) {
        val now = System.currentTimeMillis()
        replace(id) { entry ->
            entry.copy(prompt = prompt, model = model, transcript = trim(turns), updatedAt = now)
        }
    }

    /** 续聊时追加两条（用户新消息、子智能体新结论），同样受条数与长度上限约束。 */
    fun appendTurns(id: String, turns: List<SubAgentTurn>) {
        if (turns.isEmpty()) return
        val now = System.currentTimeMillis()
        replace(id) { entry ->
            entry.copy(transcript = trim(entry.transcript + turns), updatedAt = now)
        }
    }

    /** 续聊开始：把已完成/失败的那条重新标成运行中，并发闸之外再加一道「同一条不能被续两次」。 */
    fun markRunning(id: String) {
        val now = System.currentTimeMillis()
        replace(id) { entry ->
            entry.copy(state = RUNNING, finishedAt = 0L, rounds = 0, updatedAt = now)
        }
    }

    /** 同步读一条记录：resume 要靠它重建上下文。 */
    fun find(id: String): Entry? = synchronized(lock) { entries.firstOrNull { it.id == id } }

    /** 先截断单条正文，再按条数上限丢弃最早记录（保留首条 user 消息）。 */
    private fun trim(turns: List<SubAgentTurn>): List<SubAgentTurn> {
        val clipped = turns.map { turn ->
            if (turn.text.length <= MAX_TURN_CHARS) {
                turn
            } else {
                turn.copy(text = turn.text.take(MAX_TURN_CHARS) + "…")
            }
        }
        if (clipped.size <= MAX_TRANSCRIPT) return clipped
        val firstUser = clipped.firstOrNull { it.role == "user" }
        val tail = clipped.takeLast(if (firstUser == null) MAX_TRANSCRIPT else MAX_TRANSCRIPT - 1)
        return if (firstUser == null) tail else listOf(firstUser) + tail.filter { it !== firstUser }
    }

    /** 当前全量快照（按创建顺序，新记录在末尾；排序由面板决定）。 */
    fun snapshot(): List<Entry> = entries

    /** 丢掉所有已结束的记录，只留运行中的。 */
    fun clearFinished() {
        synchronized(lock) {
            val next = entries.filter { it.state == RUNNING }
            // 原本全部为运行中（或原本为空）时不视为变化，避免无意义地刷新界面
            if (next.size != entries.size) {
                entries = next
                bump()
            }
        }
    }

    /** 版本号仅在同一把锁内递增：所有写入路径均已由锁串行化，不会遗漏或重复。 */
    private fun bump() {
        version.value = version.value + 1
    }

    /** 按 id 查找下标并整体替换该条记录；找不到（例如刚被清空）时不做任何操作。 */
    private fun replace(id: String, block: (Entry) -> Entry) {
        synchronized(lock) {
            val index = entries.indexOfFirst { it.id == id }
            if (index >= 0) {
                entries = entries.toMutableList().also { it[index] = block(it[index]) }
                // 找不到记录就不改动版本号：收尾回调晚到这类空操作不应触发界面刷新
                bump()
            }
        }
    }
}

/** 子智能体的一轮对话；[tools] 记这一轮调用过的工具名，只用于展示。 */
data class SubAgentTurn(
    val role: String,
    val text: String,
    val tools: List<String> = emptyList()
)
