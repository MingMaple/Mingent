package com.zmf.agent.capability.tools.interaction.notify_user

import com.zmf.agent.capability.port.NotifyCategory
import com.zmf.agent.capability.port.ToolEnv
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.AppForeground
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 向用户发送系统通知。
 *
 * 通知只用于应用不在前台时提醒用户，不承载对话正文，也不代表任务已经完成。
 * 前台抑制、权限、去重与频控均在本工具内判定，回执据此说明未送达的具体原因。
 */
class NotifyUserTool : Tool {

    override val name = "notify_user"

    override val description =
        "在应用不在前台时向用户发送一条系统通知，仅作提醒。" +
            "不承载对话正文，也不表示任务完成；需要用户当场作答改用 ask_user_question。" +
            "同一去重键、同一会话各 10 秒 1 条，全局每分钟 20 条；前台默认跳过；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "title": {"type": "string", "description": "通知标题，不超过 50 字；必填，无默认值"},
            "text": {"type": "string", "description": "通知正文，不超过 500 字，超出部分截断；必填，无默认值"},
            "category": {"type": "string", "enum": ["info", "done", "attention"], "description": "通知类别，取 info（一般提醒，默认）、done（已完成）、attention（需处理）"},
            "session_id": {"type": "string", "description": "目标会话 id；省略时使用当前会话"},
            "dedupe_key": {"type": "string", "description": "去重键，不超过 64 字符，10 秒内同一键只发送一次；默认空"},
            "only_when_background": {"type": "boolean", "description": "为 true 时应用在前台跳过发送；默认 true"}
          },
          "required": ["title", "text"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val title = args.optString("title", "").trim().take(MAX_TITLE_CHARS)
            if (title.isEmpty()) {
                return@withContext ToolResult.Error("[E_INVALID] title 不能为空：请填写通知标题后重试。")
            }
            val text = args.optString("text", "").trim().take(MAX_TEXT_CHARS)
            if (text.isEmpty()) {
                return@withContext ToolResult.Error("[E_INVALID] text 不能为空：请填写通知正文后重试。")
            }
            val category = categoryOf(args.optString("category", "info"))
            val notifier = ToolEnv.services().notifier
                ?: return@withContext ToolResult.Error(
                    "[E_SERVICE_UNAVAILABLE] 通知服务尚未装配，无法发送；此项不能通过调整参数解决。"
                )
            val sessionId = args.optString("session_id", "").trim().ifEmpty { ctx.sessionId }
            if (sessionId.isEmpty() || SessionStore.load(ctx.context).none { it.id == sessionId }) {
                return@withContext ToolResult.Error(
                    "[E_SESSION_NOT_FOUND] 未找到会话 " + sessionId.ifEmpty { "（当前无会话）" } +
                        "；请确认 session_id，或省略以使用当前会话。"
                )
            }
            val onlyWhenBackground = args.optBoolean("only_when_background", true)
            val dedupeKey = args.optString("dedupe_key", "").trim().take(MAX_DEDUPE_CHARS)

            if (onlyWhenBackground && AppForeground.isForeground) {
                return@withContext rejected("foreground")
            }
            if (!notifier.allowed()) return@withContext rejected("denied")
            when (NotifyGate.tryAcquire(sessionId, dedupeKey)) {
                NotifyGate.Result.DEDUPED -> return@withContext rejected("deduped")
                NotifyGate.Result.RATE_LIMITED -> return@withContext rejected("rate_limited")
                NotifyGate.Result.PASS -> Unit
            }

            val outcome = runCatching {
                notifier.notify(prefixOf(category) + title, text, sessionId, category)
            }.getOrElse {
                return@withContext ToolResult.Error(
                    "[E_NOTIFY_FAILED] 通知发送失败：" + (it.message ?: it.javaClass.simpleName) + "；请稍后重试。"
                )
            }
            if (!outcome.delivered) {
                return@withContext rejected(outcome.reason.ifEmpty { "failed" })
            }
            ToolResult.Ok(
                JSONObject()
                    .put("delivered", true)
                    .put("reason", "posted")
                    .put("id", outcome.id)
                    .toString()
            )
        }

    private companion object {
        const val MAX_TITLE_CHARS = 50
        const val MAX_TEXT_CHARS = 500
        const val MAX_DEDUPE_CHARS = 64

        /** 类别只决定标题前缀：需处理与已完成必须让用户在通知栏直接看出轻重。 */
        fun prefixOf(category: NotifyCategory): String = when (category) {
            NotifyCategory.ATTENTION -> "需处理："
            NotifyCategory.DONE -> "已完成："
            NotifyCategory.INFO -> ""
        }

        fun categoryOf(value: String): NotifyCategory = when (value.trim().lowercase()) {
            "attention" -> NotifyCategory.ATTENTION
            "done" -> NotifyCategory.DONE
            else -> NotifyCategory.INFO
        }

        /** 未送达回执：原因取固定标识，便于模型区分「前台抑制」与「权限、频控、去重」。 */
        fun rejected(reason: String): ToolResult =
            ToolResult.Ok(
                JSONObject()
                    .put("delivered", false)
                    .put("reason", reason)
                    .toString()
            )
    }
}

/**
 * 去重与频控闸门。
 *
 * 三项约束在同一临界区内判定并登记：同键 10 秒 1 条、每会话 10 秒 1 条、全局每分钟 20 条。
 * 先判去重再判频控，同一键的重复调用才会得到 deduped 而不是 rate_limited。
 * 状态只驻留内存：进程重启后重新计数，通知本身不承担持久化职责。
 */
private object NotifyGate {

    private const val DEDUPE_WINDOW_MS = 10_000L
    private const val SESSION_INTERVAL_MS = 10_000L
    private const val GLOBAL_WINDOW_MS = 60_000L
    private const val GLOBAL_LIMIT = 20

    enum class Result { PASS, DEDUPED, RATE_LIMITED }

    private val dedupeAt = HashMap<String, Long>()
    private val sessionAt = HashMap<String, Long>()
    private val globalAt = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(sessionId: String, dedupeKey: String): Result {
        val now = System.currentTimeMillis()
        prune(now)

        if (dedupeKey.isNotEmpty()) {
            val last = dedupeAt[dedupeKey]
            if (last != null && now - last < DEDUPE_WINDOW_MS) return Result.DEDUPED
        }
        val lastInSession = sessionAt[sessionId]
        if (lastInSession != null && now - lastInSession < SESSION_INTERVAL_MS) {
            return Result.RATE_LIMITED
        }
        if (globalAt.size >= GLOBAL_LIMIT) return Result.RATE_LIMITED

        globalAt.addLast(now)
        sessionAt[sessionId] = now
        if (dedupeKey.isNotEmpty()) dedupeAt[dedupeKey] = now
        return Result.PASS
    }

    /** 清理超出窗口的登记项，避免长时间运行后集合无界增长。 */
    private fun prune(now: Long) {
        while (globalAt.isNotEmpty() && now - globalAt.first() >= GLOBAL_WINDOW_MS) {
            globalAt.removeFirst()
        }
        val dedupe = dedupeAt.entries.iterator()
        while (dedupe.hasNext()) {
            if (now - dedupe.next().value >= DEDUPE_WINDOW_MS) dedupe.remove()
        }
        val sessions = sessionAt.entries.iterator()
        while (sessions.hasNext()) {
            if (now - sessions.next().value >= SESSION_INTERVAL_MS) sessions.remove()
        }
    }
}
