package com.zmf.agent.capability.tools.session.session_update_settings

import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.atLeast
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.SessionPreference
import com.zmf.agent.core.model.permissionLevelOfWire
import com.zmf.agent.core.model.wireName
import com.zmf.agent.core.store.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 改会话的权限与思考强度：只改目标会话，不动全局默认。 */
class SessionUpdateSettingsTool : Tool {

    override val name = "session_update_settings"

    override val description =
        "修改一条会话的权限档或思考强度，只影响该会话。" +
            "不改全局默认值，也不影响其他会话；调整全局默认改用设置页。" +
            "权限只能下调或保持，且不得高于当前档；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "会话 id，取自 session_list"},
            "permission": {
              "type": "string",
              "enum": ["read-only", "workspace-write", "danger-full-access"],
              "description": "会话权限档，取 read-only、workspace-write、danger-full-access；省略时不修改，且不得高于当前档"
            },
            "reasoningEffort": {
              "type": "string",
              "enum": ["off", "low", "high", "max"],
              "description": "思考强度，取 off、low、high、max；省略时不修改，off 表示不发送思考参数"
            }
          },
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val rawPermission = args.optString("permission", "").trim()
        val rawEffort = args.optString("reasoningEffort", "").trim()
        if (rawPermission.isEmpty() && rawEffort.isEmpty()) {
            return@withContext ToolResult.Error(
                "[E_INVALID] permission 与 reasoningEffort 至少要给一个，请补充其中一个后重试。"
            )
        }
        val permission = rawPermission.takeIf { it.isNotEmpty() }?.let {
            permissionLevelOfWire(it) ?: return@withContext ToolResult.Error(
                "[E_INVALID] permission 取值不对：" + it + "；只能取 read-only、workspace-write、danger-full-access。"
            )
        }
        // 只允许下调或保持不变：模型不能把自己的会话提到更高档，提权必须由用户在设置里完成
        if (permission != null && !ctx.permission.atLeast(permission)) {
            return@withContext ToolResult.Error(
                "[E_INVALID] 不能把会话权限提到当前档位之上（当前「" + ctx.permission.displayName() + "」）；" +
                    "需要更高权限请让用户在会话设置里调整。"
            )
        }
        val effort = rawEffort.takeIf { it.isNotEmpty() }?.let {
            SessionPreference.effortOrNull(it) ?: return@withContext ToolResult.Error(
                "[E_INVALID] reasoningEffort 取值不对：" + it + "；只能取 off、low、high、max。"
            )
        }
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("[E_SESSION_NOT_FOUND] 没有这个会话：" + id + "；请先用 session_list 核对。")
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
        ToolResult.Ok("会话设置已更新：" + parts.joinToString("，") + "；后续调用按新值生效。")
    }
}
