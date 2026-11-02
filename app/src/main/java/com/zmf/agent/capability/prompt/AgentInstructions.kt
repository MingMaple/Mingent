package com.zmf.agent.capability.prompt

import com.zmf.agent.core.log.FileLog
import java.io.File

/**
 * 工作区根目录里的「指令文件」（AGENTS.md / CLAUDE.md 系列）的读取。
 *
 * 为什么单开一个模块：它既不是系统提示词（PromptBuilder 里那套固定的身份 / 环境 / 权限段），
 * 也不是 Skill（按需取正文）：它是用户在目录里写的约定，必须每次请求前现读，
 * 用户修改文件后，下一轮请求即生效，无需重启会话。
 *
 * 只在工作区根查找，不向父目录查找：手机环境不存在多级 checkout 的目录结构，
 * 向父目录查找只会引入用户 home 中无关的文件。
 */
object AgentInstructions {

    /**
     * 一次读到的东西。
     *
     * [displayPath] 是给模型看的相对名字（多文件用逗号拼接），
     * [hash] 仅用于判断「与上次注入的是否为同一份」，不展示给模型。
     */
    data class Payload(val text: String, val displayPath: String, val hash: String)

    private const val TAG = "instructions"

    /** 优先级从高到低：预算不够先丢尾部，所以这个顺序就是优先级。 */
    private val CANDIDATES = listOf("AGENTS.md", "CLAUDE.md", "AGENTS.local.md", "CLAUDE.local.md")

    /** 合计预算 64KiB：再大就把真正的工作内容挤出上下文，而且每轮都重复计费。 */
    const val BUDGET_BYTES = 65536

    /** 单文件最多读 1MiB：即使误放巨型文件也不会拖垮读盘（后续仍会截断至 64KiB）。 */
    private const val MAX_READ_BYTES = 1024 * 1024

    /** 多份文件之间的分隔；两文件之间空一行，避免模型将两段约定连读。 */
    private const val SEPARATOR = "\n\n"

    /** 候选文件 + 已经读进来的正文。 */
    private class Entry(val name: String, val file: File, val text: String)

    /**
     * 读工作区根下的指令文件。
     *
     * 返回 null = 一个都没有（或全都读失败）：调用方什么都不注入，行为与没有这个功能时一致。
     */
    fun load(workspaceRoot: File): Payload? {
        val entries = readAll(workspaceRoot)
        if (entries.isEmpty()) return null

        // 超预算时优先丢弃尾部文件：高优先级的 AGENTS.md 不得为低优先级的本地覆盖文件让出预算
        while (entries.size > 1 && joinedBytes(entries) > BUDGET_BYTES) {
            val dropped = entries.removeAt(entries.size - 1)
            FileLog.log(
                TAG,
                "超出 " + BUDGET_BYTES + " 字节预算，跳过 " + dropped.name +
                    "（" + dropped.text.toByteArray(Charsets.UTF_8).size + " 字节）"
            )
        }
        // 执行至此仍超预算，说明剩余的最后一份文件自身即超限：二分截断并保留一行标记
        if (joinedBytes(entries) > BUDGET_BYTES) {
            val last = entries[0]
            entries[0] = Entry(last.name, last.file, truncate(last, BUDGET_BYTES))
        }

        val text = entries.joinToString(SEPARATOR) { it.text }
        val displayPath = entries.joinToString(", ") { it.name }
        return Payload(text = text, displayPath = displayPath, hash = hashOf(entries))
    }

    /** 按优先级读取候选文件；不存在的跳过，读取失败或内容全空白也跳过（注入空提醒只会形成噪声）。 */
    private fun readAll(workspaceRoot: File): ArrayList<Entry> {
        val out = ArrayList<Entry>(CANDIDATES.size)
        for (name in CANDIDATES) {
            val file = File(workspaceRoot, name)
            if (!file.isFile) continue
            val raw = try {
                readLimited(file)
            } catch (e: Exception) {
                FileLog.log(TAG, "读 " + name + " 失败：" + (e.message ?: e.javaClass.simpleName))
                continue
            }
            // 统一为 \n：这些文件通常在电脑上编辑后传入手机，CRLF 会额外占用预算
            val text = raw.replace("\r\n", "\n")
            if (text.isBlank()) continue
            out.add(Entry(name, file, text))
        }
        return out
    }

    /** 读单文件，最多 MAX_READ_BYTES；超长时末尾可能截断在半个 UTF-8 字符处，替换为替换符不影响正确性。 */
    private fun readLimited(file: File): String = file.inputStream().use { input ->
        // 按实际长度开缓冲区：小文件不必为 1MiB 上限白分配内存
        val buffer = ByteArray(minOf(file.length(), MAX_READ_BYTES.toLong()).toInt().coerceAtLeast(1))
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        String(buffer, 0, read, Charsets.UTF_8)
    }

    /** 拼起来之后的字节数（含分隔符），预算按真实投给模型的字节算。 */
    private fun joinedBytes(entries: List<Entry>): Int {
        var total = 0
        for (e in entries) total += e.text.toByteArray(Charsets.UTF_8).size
        if (entries.size > 1) total += SEPARATOR.length * (entries.size - 1)
        return total
    }

    /**
     * 把单份文件截到 [budget] 字节，并追加一行说明：模型看到标记即可判断内容不完整，
     * 不会将「约定只写到一半」当作全文执行。
     *
     * 用二分查找最大的可保留前缀：标记中的 to 值随截断长度变化，无法一次算出。
     */
    private fun truncate(entry: Entry, budget: Int): String {
        val text = entry.text
        val total = text.toByteArray(Charsets.UTF_8).size
        var lo = 0
        var hi = text.length
        var best = ""
        var bestBytes = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val head = headOf(text, mid)
            val headBytes = head.toByteArray(Charsets.UTF_8).size
            val mark = marker(entry.name, total, headBytes)
            val used = headBytes + 1 + mark.toByteArray(Charsets.UTF_8).size
            if (used <= budget) {
                best = head + "\n" + mark
                bestBytes = headBytes
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        FileLog.log(TAG, "截断 " + entry.name + "：" + total + " → " + bestBytes + " 字节（预算 " + budget + " 字节）")
        return best
    }

    /** 截断标记；文案固定，方便按日志 / 界面排查到底截了多少。 */
    private fun marker(name: String, from: Int, to: Int): String =
        "Workspace instruction budget " + BUDGET_BYTES + " bytes: truncated " + name +
            " from " + from + " to " + to + " bytes"

    /** 取前 [len] 个字符，且不把代理对切成半个（切了就变成替换符，模型读到的是乱码）。 */
    private fun headOf(text: String, len: Int): String {
        var end = len.coerceIn(0, text.length)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate()) end--
        return if (end >= text.length) text else text.substring(0, end)
    }

    /**
     * 变化判定用的指纹：各文件「名字 + mtime + size」拼起来取 hashCode。
     *
     * 为什么不使用正文计算：正文可能达数十 KB，每轮请求重复计算将造成浪费；
     * mtime 与 size 发生变化即足以判定「用户修改过文件」，修改后立即重新注入即可。
     */
    private fun hashOf(entries: List<Entry>): String {
        val sb = StringBuilder()
        for (e in entries) {
            sb.append(e.name).append(':').append(e.file.lastModified()).append(':').append(e.file.length()).append('|')
        }
        return sb.toString().hashCode().toString()
    }
}
