package com.zmf.agent.agent.subagent

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 子智能体的内存登记表，只服务主页右上角的「子任务」面板。
 *
 * 不落盘、不分会话，也不参与成败判定：SubAgentRunner 负责写，ChatScreen 订阅 [version] 后读快照，纯展示用。
 * 写入发生在 IO 协程、读取发生在 Compose 主线程，所以整张表用一把锁守着，对外只给不可变快照，
 * 读到的永远是一致的一份（不会出现面板里某条记录只更新了一半的情况）。
 */
object SubAgentRegistry {

    /** 运行中。 */
    const val RUNNING = "running"

    /** 已结束并拿到结论。 */
    const val DONE = "done"

    /** 结束了但失败（超时 / 异常 / 空结论），原因记在 [Entry.error]。 */
    const val FAILED = "failed"

    /** 面板里单条结论最多留多少字符：完整结论仍走工具结果，这里只够看清大意。 */
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
        /** 已经跑到第几轮（AgentLoop 的轮次，从 1 开始；0 = 还没开跑）。 */
        val rounds: Int,
        val startedAt: Long,
        /** 结束时间；还在跑时是 0。 */
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
     * 快照版本号：每次快照**真的变了**就 +1。
     *
     * 主页以前靠 300ms 轮询拿快照，手机上等于空闲时也在每 300ms 醒一次、分配一份列表；
     * 改成订阅这个 Flow 之后，没有子智能体在跑时它就是一条直线，谁都叫不醒。
     * 用 StateFlow 而不是 Compose 的 mutableStateOf：写入发生在 IO 线程（Runner 里），
     * StateFlow 天生线程安全，也不要求谁在组合里先持有它。
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

    /** 这条记录还能不能续聊：记录不在或不是多轮，一律 false。 */
    fun canContinue(id: String): Boolean = find(id)?.multiTurn == true

    /** 记本轮轮次；已经结束的记录不再改（收尾晚到的进度回调不能把状态又刷回运行中）。 */
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

    /** 用真正的委托内容覆盖登记（跑到收尾才拿得到 prompt/model/transcript）；不改 state。 */
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

    /** 先截断单条正文，再按条数上限裁掉最早的（首条 user 保留）。 */
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

    /** 当前全量快照（按创建顺序，新的在最后；面板自己决定怎么排）。 */
    fun snapshot(): List<Entry> = entries

    /** 丢掉所有已结束的记录，只留运行中的。 */
    fun clearFinished() {
        synchronized(lock) {
            val next = entries.filter { it.state == RUNNING }
            // 本来就全是运行中的（或者本来是空的）就不算变化，别通知界面白跑一趟
            if (next.size != entries.size) {
                entries = next
                bump()
            }
        }
    }

    /** 版本号只在自己那把锁里加：所有写入路径都已经被锁串起来，不会漏也不会重复。 */
    private fun bump() {
        version.value = version.value + 1
    }

    /** 按 id 找下标再整体换掉那一条；找不到（比如刚被清空）就什么也不做。 */
    private fun replace(id: String, block: (Entry) -> Entry) {
        synchronized(lock) {
            val index = entries.indexOfFirst { it.id == id }
            if (index >= 0) {
                entries = entries.toMutableList().also { it[index] = block(it[index]) }
                // 找不到记录就不动版本号：收尾回调晚到这类空操作不该把界面叫醒
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
