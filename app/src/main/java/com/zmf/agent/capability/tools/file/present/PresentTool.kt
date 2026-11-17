package com.zmf.agent.capability.tools.file.present

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
 * 它是回合产物的唯一声明入口：界面只认这里声明过、且当时校验通过的文件，
 * 不读取工具参数，也不扫描工作区。没有调用本工具的回合不产生任何产物标记。
 */
class PresentTool : Tool {

    override val name = "present"

    /** 只读操作：只登记交付意图，不碰文件内容。 */
    override val mutates = false

    override val description =
        "声明本轮交付给用户的文件，是本回合唯一的产物声明入口。" +
            "未声明的文件不会显示为产物；它只登记路径，不读取也不修改文件内容。" +
            "通常给 1-2 个，单次最多 8 个，path 必须是已存在的普通文件；工作区外路径需要「完全」权限。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "files": {
              "type": "array",
              "description": "要交付的文件数组，至少 1 个、最多 8 个，通常 1-2 个",
              "items": {
                "type": "object",
                "properties": {
                  "path": {"type": "string", "description": "已存在的普通文件路径，相对工作区或绝对路径"},
                  "description": {"type": "string", "description": "给用户看的一句话说明，可省略（默认不带说明）"}
                },
                "required": ["path"]
              }
            }
          },
          "required": ["files"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val items = presentPaths(args)
            ?: return ToolResult.Error("[E_INVALID] files 不能为空：请给出至少一个 {path, description?} 对象")
        if (items.size > MAX_FILES) {
            return ToolResult.Error("[E_TOO_LARGE] files 最多 " + MAX_FILES + " 个，本次 " + items.size + " 个；请挑选最重要的交付物")
        }
        val accepted = ArrayList<String>()
        for (raw in items) {
            if (raw.isEmpty()) return ToolResult.Error("[E_INVALID] path 不能为空：请给出要交付的文件路径")
            val file = runCatching { PathGate.resolve(ctx.workspaceRoot, raw) }.getOrNull()
                ?: return ToolResult.Error("[E_INVALID] 路径不合法：" + raw + "；请检查 path 后重试")
            if (!file.isFile) {
                return ToolResult.Error("[E_NOT_FOUND] 文件不存在或不是普通文件：" + raw + "；请先用 write_file 生成再声明")
            }
            // 交付会把路径交给预览页打开，必须与 read_file 同一套读权限：只读档不能借它读取工作区外的文件
            if (!PathAccess.canRead(ctx, file)) {
                return ToolResult.Error("当前权限档不能交付这个路径：" + raw + "（工作区外需要「完全」权限）")
            }
            accepted.add(raw)
        }
        return ToolResult.Ok(accepted.joinToString("\n") { "已声明交付：" + it })
    }

    private companion object {
        const val MAX_FILES = 8
    }
}
