package com.zmf.agent.capability.tools.skill.skill

import com.zmf.agent.capability.skill.SkillStore
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * skill 工具：按名字加载一个 Skill 的完整正文。
 *
 * 设计目的：系统提示词中只放入 available_skills 目录（名称与一句话描述），
 * 即使 Skill 数量较多也不会占满上下文；模型实际使用某个 Skill 时再调用该工具取回正文。
 * 不传 name 时列出全部可用 Skill，便于模型发现可用的能力。
 *
 * 仅读取本地文件、不执行任何写入，因此权限档为 READ_ONLY。
 */
class SkillTool : Tool {

    override val name = "skill"

    override val description =
        "按名称加载一个 Skill 的完整说明，name 省略时列出全部可用 Skill。" +
            "只读；目录里的一句话用途足够判断时直接选用，不要逐个加载。" +
            "name 取自系统提示词里的 available_skills；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "name": {"type": "string", "description": "要加载的 Skill 名称，取自系统提示词里的 available_skills；省略或留空时列出全部可用 Skill，无默认名称"}
          },
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        // 参数可能被模型写成 null（JSON null 会被 optString 变成 "null"，这里统一 trim 后再判空）
        val key = args.optString("name", "").trim()
        return try {
            withContext(Dispatchers.IO) {
                if (key.isEmpty() || key.equals("null", ignoreCase = true)) listAll(ctx) else loadOne(ctx, key)
            }
        } catch (e: CancellationException) {
            // 用户点击「停止」：原样抛出，否则取消无法向上传播
            throw e
        } catch (e: Exception) {
            ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 读取 Skill 失败：" + (e.message ?: e.javaClass.simpleName) +
                    "；请检查 appdata/skills 目录后重试，此项不能通过调整参数解决。"
            )
        }
    }

    /** 列出全部可用 Skill：一行一个「- 名字：描述」。 */
    private fun listAll(ctx: ToolContext): ToolResult {
        val entries = SkillStore.list(ctx.context)
        if (entries.isEmpty()) {
            return ToolResult.Ok("（当前没有任何 Skill。把 Skill 放到应用私有目录 appdata/skills/<名字>/SKILL.md 就能被发现。）")
        }
        val text = buildString {
            append("可用 Skill 共 ").append(entries.size).append(" 个：\n")
            for (entry in entries) {
                append("- ").append(entry.name)
                val desc = entry.description.replace("\n", " ").trim()
                if (desc.isNotEmpty()) append("：").append(desc)
                append('\n')
            }
            append("用 skill 工具并带上 name 即可加载正文。")
        }
        return ToolResult.Ok(text)
    }

    /** 加载一个 Skill 的正文，按 DSH 同款信封包裹，使模型能清晰识别边界与资源位置。 */
    private fun loadOne(ctx: ToolContext, key: String): ToolResult {
        // 先 find 拿到规范名字（用户/模型可能传的是目录 id），加载仍然走 SkillStore.load
        val entry = SkillStore.find(ctx.context, key)
            ?: return ToolResult.Error(
                "[E_INVALID] 找不到 Skill「" + key + "」或它已不可用；请省略 name 列出全部 Skill 后重试。"
            )
        val body = SkillStore.load(ctx.context, entry.id)
            ?: return ToolResult.Error(
                "[E_INVALID] 找不到 Skill「" + key + "」或它已不可用；请省略 name 列出全部 Skill 后重试。"
            )
        // 包裹分三段：名称 → 资源指引（base 目录，正文中的相对路径均相对该目录解析）→ 正文。
        // 与 DSH 的 <skill_content>/<skill_resources>/<skill_instructions> 对齐；
        // base 目录即 SkillEntry.dir，本地技能均以此作为资源解析基准。
        return ToolResult.Ok(
            "<skill_content name=\"" + entry.name + "\">\n" +
                "<skill_resources>\n" +
                "Base directory for this skill: " + entry.dir.absolutePath + "\n" +
                "Resolve relative paths mentioned by this skill against the base directory before using them.\n" +
                "</skill_resources>\n\n" +
                "<skill_instructions>\n" + body.trim() + "\n</skill_instructions>\n" +
                "</skill_content>"
        )
    }
}
