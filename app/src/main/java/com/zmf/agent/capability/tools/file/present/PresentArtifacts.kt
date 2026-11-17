package com.zmf.agent.capability.tools.file.present

import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.FileKinds
import com.zmf.agent.core.model.ProducedArtifact
import org.json.JSONObject

/**
 * 从 present 调用中读出文件路径。
 *
 * 参数为 `files: [{path, description?}]`；兼容旧参数 `paths`（字符串数组）。
 * 返回 null 表示参数结构不合法：调用方按失败处理，不做猜测性兜底。
 */
internal fun presentPaths(args: JSONObject): List<String>? {
    val array = args.optJSONArray("files")
    if (array != null) {
        if (array.length() == 0) return null
        val paths = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val path = if (item != null) {
                item.optString("path", "")
            } else {
                array.optString(index, "")
            }.trim()
            if (path.isEmpty()) return null
            paths.add(path)
        }
        return paths
    }
    val legacy = args.optJSONArray("paths") ?: return null
    if (legacy.length() == 0) return null
    val paths = ArrayList<String>(legacy.length())
    for (index in 0 until legacy.length()) {
        val path = legacy.optString(index, "").trim()
        if (path.isEmpty()) return null
        paths.add(path)
    }
    return paths
}

/**
 * 回合产物提取：只认本回合内**执行成功**的 present 调用。
 *
 * present 是唯一的产物声明入口：文件工具写了多少文件都不参与判定，
 * 没有调用 present 的回合不产生任何产物标记。
 */
object PresentArtifacts {

    const val PRESENT_TOOL = "present"

    /** 产物来源标识；与 [ProducedArtifact.source] 的取值口径一致。 */
    const val SOURCE = "present"

    /** [messages] 必须是本回合新增的消息（含 assistant 与 tool）。 */
    fun collect(messages: List<ChatMessage>): List<ProducedArtifact> {
        val okCalls = HashSet<String>()
        for (message in messages) {
            if (message.role == "tool" && message.toolOk == true) {
                message.toolCallId?.takeIf { it.isNotEmpty() }?.let { okCalls.add(it) }
            }
        }
        if (okCalls.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, ProducedArtifact>()
        for (message in messages) {
            if (message.role != "assistant") continue
            for (call in message.toolCalls) {
                if (call.name != PRESENT_TOOL || call.id !in okCalls) continue
                val args = runCatching { JSONObject(call.arguments) }.getOrNull() ?: continue
                for (raw in presentPaths(args).orEmpty()) {
                    val path = raw.trim()
                    if (path.isEmpty()) continue
                    out.getOrPut(path) {
                        ProducedArtifact(path, FileKinds.of(path).name.lowercase(), SOURCE)
                    }
                }
            }
        }
        return out.values.toList()
    }
}
