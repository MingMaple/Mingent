package com.zmf.agent.agent.context

import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage

/**
 * 工具输出中段折叠（只作用于请求视图，不改落盘与界面）。
 *
 * **它解决什么**：一次工具调用吐 2 万字符，之后每一轮请求都要原样重发这 2 万字符，
 * 不是为了看它，只是因为它还在上下文里。做法是：压缩压力确认之后，
 * 把超过阈值的工具结果换成「头 4096 + 一行折叠说明 + 尾 1024」，
 * 中段整段丢掉（原文在会话日志里仍有，可回放查证）。
 *
 * **为什么只在压缩之后才折**：折叠是**有损**的。没到压力线时，宁可多花点 token 也把原文留全；
 * 到了压力线，留全反而会把真正要看的东西挤出去。
 *
 * **为什么不改 messages / 界面**：折叠只作用于**请求视图**。界面照旧显示完整输出，
 * 用户回滚、复制、重新生成拿到的都是原文。同一段内容的折叠形态是**恒定**的（同一输入同一输出），
 * 所以一旦折过，后面每轮拼出来的字节都一样，前缀缓存不会被它逐轮抖动。
 *
 * 默认值：阈值 8192 / 头 4096 / 尾 1024（Unicode 码点，不是字节，也不是 UTF-16 单元）。
 */
object ToolResultPruner {

    /** 超过这个码点数才折叠：阈值以下折了也省不下多少 token。 */
    const val THRESHOLD_CHARS = 8192

    /** 保留下来的开头码点数：约占一半，足够模型认出这段输出是什么。 */
    const val HEAD_CHARS = 4096

    /** 保留下来的结尾码点数：尾部通常带着结论或错误，比中段更有用。 */
    const val TAIL_CHARS = 1024

    private const val TAG = "prune"

    /**
     * 折一条正文：不超阈值返回 null（= 不用改），超了返回折叠后的正文。
     *
     * 按**码点**切，所以 emoji 之类的代理对不会被切成半个字符。
     * 中段那行说明里带省略的字符数：模型据此知道「这里少了一截」，而不是把接缝当成原文。
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

    /** 折一条消息：只折 tool 角色，其余原样返回（同一个对象引用，便于调用方判「变没变」）。 */
    fun pruneMessage(message: ChatMessage): ChatMessage {
        if (message.role != "tool") return message
        val text = pruneText(message.content) ?: return message
        return message.copy(content = text)
    }

    /**
     * 折一批消息，返回**新的**列表；一条都不用折时返回**原列表本身**（调用方可直接用引用比较判断）。
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
