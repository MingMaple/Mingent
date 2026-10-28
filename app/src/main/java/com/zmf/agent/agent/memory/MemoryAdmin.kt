package com.zmf.agent.agent.memory

import android.content.Context
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 概览三数：事件行数（唯一真相源的体量）、节点数（投影里的可检索条目）、归档数。 */
data class MemoryStatus(
    val eventsLines: Int = 0,
    val nodes: Int = 0,
    val archived: Int = 0,
)

/** 一条检索命中。score 是内核 RRF 重排后的分数，界面只用来排序展示，不参与任何再计算。 */
data class MemoryHit(
    val id: String,
    val kind: String,
    val ts: String,
    val text: String,
    val score: Double = 0.0,
    val disclosure: String = "",
)

/** 一个待巩固批次：同一命名空间里攒够条数的一组来源 id（点不下去，只报数）。 */
data class MemoryBatch(
    val ns: String,
    val count: Int,
    val ids: List<String>,
)

/** 一次维护扫描的结果。[perfume] 是「待熏习候选」的展示行，已在这里拼好，界面不再解析。 */
data class MemoryCycle(
    val scanned: Int = 0,
    val archived: Int = 0,
    val batches: List<MemoryBatch> = emptyList(),
    val perfume: List<String> = emptyList(),
)

/**
 * 记忆页的整屏快照。
 *
 * 为什么所有字段都带默认值：页面是无状态的（state 由父会话持有），
 * 任何一次动作返回的都是**整体替换**：缺省的块会立刻退回这里的默认值，
 * 所以 [doctorOk] 默认 true 只是「没数据时的占位」，真正的结论必须以一次 doctor 的结果覆盖（见 MemoryAdmin.snapshot）。
 */
data class MemoryState(
    val busy: Boolean = false,
    val status: MemoryStatus? = null,
    val doctorOk: Boolean = true,
    val doctorLines: List<String> = emptyList(),
    val query: String = "",
    val briefing: String = "",
    val hits: List<MemoryHit> = emptyList(),
    val cycle: MemoryCycle? = null,
    val exportPath: String = "",
    val exportLines: Int = 0,
    val error: String = "",
)

/**
 * 记忆页的数据入口：把界面上的四个动作翻译成内核 CLI 调用，并把 --json 结果解析成上面那几个不可变快照。
 *
 * 为什么这里不缓存、不增量更新：内核的真相源是 append-only 事件流，任何一次写入都可能让状态、检索结果、
 * 待巩固批次一起变。缓存一半反而会出现「节点数变了但批次还是旧的」这种自相矛盾的界面，所以每次动作都整份重取。
 *
 * 为什么不在 Kotlin 里算任何东西：分数、衰减、批次判定都只认内核输出，不在这里重算一遍。
 * 这里做的只有「取 JSON → 映射字段」，解析失败就把内核原文塞进 error，不吞不编。
 *
 * 关于「根」：本类对外收的是**工作区根**（`currentWorkspaceRoot()`），只拿它当「默认命名空间名」的来源
 * （`project:<工作区目录名>`）。内核本体与数据根都在 App 内部存储的 `filesDir/mingmem/` 下，
 * 由 [MemoryKernel.ensureLibrary] 统一铺、统一定位：页面和调用方不关心、也不传库路径。
 */
object MemoryAdmin {

    private const val TAG = "mem-admin"

    /** 概览 + 体检。进页面时调一次，也是其余动作的公共前置。 */
    suspend fun refresh(context: Context, workspaceRoot: File): MemoryState = snapshot(context, workspaceRoot)

    /**
     * 检索记忆。空检索词不落到 CLI：内核会以退出码 2 报「--query 必填」，
     * 把本地就能说清的空输入变成一次往返，白等一轮。
     */
    suspend fun search(context: Context, workspaceRoot: File, query: String): MemoryState {
        val text = query.trim()
        if (text.isEmpty()) {
            return snapshot(context, workspaceRoot).copy(query = "", briefing = "", hits = emptyList())
        }
        val base = snapshot(context, workspaceRoot)
        // --dry-run：设置页的「检索」是只读预览，不带它内核会顺手写一条 access 事件（访问强化），
        // 每搜一次就把 storage 往上抬一点，检索强度被设置页「看几眼」悄悄改掉。
        return MemoryKernel.run(context, workspaceRoot, listOf("recall", "--query=" + text, "--dry-run")).fold(
            onSuccess = { raw ->
                val payload = jsonObjectAt(raw)
                if (payload == null) {
                    failed(base, "recall 输出解析失败：" + raw.take(400))
                } else {
                    base.copy(
                        query = text,
                        briefing = payload.optString("briefing", ""),
                        hits = parseHits(payload.optJSONArray("items")),
                    )
                }
            },
            onFailure = { error -> failed(base, error.message ?: ("recall 调用失败：" + error.javaClass.simpleName)) },
        )
    }

    /**
     * 维护扫描。**必须带 --dry-run**：这个按钮只报数，绝不写库。
     *
     * 真正会写事件的那次 cycle（衰减 + 归档 + sweep 事件）留在对话里由模型显式跑；
     * 用户在设置页点一下就把强度改了，等于绕过「不可逆动作的最小充分核验」这条约束。
     */
    suspend fun cycle(context: Context, workspaceRoot: File): MemoryState {
        val base = snapshot(context, workspaceRoot)
        return MemoryKernel.run(context, workspaceRoot, listOf("cycle", "--dry-run")).fold(
            onSuccess = { raw ->
                val payload = jsonObjectAt(raw)
                if (payload == null) {
                    failed(base, "cycle 输出解析失败：" + raw.take(400))
                } else {
                    base.copy(cycle = parseCycle(payload))
                }
            },
            onFailure = { error -> failed(base, error.message ?: ("cycle 调用失败：" + error.javaClass.simpleName)) },
        )
    }

    /**
     * 导出给人看的视图。默认不传 --out，落回库自己的 _Data/memory.md。
     *
     * 界面可以指定目录：在「记忆 → 导出」里填一个绝对路径，这里就带 `--out=<路径>`。
     * 为什么必须让用户指定：原来的固定落点是 App 私有目录（/sdcard/Android/data/<包名>/files/…），
     * 文件管理器根本进不去，导出完拿不到东西。
     *
     * 内核默认排除 private / ask，这里也不加 --include-*：设置页不该成为绕过披露等级的捷径。
     */
    suspend fun export(context: Context, workspaceRoot: File, outPath: String = ""): MemoryState {
        val base = snapshot(context, workspaceRoot)
        val args = if (outPath.isBlank()) {
            listOf("export")
        } else {
            // 路径一律走绝对路径：相对路径会把产物落在库根
            listOf("export", "--out=" + outPath)
        }
        return MemoryKernel.run(context, workspaceRoot, args).fold(
            onSuccess = { raw ->
                val payload = jsonObjectAt(raw)
                if (payload == null) {
                    failed(base, "export 输出解析失败：" + raw.take(400))
                } else {
                    base.copy(
                        exportPath = payload.optString("out", ""),
                        exportLines = payload.optInt("lines", 0),
                    )
                }
            },
            onFailure = { error -> failed(base, error.message ?: ("export 调用失败：" + error.javaClass.simpleName)) },
        )
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 概览 + 体检的公共快照。
     *
     * 为什么每个动作都先跑这一遍：state 是整体替换的，只填自己那一块会让其余块退回默认值；
     * 而 doctorOk 默认 true，那等于「没跑体检却显示通过」，这种伪造结果不能出现。
     * 两次 CLI 都是只读命令（doctor 只读；status 最多重建可丢弃的投影），不会碰事件流。
     */
    private suspend fun snapshot(context: Context, workspaceRoot: File): MemoryState {
        var state = MemoryState()
        var error = ""

        MemoryKernel.run(context, workspaceRoot, listOf("status")).fold(
            onSuccess = { raw ->
                val status = parseStatus(raw)
                if (status == null) {
                    error = "status 输出解析失败：" + raw.take(400)
                } else {
                    state = state.copy(status = status)
                }
            },
            onFailure = { e -> error = e.message ?: ("status 调用失败：" + e.javaClass.simpleName) },
        )

        val doctor = MemoryKernel.run(context, workspaceRoot, listOf("doctor"))
        val parsed = parseDoctor(doctor.getOrNull() ?: doctor.exceptionOrNull()?.message.orEmpty())
        if (parsed != null) {
            state = state.copy(doctorOk = parsed.first, doctorLines = parsed.second)
        } else {
            // 连 JSON 都回捞不到，才认为体检本身跑不起来：显示异常，并把内核原文交给 error
            state = state.copy(doctorOk = false)
            if (error.isEmpty()) {
                error = doctor.exceptionOrNull()?.message ?: "doctor 输出解析失败"
            }
        }

        return state.copy(error = error, busy = false)
    }

    private fun failed(base: MemoryState, message: String): MemoryState {
        FileLog.log(TAG, "记忆操作失败：" + message.take(300))
        return base.copy(error = message)
    }

    private fun parseStatus(raw: String): MemoryStatus? {
        val payload = jsonObjectAt(raw) ?: return null
        return MemoryStatus(
            eventsLines = payload.optInt("events_lines", 0),
            nodes = payload.optInt("nodes", 0),
            archived = payload.optInt("archived", 0),
        )
    }

    /**
     * 体检结果 = ok + 逐行说明。
     *
     * 注意 doctor 在「发现问题（坏行 / 投影损坏）」时退出码是 1，MemoryKernel 会按退出码把这个结果当失败，
     * 但 JSON 已经完整打在原文里（前面多一行「第 4 步…退出码 1」）。所以这里统一从文本里回捞 JSON：
     * 能捞到就是「体检不通过」，捞不到才是「体检跑不起来」：两件事不能混成一句报错。
     */
    private fun parseDoctor(text: String): Pair<Boolean, List<String>>? {
        val payload = jsonObjectAt(text) ?: return null
        val ok = payload.optBoolean("ok", false)
        val lines = ArrayList<String>()
        payload.optJSONArray("lines")?.let { array ->
            for (i in 0 until array.length()) {
                val line = array.optString(i, "").trim()
                if (line.isNotEmpty()) lines.add(line)
            }
        }
        return ok to lines
    }

    private fun parseHits(array: JSONArray?): List<MemoryHit> {
        if (array == null) return emptyList()
        val out = ArrayList<MemoryHit>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(
                MemoryHit(
                    id = item.optString("id", ""),
                    kind = item.optString("kind", ""),
                    ts = item.optString("ts", ""),
                    text = item.optString("text", ""),
                    score = item.optDouble("score", 0.0),
                    disclosure = item.optString("disclosure", ""),
                )
            )
        }
        return out
    }

    private fun parseCycle(payload: JSONObject): MemoryCycle {
        val batches = ArrayList<MemoryBatch>()
        payload.optJSONArray("batches")?.let { array ->
            for (i in 0 until array.length()) {
                val batch = array.optJSONObject(i) ?: continue
                val ids = ArrayList<String>()
                batch.optJSONArray("ids")?.let { idArray ->
                    for (j in 0 until idArray.length()) {
                        val id = idArray.optString(j, "")
                        if (id.isNotEmpty()) ids.add(id)
                    }
                }
                batches.add(
                    MemoryBatch(
                        ns = batch.optString("ns", ""),
                        count = batch.optInt("count", ids.size),
                        ids = ids,
                    )
                )
            }
        }
        val perfume = ArrayList<String>()
        payload.optJSONArray("perfume")?.let { array ->
            for (i in 0 until array.length()) {
                val candidate = array.optJSONObject(i) ?: continue
                // 拼成一行给界面直接用：这一行只是展示文案，分类判断仍在内核里
                perfume.add(
                    candidate.optString("ns", "") + " ｜ #" + candidate.optString("tag", "") +
                        " × " + candidate.optInt("count", 0)
                )
            }
        }
        return MemoryCycle(
            scanned = payload.optInt("scanned", 0),
            archived = payload.optJSONArray("archived")?.length() ?: 0,
            batches = batches,
            perfume = perfume,
        )
    }

    /**
     * 从一段文本里取出 JSON 对象：先整体解析，失败再从第一个 '{' 处重试。
     *
     * 容忍前缀是为了 doctor 这类「JSON 前面多了一句中文原因」的输出；
     * 整体解析优先，是因为正文里本身可能就含花括号（引用的代码片段），不能一上来就截。
     */
    private fun jsonObjectAt(text: String): JSONObject? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
        val start = trimmed.indexOf('{')
        if (start <= 0) return null
        return runCatching { JSONObject(trimmed.substring(start)) }.getOrNull()
    }
}
