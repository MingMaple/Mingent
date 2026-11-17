package com.zmf.agent.capability.tools.session.session_create

import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.atLeast
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.capability.tools.session.support.workspaceOf
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.SessionPreference
import com.zmf.agent.core.model.permissionLevelOfWire
import com.zmf.agent.core.model.wireName
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 新建会话并直接转正；该工作区已有空白会话时就地复用。 */
class SessionCreateTool : Tool {

    override val name = "session_create"

    override val description =
        "在工作区中新建会话并转为正式会话，该工作区已有空白会话时就地复用。" +
            "只新建或复用会话，不复制其他会话的消息与设置。" +
            "权限省略时取 workspace-write 且不得高于当前档，思考强度省略时取 high；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "workspaceId": {"type": "string", "description": "工作区 id，取自 workspace_list；省略时使用默认工作区"},
            "title": {"type": "string", "description": "会话标题，可为空，空标题显示为「新会话」"},
            "permission": {
              "type": "string",
              "enum": ["read-only", "workspace-write", "danger-full-access"],
              "description": "新会话的权限档，取 read-only、workspace-write、danger-full-access；省略时取 workspace-write，且不得高于当前档"
            },
            "reasoningEffort": {
              "type": "string",
              "enum": ["off", "low", "high", "max"],
              "description": "思考强度，取 off、low、high、max；省略时取全局默认值 high"
            }
          },
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val defaultSpace = WorkSpaceStore.defaultSpace()
        val workspaceId = args.optString("workspaceId", "").trim().takeIf { it.isNotEmpty() }
            ?: defaultSpace.id
        val space = workspaceOf(ctx.context, workspaceId)
            ?: return@withContext ToolResult.Error(
                "[E_WORKSPACE_NOT_FOUND] 没有这个工作区：" + workspaceId + "；请先用 workspace_list 核对。"
            )

        val permission = args.optString("permission", "").trim().takeIf { it.isNotEmpty() }
            ?.let { raw ->
                permissionLevelOfWire(raw) ?: return@withContext ToolResult.Error(
                    "[E_INVALID] permission 取值不对：" + raw + "；只能取 read-only、workspace-write、danger-full-access。"
                )
            }
            ?: PermissionLevel.WORKSPACE
        // 不能借新建会话把自己的权限档抬上去：否则工作区档调一次工具就能拿到完全档，闸门形同虚设
        if (!ctx.permission.atLeast(permission)) {
            return@withContext ToolResult.Error(
                "[E_INVALID] 不能把新会话的权限设得比当前更高（当前「" + ctx.permission.displayName() + "」）；" +
                    "需要更高权限请让用户在会话设置里调整。"
            )
        }
        val effort = args.optString("reasoningEffort", "").trim().takeIf { it.isNotEmpty() }
            ?.let { raw ->
                SessionPreference.effortOrNull(raw) ?: return@withContext ToolResult.Error(
                    "[E_INVALID] reasoningEffort 取值不对：" + raw + "；只能取 off、low、high、max。"
                )
            }
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
