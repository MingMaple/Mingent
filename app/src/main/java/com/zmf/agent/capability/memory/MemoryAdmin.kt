package com.zmf.agent.capability.memory

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

/** 一个待巩固批次：同一命名空间内达到条数阈值的一组来源 id（仅提供数量，不提供逐条操作）。 */
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
 * 为什么此处不缓存、不做增量更新：内核的真相源是 append-only 事件流，任何一次写入都可能使状态、检索结果、
 * 待巩固批次同时变化。仅缓存其中一部分会导致「节点数已变而批次仍旧」这类自相矛盾的界面，因此每次动作均整份重取。
 *
 * 为什么不在 Kotlin 中执行任何计算：分数、衰减、批次判定均以内核输出为准，不在此处重新计算。
 * 此处仅执行「读取 JSON → 映射字段」，解析失败时将内核原文写入 error，既不吞没也不编造。
 *
 * 关于「根」：本类对外接收**工作区根**（`currentWorkspaceRoot()`），仅作为「默认命名空间名」的来源
 * （`project:<工作区目录名>`）。内核本体与数据根均位于 App 内部存储的 `filesDir/mingmem/` 下，
 * 由 [MemoryKernel.ensureLibrary] 统一铺设与定位：页面与调用方无需关心，也不传入库路径。
 */
object MemoryAdmin {

    private const val TAG = "mem-admin"

    /** 概览 + 体检。进页面时调一次，也是其余动作的公共前置。 */
    suspend fun refresh(context: Context, workspaceRoot: File): MemoryState = snapshot(context, workspaceRoot)

    /**
     * 检索记忆。空检索词不发送至 CLI：内核会以退出码 2 报「--query 必填」，
     * 将本地即可判定的空输入转变为一次往返，造成无谓等待。
     */
    suspend fun search(context: Context, workspaceRoot: File, query: String): MemoryState {
        val text = query.trim()
        if (text.isEmpty()) {
            return snapshot(context, workspaceRoot).copy(query = "", briefing = "", hits = emptyList())
        }
        val base = snapshot(context, workspaceRoot)
        // --dry-run：设置页的「检索」为只读预览，不带该参数时内核会额外写入一条 access 事件（访问强化），
        // 每次检索都会提高 storage 权重，导致检索强度被设置页的预览操作悄然改变。
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
     * 维护扫描。**必须带 --dry-run**：该按钮仅报告数量，不写入库。
     *
     * 真正写入事件的 cycle（衰减 + 归档 + sweep 事件）保留在对话中由模型显式执行；
     * 用户在设置页点击即改变强度，等于绕过「不可逆动作的最小充分核验」这一约束。
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
     * 界面可以指定目录：在「记忆 → 导出」中填写绝对路径，此处即带上 `--out=<路径>`。
     * 为什么必须由用户指定：固定落点位于 App 私有目录（/sdcard/Android/data/<包名>/files/…），
     * 文件管理器无法进入，导出后无法取得文件。
     *
     * 内核默认排除 private / ask，此处也不添加 --include-*：设置页不应成为绕过披露等级的途径。
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
     * 为什么每个动作都先执行这一遍：state 为整体替换，仅填充自身部分会使其余部分退回默认值；
     * 而 doctorOk 默认 true，等同于「未执行体检却显示通过」，此类伪造结果不得出现。
     * 两次 CLI 均为只读命令（doctor 只读；status 最多重建可丢弃的投影），不会修改事件流。
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
            // 无法获取任何 JSON 时才判定体检本身无法执行：显示异常，并将内核原文写入 error
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
     * 注意 doctor 在「发现问题（坏行 / 投影损坏）」时退出码为 1，MemoryKernel 会依据退出码将结果视为失败，
     * 但 JSON 已完整包含在原文中（前面增加一行「第 4 步…退出码 1」）。因此此处统一从文本中提取 JSON：
     * 能够提取即为「体检未通过」，无法提取才是「体检无法执行」：两种情况不得合并为一条报错。
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
     * 容忍前缀是为了兼容 doctor 这类「JSON 前附带一句中文原因」的输出；
     * 优先整体解析，是因为正文本身可能包含花括号（引用的代码片段），不应直接截取。
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
