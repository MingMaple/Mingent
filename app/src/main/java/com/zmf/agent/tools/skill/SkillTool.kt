package com.zmf.agent.tools.skill

import com.zmf.agent.agent.skill.SkillStore
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * skill 工具：按名字加载一个 Skill 的完整正文。
 *
 * 为什么要有它：系统提示词里只放 available_skills 目录（名字 + 一句话描述），
 * 几十个 Skill 也不会把上下文吃满；模型真要用某个 Skill 时再调这个工具把正文取回来。
 * 不传 name 时列出全部可用 Skill，方便模型自己发现「有哪些能用的」。
 *
 * 只读本地文件、不写任何东西，所以权限档是 READ_ONLY。
 */
class SkillTool : Tool {

    override val name = "skill"

    override val description =
        "加载一个 Skill 的完整说明；Skill 名称见系统提示词里的 available_skills；" +
            "不传 name 时列出全部可用 Skill。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "name": {"type": "string", "description": "要加载的 Skill 名称（取值见系统提示词里的 available_skills）；留空则列出全部可用 Skill"}
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
            // 用户点了「停止」：原样抛出，否则取消渗不下去
            throw e
        } catch (e: Exception) {
            ToolResult.Error("读取 Skill 失败：" + (e.message ?: e.javaClass.simpleName))
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

    /** 加载一个 Skill 的正文，按 <skill_content name="…"> 包起来，模型能一眼看出边界。 */
    private fun loadOne(ctx: ToolContext, key: String): ToolResult {
        // 先 find 拿到规范名字（用户/模型可能传的是目录 id），加载仍然走 SkillStore.load
        val entry = SkillStore.find(ctx.context, key)
            ?: return ToolResult.Error("没有这个 Skill：" + key + "（可用 list 查看）")
        val body = SkillStore.load(ctx.context, entry.id)
            ?: return ToolResult.Error("没有这个 Skill：" + key + "（可用 list 查看）")
        return ToolResult.Ok("<skill_content name=\"" + entry.name + "\">\n" + body.trim() + "\n</skill_content>")
    }
}
