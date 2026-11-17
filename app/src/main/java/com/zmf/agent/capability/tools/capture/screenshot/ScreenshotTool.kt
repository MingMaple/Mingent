package com.zmf.agent.capability.tools.capture.screenshot

import com.zmf.agent.capability.port.CaptureResult
import com.zmf.agent.capability.port.CaptureTarget
import com.zmf.agent.capability.port.ToolEnv
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.PathAccess
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.infra.image.ImageCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 截取屏幕画面并写入指定文件。
 *
 * 应用内截屏不申请任何权限；应用外截屏要求已启用无障碍服务，且系统版本不低于 Android 11。
 * 工具本身不发送任何通知：应用在后台完成截屏时，结果随回合结束的既有通知告知。
 */
class ScreenshotTool : Tool {

    override val name = "screenshot"

    /** 截屏会写入工作区：只读档与计划模式都必须拒绝。 */
    override val mutates = true

    override val description =
        "截取屏幕画面并写入 output_path 指定的文件；交付前需另行调用 present 声明该文件。" +
            "截屏结果不用于屏幕点击；foreground_app 需已启用无障碍截屏服务，且系统版本不低于 Android 11。" +
            "output_path 必填并指向文件，后缀只取 png、jpg、jpeg，max_side 上限 4096；工作区档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "source": {
              "type": "string",
              "enum": ["self", "foreground_app"],
              "description": "截屏来源，取 self（本应用当前窗口，默认）或 foreground_app（前台其他应用，需已启用无障碍截屏服务）"
            },
            "output_path": {
              "type": "string",
              "description": "截图保存路径，相对工作区或绝对路径，必填，必须指向文件而不是目录；后缀只接受 png、jpg、jpeg，未写时补 png"
            },
            "app_package": {
              "type": "string",
              "description": "仅 source=foreground_app 有效：目标应用包名；给出时必须与当前前台应用一致"
            },
            "max_side": {
              "type": "integer",
              "description": "长边上限，单位像素，范围 256–4096，默认 1568"
            }
          },
          "required": ["output_path"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) { run(args, ctx) }

    private suspend fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val sourceName = args.optString("source", DEFAULT_SOURCE).trim().ifEmpty { DEFAULT_SOURCE }
        val target = when (sourceName) {
            "self" -> CaptureTarget.SELF
            "foreground_app" -> CaptureTarget.FOREGROUND_APP
            else -> return ToolResult.Error(
                "[E_UNSUPPORTED] 截屏来源不受支持：" + sourceName +
                    "；source 只能取 self 或 foreground_app，改正后重试"
            )
        }
        val rawPath = args.optString("output_path", "").trim()
        if (rawPath.isEmpty()) {
            return ToolResult.Error(
                "[E_INVALID_ARGUMENT] output_path 不能为空：给出工作区相对路径或绝对路径的文件后重试"
            )
        }
        if (rawPath.endsWith("/") || rawPath.endsWith("\\")) {
            return ToolResult.Error(
                "[E_PATH_NOT_FILE] output_path 指向目录：" + rawPath + "；改指文件名后重试"
            )
        }
        val resolved = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse {
                return ToolResult.Error(
                    "[E_PATH_OUTSIDE] output_path 不合法：" + rawPath + "（" +
                        (it.message ?: "无法解析") + "）；改用工作区内路径后重试"
                )
            }
        if (!PathGate.isInsideWorkspaceOrTemp(ctx.workspaceRoot, resolved)) {
            HighRiskGate.protectedWriteReason(resolved)?.let { reason ->
                return ToolResult.Error(reason)
            }
        }
        if (!PathAccess.canWrite(ctx, resolved)) {
            return ToolResult.Error(
                "[E_PATH_OUTSIDE] 当前权限档不能写入该路径：" + rawPath +
                    "；改用工作区内路径，或请用户切到「完全」档后重试"
            )
        }
        if (resolved.isDirectory) {
            return ToolResult.Error("[E_PATH_NOT_FILE] output_path 是目录：" + rawPath + "；改指文件名后重试")
        }
        // 扩展名决定格式：未写扩展名按 png 补齐，写了就按扩展名编码
        val suffix = suffixOf(resolved.name)
        val format = when (suffix) {
            "" -> FORMAT_PNG
            "png" -> FORMAT_PNG
            "jpg", "jpeg" -> FORMAT_JPEG
            else -> return ToolResult.Error(
                "[E_UNSUPPORTED] output_path 的扩展名不受支持：" + suffix +
                    "；只能取 png、jpg 或 jpeg，改正后重试"
            )
        }
        val file = if (suffix.isEmpty()) File(resolved.parentFile, resolved.name + ".png") else resolved
        val directory = file.parentFile
            ?: return ToolResult.Error("[E_PATH_NOT_FILE] output_path 没有父目录：" + file.absolutePath + "；换一个路径后重试")

        val maxSide = args.optInt("max_side", DEFAULT_MAX_SIDE).coerceIn(MIN_SIDE, MAX_SIDE)
        val expectedPackage = args.optString("app_package", "").trim().takeIf { it.isNotEmpty() }

        val capture = ToolEnv.services().screenCapture
            ?: return ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 截屏服务未装配：该错误不能通过调整参数解决，请重启应用后重试"
            )
        val result = try {
            capture.capture(target, expectedPackage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.Error(
                "[E_CAPTURE_FAILED] 截屏失败：" + (e.message ?: e.javaClass.simpleName) +
                    "；稍后重试，若持续失败则重启应用后再试"
            )
        }
        val success = when (result) {
            is CaptureResult.Success -> result
            is CaptureResult.Failure -> return ToolResult.Error("[" + result.code + "] " + result.message)
        }

        val sourceWidth = success.bitmap.width
        val sourceHeight = success.bitmap.height
        val bytes = try {
            ImageCodec.encode(success.bitmap, maxSide, format, JPEG_QUALITY)
        } catch (e: Exception) {
            return ToolResult.Error(
                "[E_CAPTURE_FAILED] 图片编码失败：" + (e.message ?: e.javaClass.simpleName) +
                    "；可改用 png 后缀重试，仍失败时降低 max_side"
            )
        } catch (e: OutOfMemoryError) {
            return ToolResult.Error("[E_CAPTURE_FAILED] 图片编码时内存不足：降低 max_side 后重试")
        } finally {
            success.bitmap.recycle()
        }

        if (!directory.exists() && !directory.mkdirs()) {
            return ToolResult.Error(
                "[E_WORKSPACE_WRITE_FAILED] 无法创建目录：" + directory.absolutePath +
                    "；确认工作区可写后重试"
            )
        }
        if (!directory.isDirectory) {
            return ToolResult.Error(
                "[E_WORKSPACE_WRITE_FAILED] 目标路径的父级不是目录：" + directory.absolutePath +
                    "；output_path 需指向文件"
            )
        }

        val part = File(directory, file.name + ".part")
        try {
            part.writeBytes(bytes)
            if (file.exists() && !file.delete()) {
                part.delete()
                return ToolResult.Error(
                    "[E_WORKSPACE_WRITE_FAILED] 无法覆盖已有文件：" + file.absolutePath +
                        "；确认工作区可写后重试"
                )
            }
            if (!part.renameTo(file)) {
                part.delete()
                return ToolResult.Error(
                    "[E_WORKSPACE_WRITE_FAILED] 写入完成但无法改为正式文件名：" + file.absolutePath +
                        "；确认工作区可写后重试"
                )
            }
        } catch (e: Exception) {
            part.delete()
            return ToolResult.Error(
                "[E_WORKSPACE_WRITE_FAILED] 写入失败：" + (e.message ?: e.javaClass.simpleName) +
                    "；确认工作区可写后重试"
            )
        }

        // 尺寸以落盘文件为准：max_side 生效后可能已缩小，不能沿用截屏位图的原始尺寸。
        val probe = runCatching { ImageCodec.probe(file) }.getOrNull()
        val width = probe?.width ?: sourceWidth
        val height = probe?.height ?: sourceHeight
        val relative = relativePath(ctx.workspaceRoot, file)
        val capturedTime = SimpleDateFormat(TIME_PATTERN, Locale.US).format(Date(success.capturedAt))
        return ToolResult.Ok(
            text = "已截屏：" + relative + "，" + width + " × " + height + "，" +
                success.packageName + "，" + capturedTime + "；展示前调用 present 声明该文件",
            imagePaths = listOf(relative)
        )
    }

    /** 文件名后缀（小写、不含点）；无后缀返回空串。 */
    private fun suffixOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.US)
    }

    /** 工作区相对路径；目标不在工作区内时退回绝对路径。 */
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
        const val DEFAULT_SOURCE = "self"
        const val FORMAT_PNG = "png"
        const val FORMAT_JPEG = "jpeg"
        const val MIN_SIDE = 256
        const val MAX_SIDE = 4096
        const val DEFAULT_MAX_SIDE = 1568
        const val JPEG_QUALITY = 80
        const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
    }
}
