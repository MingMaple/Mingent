package com.zmf.agent.capability.tools.media.read_image

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.infra.image.ImageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 读取工作区内的图片并把图像内容回传给模型。
 *
 * 图片以 data URL 形式随 [ToolResult.Ok.imageDataUrls] 返回，不落盘；文本头只给出格式、原始尺寸、
 * 文件大小、降采样后的尺寸与上下文占用估算，避免模型为确认图片内容而反复读取同一文件。
 *
 * 格式按文件头判定，不信任扩展名；只读取工作区（含 tmp）内的普通文件。
 */
class ReadImageTool : Tool {

    override val name = "read_image"

    override val description =
        "读取工作区内的图片并把图像内容返回给模型。" +
            "只支持 PNG、JPEG、WebP 与 GIF（GIF 只取首帧）；不读取工作区外文件与普通文本，文本改用 read_file。" +
            "文件上限 20 MB、像素上限 40 MP；max_side 上限 4096、quality 范围 40–95；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "file_path": {"type": "string", "description": "图片路径，相对工作区或绝对路径，须为工作区内的普通文件；必填，无默认值"},
            "max_side": {"type": "integer", "description": "长边上限，范围 256–4096，默认 1568"},
            "quality": {"type": "integer", "description": "JPEG 编码质量，范围 40–95，默认 80"},
            "keep_alpha": {"type": "boolean", "description": "为 true 时 PNG 与 GIF 保留透明通道；默认 true"}
          },
          "required": ["file_path"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) { run(args, ctx) }

    private fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = args.optString("file_path", "").trim().ifEmpty { args.optString("path", "").trim() }
        if (rawPath.isEmpty()) {
            return ToolResult.Error("[E_INVALID] 缺少 file_path：请给出工作区内的图片路径后重试。")
        }
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse {
                return ToolResult.Error(
                    "[E_PATH_OUTSIDE] 路径不合法：" + (it.message ?: "无法解析") + "（" + rawPath + "）；请检查路径后重试。"
                )
            }
        if (!PathGate.isInsideWorkspaceOrTemp(ctx.workspaceRoot, file)) {
            return ToolResult.Error(
                "[E_PATH_OUTSIDE] 路径越出工作区：" + rawPath + "；read_image 只读取工作区内的图片。"
            )
        }
        if (!file.exists() || !file.isFile) {
            return ToolResult.Error("[E_NOT_FOUND] 文件不存在或不是普通文件：" + rawPath + "；请核对路径。")
        }
        val sizeBytes = file.length()
        if (sizeBytes > MAX_FILE_BYTES) {
            return ToolResult.Error(
                "[E_TOO_LARGE] 文件 " + sizeBytes + " 字节超过 " + MAX_FILE_BYTES + " 字节上限；请先压缩后再读取。"
            )
        }

        val probe = try {
            ImageCodec.probe(file)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Error(
                "[E_DECODE] 无法读取图像信息：" + (e.message ?: "文件头不合法") + "；请确认文件是完整的图片。"
            )
        }
        if (probe == null) {
            val knownContainer = runCatching { ImageCodec.isImageContainer(file) }.getOrDefault(false)
            return if (knownContainer) {
                ToolResult.Error("[E_UNSUPPORTED] 图片格式不受支持：仅支持 PNG、JPEG、WebP 与 GIF。")
            } else {
                ToolResult.Error("[E_NOT_IMAGE] 不是图片文件：" + rawPath + "；文本请改用 read_file 读取。")
            }
        }

        val pixels = probe.width.toLong() * probe.height.toLong()
        if (pixels > ImageCodec.MAX_PIXELS) {
            return ToolResult.Error(
                "[E_PIXELS] 图像 " + probe.width + " × " + probe.height +
                    " 超过 40 MP 上限；请先缩小后再读取。"
            )
        }

        val maxSide = args.optInt("max_side", DEFAULT_MAX_SIDE).coerceIn(MIN_SIDE, MAX_SIDE)
        val quality = args.optInt("quality", DEFAULT_QUALITY).coerceIn(MIN_QUALITY, MAX_QUALITY)
        val keepAlpha = args.optBoolean("keep_alpha", true)

        val dataUrl = try {
            ImageCodec.dataUrl(file, maxSide, quality, keepAlpha)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Error("[E_PIXELS] " + (e.message ?: "图像像素超过上限") + "；请先缩小后再读取。")
        } catch (e: Exception) {
            return ToolResult.Error(
                "[E_DECODE] 图像解码失败：" + (e.message ?: e.javaClass.simpleName) + "；请确认图片完整后重试。"
            )
        } ?: return ToolResult.Error("[E_DECODE] 图像解码失败：" + rawPath + "；请确认图片完整后重试。")

        val targetWidth: Int
        val targetHeight: Int
        val longest = maxOf(probe.width, probe.height)
        if (longest <= maxSide) {
            targetWidth = probe.width
            targetHeight = probe.height
        } else {
            val scale = maxSide.toDouble() / longest
            targetWidth = maxOf(1, (probe.width * scale).roundToInt())
            targetHeight = maxOf(1, (probe.height * scale).roundToInt())
        }

        val displayPath = relativePath(ctx.workspaceRoot, file)
        val header = "<path>" + displayPath + "</path>\n" +
            "<type>image</type>\n" +
            "<content>\n" +
            "media type " + probe.mime + ", " + probe.width + " × " + probe.height +
            ", " + probe.bytes + " bytes; normalized to " + targetWidth + " × " + targetHeight +
            " (" + tokenEstimate(targetWidth, targetHeight) + " context tokens)\n" +
            "</content>"

        return ToolResult.Ok(
            text = header,
            imageDataUrls = listOf(dataUrl),
            imagePaths = listOf(displayPath)
        )
    }

    /** 上下文占用估算：按每 512 像素约 1 token 计算，仅用于让模型了解代价。 */
    private fun tokenEstimate(width: Int, height: Int): String {
        val tokens = maxOf(1L, width.toLong() * height.toLong() / PIXELS_PER_TOKEN)
        return if (tokens >= 1000) {
            String.format(Locale.US, "%.1fk", tokens / 1000.0)
        } else {
            tokens.toString()
        }
    }

    /** 工作区相对路径；目标不在工作区内时退回绝对路径（正常路径已在上方被拒绝）。 */
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

    private companion object {
        const val MAX_FILE_BYTES = 20L * 1024L * 1024L
        const val MIN_SIDE = 256
        const val MAX_SIDE = 4096
        const val DEFAULT_MAX_SIDE = 1568
        const val MIN_QUALITY = 40
        const val MAX_QUALITY = 95
        const val DEFAULT_QUALITY = 80

        /** 上下文 token 估算的像素分母。 */
        const val PIXELS_PER_TOKEN = 512L
    }
}
