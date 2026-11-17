package com.zmf.agent.feature.chat

import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.FileKind
import com.zmf.agent.core.model.FileKinds
import java.io.File

/** 本轮（或本会话）成功写出的文件；[path] 已解析成绝对路径。 */
data class ProducedFile(val path: String, val kind: FileKind) {
    val name: String get() = FileKinds.nameOf(path)
}

/**
 * 产物读取：只认消息上由 present 声明、且当时校验通过的记录。
 *
 * 不读模型正文、不扫描工作区、也不按工具参数推断；没有 present 的回合没有任何产物。
 */
object ProducedFiles {

    /** 整段消息里声明的产物，按出现顺序去重。 */
    fun of(messages: List<ChatMessage>, root: File): List<ProducedFile> = declared(messages, root)

    /** 一轮里的产物：[turn] 只含本轮的 assistant 消息。 */
    fun ofTurn(turn: List<ChatMessage>, root: File): List<ProducedFile> = declared(turn, root)

    /** 回合登记的产物：按绝对路径去重，先登记的记录优先。 */
    private fun declared(messages: List<ChatMessage>, root: File): List<ProducedFile> {
        val out = LinkedHashMap<String, ProducedFile>()
        for (message in messages) {
            for (item in message.produced) {
                val absolute = absoluteOf(root, item.path) ?: continue
                out.putIfAbsent(absolute, ProducedFile(absolute, FileKinds.of(item.path)))
            }
        }
        return out.values.toList()
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
}
