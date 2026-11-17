package com.zmf.agent.capability.tools.media.generate_image

import com.zmf.agent.capability.port.ImageResult
import com.zmf.agent.capability.port.ToolEnv
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.PathAccess
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.model.PermissionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 按文字描述生成图片并写入指定文件。
 *
 * 图像模型由既有模型配置识别（见 `ImageGenerator`）；未装配服务或未识别到图像模型时返回对应错误码。
 * 文件必须先写入同目录的 `.part` 临时文件，再改名为正式文件，避免把写了一半的图片当作成品。
 */
class GenerateImageTool : Tool {

    override val name = "generate_image"

    /** 生成图片会写入工作区：只读档与计划模式都必须拒绝。 */
    override val mutates = true

    override val description =
        "按文字描述生成图片并写入 output_path 指定的文件。" +
            "调用前须先确定 output_path；生成成功后另用 present 声明交付物，查看图片用 read_image。" +
            "size 取 1024x1024（默认）、1024x1536、1536x1024；文件上限 20 MB；需「工作区」及以上权限。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "prompt": {"type": "string", "description": "图片描述，须完整写明画面内容与风格；必填，无默认值"},
            "output_path": {
              "type": "string",
              "description": "图片保存路径，相对工作区或绝对路径，必须指向文件而不是目录；未写扩展名时按模型返回格式补全，默认 png；必填，无默认值"
            },
            "size": {
              "type": "string",
              "enum": ["1024x1024", "1024x1536", "1536x1024"],
              "description": "图片尺寸，取 1024x1024（默认）、1024x1536、1536x1024"
            },
            "model": {"type": "string", "description": "图像模型名；省略时使用当前配置中识别到的图像模型"}
          },
          "required": ["prompt", "output_path"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) { run(args, ctx) }

    private suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val prompt = args.optString("prompt", "").trim()
        if (prompt.isEmpty()) {
            return ToolResult.Error(
                "[E_IMAGE_REQUEST_FAILED] prompt 不能为空：请完整描述画面内容与风格后重试。"
            )
        }
        val rawPath = args.optString("output_path", "").trim()
        if (rawPath.isEmpty()) {
            return ToolResult.Error(
                "[E_IMAGE_BAD_OUTPUT_PATH] 缺少 output_path：请给出文件路径（相对工作区或绝对路径），不能是目录。"
            )
        }
        if (rawPath.endsWith("/") || rawPath.endsWith("\\")) {
            return ToolResult.Error(
                "[E_PATH_NOT_FILE] output_path 指向目录：请给出文件路径，例如 out/cover.png。"
            )
        }
        val size = args.optString("size", DEFAULT_SIZE).trim().ifEmpty { DEFAULT_SIZE }
        if (size !in ALLOWED_SIZES) {
            return ToolResult.Error(
                "[E_IMAGE_BAD_SIZE] size 只能取 " + ALLOWED_SIZES.joinToString("、") + "，收到的是：" + size + "。"
            )
        }
        val model = args.optString("model", "").trim().takeIf { it.isNotEmpty() }

        val resolved = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse {
                return ToolResult.Error(
                    "[E_PATH_OUTSIDE] output_path 不合法：" + rawPath + "（" +
                        (it.message ?: "无法解析") + "）；请改用工作区内的路径。"
                )
            }
        if (!PathGate.isInsideWorkspaceOrTemp(ctx.workspaceRoot, resolved)) {
            HighRiskGate.protectedWriteReason(resolved)?.let { reason ->
                return ToolResult.Error("[E_INVALID] " + reason + "；请改用工作区内其他路径。")
            }
        }
        if (!PathAccess.canWrite(ctx, resolved)) {
            return ToolResult.Error(
                "[E_PATH_OUTSIDE] 当前权限档不能写入该路径：" + rawPath + "；工作区外写入需要「完全」权限。"
            )
        }
        if (resolved.isDirectory) {
            return ToolResult.Error("[E_PATH_NOT_FILE] output_path 是目录：请给出文件路径。")
        }

        val generator = ToolEnv.services().imageGenerator
            ?: return ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 图像生成服务未装配，当前环境不能生成图片；此项不能通过调整参数解决。"
            )
        if (!generator.available()) {
            return ToolResult.Error(
                "[E_IMAGE_PROVIDER_UNSET] 未识别到可用的图像模型：请在「设置 → 模型」中配置名称含 image 的模型并填写密钥。"
            )
        }

        val generated = try {
            generator.generate(prompt, size, model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.Error(
                "[E_IMAGE_REQUEST_FAILED] 图像请求失败：" + (e.message ?: e.javaClass.simpleName) + "；请稍后重试。"
            )
        }
        val success = when (generated) {
            is ImageResult.Success -> generated
            is ImageResult.Failure ->
                return ToolResult.Error("[" + generated.code + "] " + generated.message + "；请按提示处理后重试。")
        }
        if (success.bytes.size > MAX_FILE_BYTES) {
            return ToolResult.Error(
                "[E_IMAGE_TOO_LARGE] 生成图片 " + success.bytes.size + " 字节超过 20 MB 上限；请换更小的尺寸重试。"
            )
        }

        // 未写扩展名时按模型返回的 MIME 补全；已给出扩展名则原样使用
        val target = if (hasExtension(resolved.name)) {
            resolved
        } else {
            File(resolved.parentFile, resolved.name + "." + extensionFor(success.mime))
        }
        val directory = target.parentFile
            ?: return ToolResult.Error(
                "[E_PATH_NOT_FILE] output_path 没有父目录：" + target.absolutePath + "；请给出完整文件路径。"
            )
        if (!directory.exists() && !directory.mkdirs()) {
            return ToolResult.Error(
                "[E_WORKSPACE_WRITE_FAILED] 无法创建目录：" + directory.absolutePath + "；请检查存储空间与目录权限。"
            )
        }
        if (!directory.isDirectory) {
            return ToolResult.Error(
                "[E_WORKSPACE_WRITE_FAILED] 目标路径的父级不是目录：" + directory.absolutePath + "；请改用文件路径。"
            )
        }

        val existed = target.exists()
        val part = File(directory, target.name + ".part")
        try {
            part.writeBytes(success.bytes)
            if (existed && !target.delete()) {
                part.delete()
                return ToolResult.Error(
                    "[E_WORKSPACE_WRITE_FAILED] 无法覆盖已有文件：" + target.absolutePath + "；请检查文件权限或改用其他路径。"
                )
            }
            if (!part.renameTo(target)) {
                part.delete()
                return ToolResult.Error(
                    "[E_WORKSPACE_WRITE_FAILED] 无法改为正式文件名：" + target.absolutePath + "；请检查目标目录。"
                )
            }
        } catch (e: Exception) {
            part.delete()
            return ToolResult.Error(
                "[E_WORKSPACE_WRITE_FAILED] 写入失败：" + (e.message ?: e.javaClass.simpleName) + "；请检查存储空间后重试。"
            )
        }

        val relative = relativePath(ctx.workspaceRoot, target)
        return ToolResult.Ok(
            text = "已生成图片：" + relative +
                "\n模型：" + success.model +
                "\n尺寸：" + size +
                "\n字节：" + success.bytes.size +
                "\n" + (if (existed) "已覆盖原有文件" else "已新建文件") +
                "\n绝对路径：" + target.absolutePath,
            // 默认不附带图片数据：模型已获得路径，可显式调用 read_image 查看，避免重复占用上下文。
            imageDataUrls = emptyList(),
            imagePaths = listOf(relative)
        )
    }

    /** 文件名是否已带扩展名（以点开头的隐藏文件不算）。 */
    private fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot > 0 && dot < name.length - 1
    }

    /** 工作区相对路径；目标在工作区外时退回绝对路径。 */
    private fun relativePath(root: File, file: File): String {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
        val targetPath = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
        val prefix = rootPath.trimEnd(File.separatorChar) + File.separator
        return if (targetPath.startsWith(prefix)) {
            targetPath.substring(prefix.length).replace(File.separatorChar, '/')
        } else {
            targetPath
        }
    }

    /** 服务端返回的 MIME → 文件扩展名；未知类型按 PNG 处理。 */
    private fun extensionFor(mime: String): String =
        when (mime.lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }

    private companion object {
        const val DEFAULT_SIZE = "1024x1024"
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
        val ALLOWED_SIZES = listOf("1024x1024", "1024x1536", "1536x1024")
    }
}
