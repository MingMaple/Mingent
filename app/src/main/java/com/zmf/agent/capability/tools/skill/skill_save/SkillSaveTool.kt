package com.zmf.agent.capability.tools.skill.skill_save

import com.zmf.agent.capability.skill.SkillStore
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * skill_save：把一段可复用的做法固化成 Skill（创建或更新）。
 *
 * 与 skill 工具的分工：skill 只读（列目录、加载正文），本工具只写。
 * 写入位置仍是 appdata/skills/<id>/SKILL.md，和设置页的手工编辑共用同一份存储，
 * 因此「助手写出来的技能」和「用户自己建的技能」在列表里完全平等。
 *
 * 只在用户明确要求「记下来 / 存成技能 / 下次还用这套流程」时使用：
 * 自动固化会不断堆积低质量技能，反而干扰模型挑选。
 */
class SkillSaveTool : Tool {

    override val name = "skill_save"

    /** 写 appdata，计划模式与只读档都必须拒绝。 */
    override val mutates = true

    override val description =
        "把一段可复用的做法固化为 Skill（新建或更新），写入 appdata/skills/<id>/SKILL.md。" +
            "仅在用户明确要求记录或固化时使用，不得自行保存；只读技能改用 skill。" +
            "新建同名技能或改名撞名须显式 overwrite=true；名称用小写字母、数字与连字符；需「工作区」及以上权限。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "name": {"type": "string", "description": "技能名称，例如 release-apk；新建时据此生成目录名；必填，无默认值"},
            "description": {"type": "string", "description": "一句话描述适用场景，是技能被选中的唯一依据；必填，无默认值"},
            "body": {"type": "string", "description": "技能正文：给模型看的具体步骤、命令与判断条件；必填，无默认值"},
            "when_to_use": {"type": "string", "description": "可选适用场景，比 description 更具体，一并写入 frontmatter；默认空"},
            "overwrite": {"type": "boolean", "description": "为 true 时允许覆盖同名技能；新建同名或改名撞名时必须显式传 true，默认 false"}
          },
          "required": ["name", "description", "body"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val name = args.optString("name", "").trim()
        val description = args.optString("description", "").trim()
        val body = args.optString("body", "").trim()
        if (name.isEmpty()) {
            return ToolResult.Error("[E_INVALID] name 不能为空：请填写小写字母、数字与连字符组成的名称。")
        }
        if (description.isEmpty()) {
            return ToolResult.Error("[E_INVALID] description 不能为空：它是技能被选中的唯一依据。")
        }
        if (body.isEmpty()) {
            return ToolResult.Error("[E_INVALID] body 不能为空：技能缺少可执行的步骤。")
        }

        val whenToUse = args.optString("when_to_use", "").trim()
        val overwrite = args.optBoolean("overwrite", false)

        return try {
            withContext(Dispatchers.IO) {
                val existed = SkillStore.find(ctx.context, name) != null
                val result = SkillStore.save(
                    context = ctx.context,
                    key = if (existed) name else null,
                    name = name,
                    description = description,
                    body = body,
                    whenToUse = whenToUse,
                    overwrite = overwrite
                )
                val entry = result.getOrElse {
                    return@withContext ToolResult.Error(
                        "[E_INVALID] 保存 Skill 失败：" + (it.message ?: it.javaClass.simpleName) +
                            "；若为同名冲突请显式传 overwrite=true。"
                    )
                }
                ToolResult.Ok(
                    (if (existed) "已更新 Skill「" else "已创建 Skill「") + entry.name +
                        "」（id=" + entry.id + "）。下一条消息起会出现在技能目录中，可用 skill 工具按名称加载。"
                )
            }
        } catch (e: CancellationException) {
            // 用户点了「停止」：原样抛出，否则取消无法向上传播
            throw e
        } catch (e: Exception) {
            ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 保存 Skill 失败：" + (e.message ?: e.javaClass.simpleName) +
                    "；请检查 appdata/skills 目录后重试，此项不能通过调整参数解决。"
            )
        }
    }
}
