package com.zmf.agent.feature.chat

import org.json.JSONObject

/**
 * 提问工具名：过程组里那条提问只认这一个工具，展开详情的解析也随之限定。
 */
internal const val ASK_TOOL_NAME = "ask_user_question"

/**
 * 提问条目展开后的正文：题面、选项、答复与答复状态平铺成一段文字。
 *
 * 数据仍来自两处：assistant 消息里的 `toolCalls`（题面与选项）与对应 tool 消息的结果 JSON（作答）。
 * 三种状态都要如实写出——
 * - `已答复`：该题有选中项或自填内容；
 * - `未答复`：结果里没有这一题的作答（含尚未结算的调用）；
 * - `已跳过`：整次提问被关闭或超时（结果里是 `cancelled` / `timed_out`）。
 *
 * 参数解析失败返回 null：调用方退回工具卡原本的参数与输出两段，宁可难看也不画错。
 * 结果不是 JSON（例如工具直接返回错误文案）时按「未答复」处理，不臆造答案。
 */
internal fun askDetailText(arguments: String, result: String?): String? {
    val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    val questions = args.optJSONArray("questions") ?: return null
    val outcome = result?.let { runCatching { JSONObject(it) }.getOrNull() }
    // 超时与叉号都算「没有作答」：前者结果里是 timed_out，后者是 cancelled
    val skipped = outcome != null &&
        (outcome.optBoolean("cancelled", false) || outcome.optBoolean("timed_out", false))
    val answers = outcome?.optJSONArray("answers")
    val byId = HashMap<String, Pair<List<String>, String>>()
    if (answers != null) {
        for (i in 0 until answers.length()) {
            val item = answers.optJSONObject(i) ?: continue
            val picked = ArrayList<String>()
            val selected = item.optJSONArray("selected")
            if (selected != null) {
                for (k in 0 until selected.length()) {
                    selected.optString(k, "").takeIf { it.isNotBlank() }?.let { picked.add(it) }
                }
            }
            byId[item.optString("id", "")] = picked to item.optString("custom", "").trim()
        }
    }
    val blocks = ArrayList<String>(questions.length())
    for (i in 0 until questions.length()) {
        val item = questions.optJSONObject(i) ?: continue
        val answer = byId[item.optString("id", "")]
        // 作答值＝选中的选项 + 自填文本（自填在后），空项一律不计
        val values = answer?.first.orEmpty() + listOfNotNull(answer?.second?.takeIf { it.isNotEmpty() })
        val status = when {
            skipped -> "已跳过"
            values.isNotEmpty() -> "已答复"
            else -> "未答复"
        }
        blocks.add(
            buildString {
                append("问题：").append(item.optString("question", "").trim().ifEmpty { "（未提供题面）" })
                val options = item.optJSONArray("options")
                if (options != null && options.length() > 0) {
                    val labels = ArrayList<String>(options.length())
                    for (k in 0 until options.length()) {
                        options.optJSONObject(k)?.optString("label", "")?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { labels.add(it) }
                    }
                    if (labels.isNotEmpty()) append("\n选项：").append(labels.joinToString("、"))
                }
                if (values.isNotEmpty()) append("\n答复：").append(values.joinToString("、"))
                append("\n状态：").append(status)
            }
        )
    }
    if (blocks.isEmpty()) return null
    return blocks.joinToString("\n\n")
}
