package com.zmf.agent.capability.tools.session

import android.content.Context
import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.storage.StorageReclaimer
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.atLeast
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.SessionPreference
import com.zmf.agent.core.model.permissionLevelOfWire
import com.zmf.agent.core.model.wireName
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 会话工具集：列出、新建、改名、移动、归档、恢复、删除、改偏好。
 *
 * 三条行为约定（与设计稿一致）：
 * 1. 先读后写：涉及 id 的操作先 `session_list`，不猜 id；
 * 2. 破坏性操作先复述：删除会话、移动会话要先说明目标与后果；
 * 3. 删除只对已归档会话开放，未归档的先归档——与界面入口保持同一套秩序。
 *
 * 每次写操作之后都通过 [ChatBridge] 通知界面重读，避免工具改完而列表仍是旧快照。
 */
private const val LIST_LIMIT = 20

/** 会话的一行摘要：标题、id、状态。 */
private fun sessionLine(session: ChatSession): String = buildString {
    append("- ").append(session.title.ifBlank { "新会话" })
    append("（id=").append(session.id).append("）")
    if (session.archived) append("；已归档")
    if (session.blank) append("；空白会话")
    if (!session.listed) append("；不在列表显示")
    append("；更新于 ").append(SessionStore.relativeTime(session.updatedAt))
}

private fun workspaceOf(context: Context, id: String?): WorkSpace? =
    WorkSpaceStore.load(context).firstOrNull { it.id == id }

/** 列出会话；默认隐藏空白会话与已归档会话。 */
class SessionListTool : Tool {

    override val name = "session_list"

    override val description =
        "列出会话：id、标题、是否归档、是否空白。默认隐藏空白会话与已归档会话。" +
            "可按工作区过滤；需要归档会话时把 includeArchived 设为 true。默认最多返回 20 条。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "workspaceId": {"type": "string", "description": "可选：只列该工作区的会话"},
            "includeArchived": {"type": "boolean", "description": "是否包含已归档会话，默认 false"}
          },
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val workspaceId = args.optString("workspaceId", "").trim().takeIf { it.isNotEmpty() }
        val includeArchived = args.optBoolean("includeArchived", false)
        val all = SessionStore.load(ctx.context).sortedByDescending { it.updatedAt }
        val filtered = all.filter { session ->
            (includeArchived || !session.archived) &&
                session.listed &&
                (workspaceId == null || session.workspaceId == workspaceId)
        }
        if (filtered.isEmpty()) return@withContext ToolResult.Ok("没有符合条件的会话")
        val shown = filtered.take(LIST_LIMIT)
        val text = buildString {
            append("会话 ").append(filtered.size).append(" 条")
            if (filtered.size > shown.size) append("（仅列前 ").append(shown.size).append(" 条）")
            append("：\n")
            shown.forEach { append(sessionLine(it)).append("\n") }
        }
        ToolResult.Ok(text.trimEnd())
    }
}

/** 新建会话并直接转正；该工作区已有空白会话时就地复用。 */
class SessionCreateTool : Tool {

    override val name = "session_create"

    override val description =
        "在工作区里新建一条会话，并立即转为正式会话（不出现在列表之外的隐藏态）。" +
            "该工作区若已有空白会话，会复用那一条而不是继续新建，避免列表堆满空会话。" +
            "权限与思考强度不传时沿用全局默认值。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "workspaceId": {"type": "string", "description": "工作区 id；省略表示默认工作区"},
            "title": {"type": "string", "description": "可选：会话标题"},
            "permission": {"type": "string", "enum": ["read-only", "workspace-write", "danger-full-access"]},
            "reasoningEffort": {"type": "string", "enum": ["off", "low", "high", "max"]}
          },
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val defaultSpace = WorkSpaceStore.defaultSpace(ctx.context)
        val workspaceId = args.optString("workspaceId", "").trim().takeIf { it.isNotEmpty() }
            ?: defaultSpace.id
        val space = workspaceOf(ctx.context, workspaceId)
            ?: return@withContext ToolResult.Error("没有这个工作区：" + workspaceId)

        val permission = args.optString("permission", "").trim().takeIf { it.isNotEmpty() }
            ?.let { raw -> permissionLevelOfWire(raw) ?: return@withContext ToolResult.Error("permission 取值不对：" + raw) }
            ?: PermissionLevel.WORKSPACE
        // 不能借新建会话把自己的权限档抬上去：否则工作区档调一次工具就能拿到完全档，闸门形同虚设
        if (!ctx.permission.atLeast(permission)) {
            return@withContext ToolResult.Error(
                "不能把新会话的权限设得比当前更高（当前「" + ctx.permission.displayName() + "」）；" +
                    "需要更高权限请让用户在会话设置里调整"
            )
        }
        val effort = args.optString("reasoningEffort", "").trim().takeIf { it.isNotEmpty() }
            ?.let { raw -> SessionPreference.effortOrNull(raw) ?: return@withContext ToolResult.Error("reasoningEffort 取值不对：" + raw) }
            ?: SessionPreference.EFFORT_DEFAULT

        val all = SessionStore.load(ctx.context)
        val blank = all.firstOrNull { it.blank && !it.archived && it.workspaceId == space.id }
        if (blank != null) {
            val promoted = blank.copy(
                blank = false,
                listed = true,
                storageId = space.storageId,
                permissionPreset = permission.wireName(),
                reasoningEffort = effort,
                updatedAt = System.currentTimeMillis()
            )
            SessionStore.save(ctx.context, all.map { if (it.id == blank.id) promoted else it })
            ChatBridge.notifySessionsChanged()
            return@withContext ToolResult.Ok("已复用该工作区的空白会话（id=" + promoted.id + "）")
        }

        val created = SessionStore.newSession(
            storageId = space.storageId,
            listed = true,
            permissionPreset = permission.wireName(),
            reasoningEffort = effort
        ).copy(
            workspaceId = space.id,
            title = args.optString("title", "").trim()
        )
        SessionStore.save(ctx.context, listOf(created) + all)
        ChatBridge.notifySessionsChanged()
        ToolResult.Ok("已新建会话（id=" + created.id + "），归属工作区「" + space.name + "」")
    }
}

/** 改标题。 */
class SessionRenameTool : Tool {

    override val name = "session_rename"

    override val description = "修改会话标题；标题为空会被拒绝。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "会话 id"},
            "title": {"type": "string", "description": "新标题"}
          },
          "required": ["id", "title"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val title = args.optString("title", "").trim()
        if (title.isEmpty()) return@withContext ToolResult.Error("title 不能为空")
        val all = SessionStore.load(ctx.context)
        if (all.none { it.id == id }) return@withContext ToolResult.Error("没有这个会话：" + id)
        SessionStore.save(ctx.context, all.map { if (it.id == id) it.copy(title = title) else it })
        ChatBridge.notifySessionsChanged()
        ToolResult.Ok("已改名为「" + title + "」")
    }
}

/** 移动会话：分组与运行根同时改。 */
class SessionMoveTool : Tool {

    override val name = "session_move"

    override val description =
        "把会话移到另一个工作区：分组与运行根（存储单元）一起改变，不移动任何文件。" +
            "原内置目录若因此失去全部引用，会按规则删除或移入回收站。" +
            "会话正在生成回复时拒绝；目标工作区不存在时拒绝。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "会话 id"},
            "workspaceId": {"type": "string", "description": "目标工作区 id"}
          },
          "required": ["id", "workspaceId"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val workspaceId = args.optString("workspaceId", "").trim()
        val space = workspaceOf(ctx.context, workspaceId)
            ?: return@withContext ToolResult.Error("没有这个工作区：" + workspaceId)
        val spaces = WorkSpaceStore.load(ctx.context)
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("没有这个会话：" + id)
        if (target.workspaceId == workspaceId && target.storageId == space.storageId) {
            return@withContext ToolResult.Ok("会话已在该工作区")
        }
        val oldStorage = target.storageId
        val moved = all.map {
            if (it.id == id) {
                it.copy(workspaceId = workspaceId, storageId = space.storageId, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        SessionStore.save(ctx.context, moved)
        val outcome = StorageReclaimer.reclaim(ctx.context, oldStorage, space.name, spaces, moved)
        ChatBridge.notifySessionsChanged()
        val suffix = if (outcome.result == StorageReclaimer.Result.TRASHED) {
            "；原内置目录已无剩余引用，已移入回收站"
        } else {
            ""
        }
        ToolResult.Ok("已移动到「" + space.name + "」" + suffix)
    }
}

/** 归档会话；以它为目标的定时任务会一并停用解绑。 */
class SessionArchiveTool : Tool {

    override val name = "session_archive"

    override val description =
        "归档会话：从列表收起但数据保留，可随时恢复。归档后不再减少内置目录的引用。" +
            "若该会话是定时任务的目标，任务会自动停用并解绑（单向，恢复会话不会自动重绑）。" +
            "会话正在生成回复时拒绝。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {"id": {"type": "string", "description": "会话 id"}},
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("没有这个会话：" + id)
        if (target.archived) return@withContext ToolResult.Ok("该会话已经是归档状态")
        val detached = ScheduleBridge.detachSession(id)
        SessionStore.save(
            ctx.context,
            all.map {
                if (it.id == id) it.copy(archived = true, updatedAt = System.currentTimeMillis()) else it
            }
        )
        ChatBridge.notifySessionsChanged()
        val suffix = if (detached > 0) "；" + detached + " 个定时任务已停用并解绑" else ""
        ToolResult.Ok("已归档会话「" + target.title.ifBlank { "新会话" } + "」" + suffix)
    }
}

/** 从归档恢复。 */
class SessionRestoreTool : Tool {

    override val name = "session_restore"

    override val description =
        "把已归档的会话恢复到列表。原工作区已删除时归入「未分类」，运行根保持不变。" +
            "恢复不会自动重新绑定此前被解绑的定时任务。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {"id": {"type": "string", "description": "会话 id"}},
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val spaces = WorkSpaceStore.load(ctx.context).map { it.id }.toSet()
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("没有这个会话：" + id)
        if (!target.archived) return@withContext ToolResult.Ok("该会话未归档")
        val exists = target.workspaceId != null && target.workspaceId in spaces
        val workspaceId = if (exists) target.workspaceId else null
        SessionStore.save(
            ctx.context,
            all.map {
                if (it.id == id) {
                    it.copy(archived = false, workspaceId = workspaceId, updatedAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        )
        ChatBridge.notifySessionsChanged()
        val suffix = if (!exists && target.workspaceId != null) "；原工作区已删除，已归入「未分类」" else ""
        ToolResult.Ok("已恢复会话" + suffix)
    }
}

/** 永久删除会话：仅限已归档。 */
class SessionDeleteTool : Tool {

    override val name = "session_delete"

    override val description =
        "永久删除一条**已归档**会话：消息、计划、目标与上下文记录一并清除，不可恢复。" +
            "未归档的会话会被拒绝，请先归档。若它是某内置目录的最后一个引用，" +
            "该目录会按规则直接删除或移入回收站。调用前必须在对话里复述这一后果。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {"id": {"type": "string", "description": "会话 id"}},
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val spaces = WorkSpaceStore.load(ctx.context)
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("没有这个会话：" + id)
        if (!target.archived) {
            return@withContext ToolResult.Error("只能删除已归档会话：请先调用 session_archive")
        }
        val remaining = all.filterNot { it.id == id }
        SessionStore.save(ctx.context, remaining)
        val outcome = StorageReclaimer.reclaim(ctx.context, target.storageId, null, spaces, remaining)
        ChatBridge.notifySessionsChanged()
        val suffix = when (outcome.result) {
            StorageReclaimer.Result.TRASHED -> "；关联的内置目录已移入回收站，保留 30 天"
            StorageReclaimer.Result.DELETED_EMPTY -> "；关联的内置目录为空，已直接移除"
            else -> ""
        }
        ToolResult.Ok("已永久删除会话「" + target.title.ifBlank { "新会话" } + "」" + suffix)
    }
}

/** 改会话的权限与思考强度：只改目标会话，不动全局默认。 */
class SessionUpdateSettingsTool : Tool {

    override val name = "session_update_settings"

    override val description =
        "修改某条会话的权限档或思考强度。只改目标会话，不影响全局默认值，也不影响其他会话。" +
            "思考强度取 off 时不发送思考参数。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "会话 id"},
            "permission": {"type": "string", "enum": ["read-only", "workspace-write", "danger-full-access"]},
            "reasoningEffort": {"type": "string", "enum": ["off", "low", "high", "max"]}
          },
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val rawPermission = args.optString("permission", "").trim()
        val rawEffort = args.optString("reasoningEffort", "").trim()
        if (rawPermission.isEmpty() && rawEffort.isEmpty()) {
            return@withContext ToolResult.Error("permission 与 reasoningEffort 至少要给一个")
        }
        val permission = rawPermission.takeIf { it.isNotEmpty() }?.let {
            permissionLevelOfWire(it) ?: return@withContext ToolResult.Error("permission 取值不对：" + it)
        }
        // 只允许下调或保持不变：模型不能把自己的会话提到更高档，提权必须由用户在设置里完成
        if (permission != null && !ctx.permission.atLeast(permission)) {
            return@withContext ToolResult.Error(
                "不能把会话权限提到当前档位之上（当前「" + ctx.permission.displayName() + "」）；" +
                    "需要更高权限请让用户在会话设置里调整"
            )
        }
        val effort = rawEffort.takeIf { it.isNotEmpty() }?.let {
            SessionPreference.effortOrNull(it) ?: return@withContext ToolResult.Error("reasoningEffort 取值不对：" + it)
        }
        val all = SessionStore.load(ctx.context)
        if (all.none { it.id == id }) return@withContext ToolResult.Error("没有这个会话：" + id)
        SessionStore.save(
            ctx.context,
            all.map {
                if (it.id != id) it
                else it.copy(
                    permissionPreset = permission?.wireName() ?: it.permissionPreset,
                    reasoningEffort = effort ?: it.reasoningEffort
                )
            }
        )
        ChatBridge.notifySessionsChanged()
        val parts = ArrayList<String>(2)
        permission?.let { parts += "权限=" + it.wireName() }
        effort?.let { parts += "思考=" + it }
        ToolResult.Ok("已更新会话设置：" + parts.joinToString("，"))
    }
}
