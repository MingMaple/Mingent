package com.zmf.agent.capability.tools.session.session_search

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.session.support.SearchHit
import com.zmf.agent.capability.tools.session.support.SearchScope
import com.zmf.agent.capability.tools.session.support.SessionIndexCache
import com.zmf.agent.capability.tools.session.support.SessionMatcher
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 跨会话检索：在会话标题与正文中查找关键词，返回带片段的命中列表。
 *
 * 只读工具。索引来自 SessionStore 的落盘快照，按 sessions.json 的修改时刻与长度缓存；
 * 单次检索最多扫描 [MAX_SCAN] 条会话，并在 [TIME_BUDGET_MS] 毫秒软预算用尽时提前结束，
 * 已得结果照常返回并标注 truncated。
 */
class SessionSearchTool : Tool {

    override val name = "session_search"

    override val description =
        "在已有会话的标题、正文与工作区名中检索关键词，返回命中列表与片段。" +
            "不提供分页，结果不足时收窄 query；列出会话改用 session_list。" +
            "多个检索词须全部命中，单次最多 100 条，空白会话不参与；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "检索词，去除首尾空白后不能为空，最多 200 字；多个词以空白分隔，全部命中才算匹配"},
            "scope": {
              "type": "string",
              "enum": ["all", "title", "content"],
              "description": "检索范围，取 all（标题、正文与工作区名）、title（仅标题）、content（仅正文）；默认 all"
            },
            "workspace_id": {"type": "string", "description": "只检索该工作区的会话 id，取自 workspace_list；省略时不限工作区"},
            "include_archived": {"type": "boolean", "description": "为 true 时包含已归档会话；默认 false"}
          },
          "required": ["query"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val query = args.optString("query", "").trim().take(MAX_QUERY_CHARS)
            if (query.isEmpty()) {
                return@withContext ToolResult.Error("[E_EMPTY_QUERY] query 不能为空，请填写检索词后重试。")
            }
            val scope = SearchScope.of(args.optString("scope", "all"))
            val workspaceId = args.optString("workspace_id", "").trim().takeIf { it.isNotEmpty() }
            val includeArchived = args.optBoolean("include_archived", false)
            // 旧参数 snippet_chars 仍接受，但结果上限固定为官方口径的 100 条，不暴露分页。
            val snippetChars = args.optInt("snippet_chars", DEFAULT_SNIPPET_CHARS)
                .coerceIn(MIN_SNIPPET_CHARS, MAX_SNIPPET_CHARS)

            val spaces = WorkSpaceStore.load(ctx.context)
            if (workspaceId != null && spaces.none { it.id == workspaceId }) {
                return@withContext ToolResult.Error(
                    "[E_WORKSPACE_NOT_FOUND] 没有这个工作区：" + workspaceId + "；请先用 workspace_list 核对。"
                )
            }
            val terms = SessionMatcher.splitTerms(query)
            if (terms.isEmpty()) {
                return@withContext ToolResult.Error("[E_EMPTY_QUERY] query 不能为空，请填写检索词后重试。")
            }
            val sessions = try {
                SessionIndexCache.load(ctx.context, spaces)
            } catch (e: Exception) {
                return@withContext ToolResult.Error(
                    "[E_INDEX_FAILED] 无法建立会话索引：" + (e.message ?: e.javaClass.simpleName) + "；请检查会话数据后重试。"
                )
            }

            val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
            val matched = ArrayList<SearchHit>()
            var scanned = 0
            var truncated = false
            for (session in sessions) {
                if (scanned >= MAX_SCAN) {
                    truncated = true
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    truncated = true
                    break
                }
                scanned++
                if (!includeArchived && session.archived) continue
                if (workspaceId != null && session.workspaceId != workspaceId) continue
                val hit = SessionMatcher.match(session, terms, scope, snippetChars) ?: continue
                matched.add(hit)
            }
            matched.sortWith(
                compareByDescending<SearchHit> { it.score }.thenByDescending { it.session.updatedAt }
            )

            if (matched.isEmpty()) return@withContext ToolResult.Ok("没有匹配的历史会话。")
            val shown = matched.take(MAX_RESULTS)
            val text = buildString {
                append("会话检索结果（").append(shown.size).append(" 条）：")
                shown.forEachIndexed { index, hit ->
                    append("\n\n").append(index + 1).append(". 会话 ").append(hit.session.id)
                    append(" — ").append(hit.session.title.ifBlank { "（无标题）" })
                    append("\n   工作区：").append(hit.session.workspaceName.ifBlank { "（未知）" })
                    append("\n   更新时间：").append(hit.session.updatedAt)
                    append("\n   最佳命中：").append(hit.role).append(" | 得分 ").append(hit.score)
                    append("\n   片段：").append(hit.snippet)
                }
                if (matched.size > shown.size || truncated) {
                    append("\n\n结果已达上限，请收窄检索词或增加过滤条件。")
                }
            }
            ToolResult.Ok(text)
        }

    private companion object {
        const val MAX_QUERY_CHARS = 200
        const val MAX_RESULTS = 100
        const val MIN_SNIPPET_CHARS = 60
        const val MAX_SNIPPET_CHARS = 400
        const val DEFAULT_SNIPPET_CHARS = 160

        /** 单次检索最多扫描的会话条数。 */
        const val MAX_SCAN = 200

        /** 检索的软时间预算：用尽后返回已得结果。 */
        const val TIME_BUDGET_MS = 2_000L
    }
}
