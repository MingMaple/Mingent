package com.zmf.agent.capability.tools.file

import com.zmf.agent.capability.tools.PathAccess
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject

/**
 * 声明本轮交付给用户的文件。
 *
 * 只做一件事：把「这些文件是给用户看的」说清楚，让界面在回合末尾挂出卡片。
 * 为什么需要它：write_file 只能写文本，PDF、Office、图片这类文件是 terminal 里造出来的，
 * 没有工具回执可依据；没有显式交付，界面就只能靠猜。
 */
class PresentTool : Tool {

    override val name = "present"

    /** 只读操作：只登记交付意图，不碰文件内容。 */
    override val mutates = false

    override val description =
        "把已经存在于工作区里的文件声明为「本轮交付给用户」，界面会在回复末尾给每份文件挂一张可点开的卡片。" +
            "用它交付 PDF、Office 文档、图片等 write_file 写不出来的文件，也包括你在 terminal 里生成的文件。" +
            "参数 paths 是工作区相对路径（或绝对路径）的数组，可一次给多个，最多 8 个；" +
            "工作区外的路径需要「完全」权限；" +
            "不要为已经在回复正文里给出的短文本再调用它。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "paths": {
              "type": "array",
              "items": {"type": "string"},
              "description": "要交付的文件路径，相对工作区或绝对路径"
            },
            "note": {"type": "string", "description": "一句话说明这些文件是什么，可省略"}
          },
          "required": ["paths"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val array = args.optJSONArray("paths") ?: return ToolResult.Error("缺少参数 paths")
        if (array.length() == 0) return ToolResult.Error("paths 是空的")
        if (array.length() > MAX_FILES) return ToolResult.Error("一次最多交付 " + MAX_FILES + " 个文件")
        val accepted = ArrayList<String>()
        val missing = ArrayList<String>()
        for (index in 0 until array.length()) {
            val raw = array.optString(index, "").trim()
            if (raw.isEmpty()) continue
            val file = runCatching { PathGate.resolve(ctx.workspaceRoot, raw) }.getOrNull()
            if (file == null || !file.isFile) {
                missing.add(raw)
                continue
            }
            // 交付会把路径交给预览页打开，必须与 read_file 同一套读权限：只读档不能借它读取工作区外的文件
            if (!PathAccess.canRead(ctx, file)) {
                return ToolResult.Error("当前权限档不能交付这个路径：" + raw + "（工作区外需要「完全」权限）")
            }
            accepted.add(raw)
        }
        if (accepted.isEmpty()) {
            return ToolResult.Error("这些路径都不是已存在的文件：" + missing.joinToString("、"))
        }
        val note = args.optString("note", "").trim()
        val body = buildString {
            append("已交付 ").append(accepted.size).append(" 个文件：").append(accepted.joinToString("、"))
            if (note.isNotEmpty()) append("\n说明：").append(note)
            if (missing.isNotEmpty()) append("\n未找到（未计入）：").append(missing.joinToString("、"))
        }
        return ToolResult.Ok(body)
    }

    private companion object {
        const val MAX_FILES = 8
    }
}
