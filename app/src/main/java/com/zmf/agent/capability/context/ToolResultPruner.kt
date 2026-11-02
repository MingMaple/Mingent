package com.zmf.agent.capability.context

import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage

/**
 * 工具输出中段折叠（只作用于请求视图，不改落盘与界面）。
 *
 * **作用**：一次工具调用返回数万字符后，后续每轮请求都会重发这段内容。
 * 在确认存在压缩压力后，将超过阈值的工具结果替换为「头 4096 + 一行折叠说明 + 尾 1024」，
 * 中段整体丢弃（原文仍保留在会话日志中，可回放查证）。
 *
 * **为何仅在压缩之后折叠**：折叠是有损操作。未达压力线时应尽量保留原文；
 * 达到压力线后，保留全部内容反而会挤占真正需要的信息。
 *
 * **为何不修改 messages 与界面**：折叠只作用于请求视图，界面仍显示完整输出，
 * 用户回滚、复制、重新生成时使用的均为原文。同一输入的折叠结果确定，
 * 因此逐轮拼出的字节一致，不影响前缀缓存。
 *
 * 默认值：阈值 8192 / 头 4096 / 尾 1024（Unicode 码点，不是字节，也不是 UTF-16 单元）。
 */
object ToolResultPruner {

    /** 超过该码点数才折叠：阈值以下的折叠无法节省可观的 token。 */
    const val THRESHOLD_CHARS = 8192

    /** 保留下来的开头码点数：约占一半，足够模型认出这段输出是什么。 */
    const val HEAD_CHARS = 4096

    /** 保留下来的结尾码点数：尾部通常带着结论或错误，比中段更有用。 */
    const val TAIL_CHARS = 1024

    private const val TAG = "prune"

    /**
     * 折叠一条正文：未超阈值返回 null（表示无需修改），超过则返回折叠后的正文。
     *
     * 按**码点**切分，避免代理对被截断成半个字符。
     * 中段说明中包含省略字符数，使模型能识别此处有内容被省略，而不会把接缝当作原文。
     */
    fun pruneText(text: String): String? {
        val points = text.codePoints().toArray()
        if (points.size <= THRESHOLD_CHARS) return null
        val tailStart = points.size - TAIL_CHARS
        val omitted = tailStart - HEAD_CHARS
        val marker = "\n\n[... 工具输出中段已折叠：省略 " + omitted +
            " 字符；要细节请重新读取（read_file 或重跑命令）...]\n\n"
        return String(points, 0, HEAD_CHARS) + marker + String(points, tailStart, TAIL_CHARS)
    }

    /** 折叠一条消息：仅处理 tool 角色，其余原样返回（同一对象引用，便于调用方比较是否变化）。 */
    fun pruneMessage(message: ChatMessage): ChatMessage {
        if (message.role != "tool") return message
        val text = pruneText(message.content) ?: return message
        return message.copy(content = text)
    }

    /**
     * 折叠一批消息，返回**新的**列表；无需折叠时返回**原列表本身**（调用方可用引用比较判断）。
     */
    fun prune(messages: List<ChatMessage>): List<ChatMessage> {
        var changed = false
        var count = 0
        var saved = 0L
        val out = ArrayList<ChatMessage>(messages.size)
        for (message in messages) {
            val pruned = pruneMessage(message)
            if (pruned === message) {
                out.add(message)
                continue
            }
            saved += message.content.length - pruned.content.length
            count++
            changed = true
            out.add(pruned)
        }
        if (!changed) return messages
        FileLog.log(
            TAG,
            "折叠 " + count + " 条工具输出中段，省 " + saved + " 字符（阈值 " + THRESHOLD_CHARS +
                "，保留头 " + HEAD_CHARS + " / 尾 " + TAIL_CHARS + " 码点）"
        )
        return out
    }
}
