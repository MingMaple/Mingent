package com.zmf.agent.feature.chat

import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.FileKind
import com.zmf.agent.core.model.FileKinds
import com.zmf.agent.core.model.ToolCall
import org.json.JSONObject
import java.io.File

/** 本轮（或本会话）成功写出的文件；[path] 已解析成绝对路径。 */
data class ProducedFile(val path: String, val kind: FileKind) {
    val name: String get() = FileKinds.nameOf(path)
}

/**
 * 产物推导：只认 write_file 且工具结果成功的调用。
 *
 * 与 DSH 同一条原则——列出的依据是「已发生的事实」，不读模型正文、不猜路径；
 * 失败或用其他工具（read/delete）的调用不贡献条目。
 */
object ProducedFiles {

    private const val WRITE_TOOL = "write_file"

    /** 显式交付：terminal 造出来的 PDF / Office / 图片，靠它进入卡片。 */
    private const val PRESENT_TOOL = "present"

    /** 整段消息里成功写出的文件，按出现顺序去重。 */
    fun of(messages: List<ChatMessage>, root: File): List<ProducedFile> {
        val okCalls = HashSet<String>(messages.size)
        for (message in messages) {
            if (message.role == "tool" && message.toolOk == true) {
                message.toolCallId?.takeIf { it.isNotEmpty() }?.let { okCalls.add(it) }
            }
        }
        return collect(writeCalls(messages), okCalls, root)
    }

    /**
     * 一轮里的产物。
     *
     * [turn] 只含本轮的 assistant 消息（回合下标按设计不收工具消息），
     * 因此成败在 [all] 里按 toolCallId 回查；[liveOk] 是本进程的工具成败表，
     * 用于刚跑完、尚未从磁盘读回的那一瞬。
     */
    fun ofTurn(
        turn: List<ChatMessage>,
        all: List<ChatMessage>,
        root: File,
        liveOk: Map<String, Boolean> = emptyMap()
    ): List<ProducedFile> {
        val okCalls = HashSet<String>()
        for ((id, ok) in liveOk) if (ok) okCalls.add(id)
        for (message in all) {
            if (message.role == "tool" && message.toolOk == true) {
                message.toolCallId?.takeIf { it.isNotEmpty() }?.let { okCalls.add(it) }
            }
        }
        return collect(writeCalls(turn), okCalls, root)
    }

    /** 相对路径接在工作区根下，绝对路径原样（PRoot 内部路径由此变成「打不开」而不是猜一个位置）。 */
    fun absoluteOf(root: File, raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching {
            if (text.startsWith("/")) File(text).absolutePath else File(root, text).absolutePath
        }.getOrNull()
    }

    /**
     * 正文里的一段行内代码 → 产物文件。
     *
     * 只认三种命中：完整路径、写法的原样后缀、唯一的文件名。有歧义（两个同名文件）时返回 null，
     * 保持不可点——点错了文件比不能点更糟。
     */
    fun match(files: List<ProducedFile>, token: String): ProducedFile? {
        val text = token.trim()
        if (text.isEmpty()) return null
        files.firstOrNull { it.path == text }?.let { return it }
        val bySuffix = files.filter { it.path == text || it.path.endsWith("/" + text) }
        if (bySuffix.size == 1) return bySuffix.first()
        val name = FileKinds.nameOf(text)
        val byName = files.filter { it.name == name }
        return if (byName.size == 1) byName.first() else null
    }

    private fun writeCalls(messages: List<ChatMessage>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (message in messages) {
            if (message.role != "assistant") continue
            for (call in message.toolCalls) {
                when (call.name) {
                    WRITE_TOOL -> {
                        val raw = runCatching { JSONObject(call.arguments).optString("path", "") }.getOrNull()
                        if (!raw.isNullOrBlank()) out.add(call.id to raw)
                    }
                    PRESENT_TOOL -> {
                        // 显式交付：一份调用可以带多个路径，逐个计入
                        val array = runCatching { JSONObject(call.arguments).optJSONArray("paths") }.getOrNull()
                        if (array != null) {
                            for (index in 0 until array.length()) {
                                val raw = array.optString(index, "").trim()
                                if (raw.isNotEmpty()) out.add(call.id to raw)
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun collect(
        calls: List<Pair<String, String>>,
        okCalls: Set<String>,
        root: File
    ): List<ProducedFile> {
        if (calls.isEmpty() || okCalls.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, ProducedFile>()
        for ((callId, raw) in calls) {
            if (callId !in okCalls) continue
            val absolute = absoluteOf(root, raw) ?: continue
            out.getOrPut(absolute) { ProducedFile(absolute, FileKinds.of(raw)) }
        }
        return out.values.toList()
    }
}
