package com.zmf.agent.core.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.zmf.agent.core.log.FileLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.math.sqrt

/**
 * 系统文件选择器给的 Uri → 当前工作区里的真实文件。
 *
 * 复制原因：模型只能通过 read_file 读取工作区内的相对路径，无法获取也无法使用 content:// 形式的 Uri。
 * 复制到 <工作区>/uploads/ 下后，relPath 可直接用于 read_file。
 *
 * 两项约束：单文件上限 50MB（超出即中止，不残留不完整文件）；
 * 落盘先写 `.part` 再 rename，中途失败或进程终止均不会残留看似可用的损坏文件。
 */
object UploadStore {

    private const val TAG = "upload"

    /** 上传目录（相对工作区根；relPath 一律用正斜杠）。 */
    const val DIR = "uploads"

    /** 单文件上限 50MB；分享入口的普通文件也按这一口径拦截。 */
    const val MAX_BYTES = 50L * 1024L * 1024L

    /**
     * 图片归一化的总像素预算：2048×2048（约 419 万像素），与 DSH 同口径。
     *
     * 按「总像素」而不是「最长边」限制：16:9 照片与正方形图都能吃满预算，
     * 不会出现 1568px 一刀切把 1569px 的图直接砍半的情况。
     */
    private const val IMAGE_MAX_PIXELS = 2048L * 2048L

    /** 长边兜底：极窄长条图按像素预算缩完仍可能超过 8192，再单独兜一道（与 DSH 同值）。 */
    private const val IMAGE_MAX_DIM = 8192

    /** 归一化后的目标字节上限 4MB（base64 后约 5.3MB），与 DSH 同口径。 */
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    /** 质量档位，由高到低尝试，首次压到 4MB 以内即采用（与 DSH 相同）。 */
    private val IMAGE_QUALITIES = intArrayOf(85, 75, 60)

    private const val BUFFER_SIZE = 8 * 1024

    /** 显示名最长 60 字符，超出时截取后 60 个字符（保留后缀更便于识别文件类型）。 */
    private const val MAX_NAME = 60

    private const val FALLBACK_NAME = "file"

    /** 从正文中读取 "uploads/xxx" 时的终止字符：除空白外还需在这些标点处终止。 */
    private const val STOP_CHARS = "\"'()（）,，。；;：:"

    /** 一次导入的结果：界面显示用 [name]，喂给模型的相对路径用 [relPath]。 */
    data class Imported(val name: String, val relPath: String, val size: Long)

    /**
     * 把 [uri] 复制进 [workspaceRoot]/uploads/。
     *
     * 失败一律返回 Result.failure 且不留残留（.part 已删）：调用方只需要在界面报错。
     */
    fun import(context: Context, uri: Uri, workspaceRoot: File): Result<Imported> {
        var part: File? = null
        return try {
            val dir = File(workspaceRoot, DIR)
            if (!dir.exists()) dir.mkdirs()
            // 重名不覆盖：若已存在 myslides.pdf，则依次尝试 myslides-1.pdf、myslides-2.pdf……
            val name = uniqueName(dir, sanitize(displayName(context, uri)))
            val target = File(dir, name)
            val temp = File(dir, name + ".part")
            part = temp

            val input = context.contentResolver.openInputStream(uri)
                ?: throw UploadFailure("无法读取所选文件，请重新选择。")
            var size = 0L
            input.use { source ->
                temp.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        // 边写边计字节：超过上限立刻中止，不把整个大文件落完再判断
                        size += read
                        if (size > MAX_BYTES) throw UploadFailure("文件超过 50MB，请选择更小的文件。")
                        sink.write(buffer, 0, read)
                    }
                    sink.flush()
                }
            }
            if (!temp.renameTo(target)) throw UploadFailure("保存上传文件失败，请稍后重试。")
            part = null

            val relPath = DIR + "/" + name
            FileLog.log(TAG, "已导入 " + relPath + "（" + size + " 字节，来源 " + uri + "）")
            Result.success(Imported(name = name, relPath = relPath, size = size))
        } catch (e: Exception) {
            // 任一步骤失败仅删除本次写入的 .part，不删除目录中已有文件
            part?.delete()
            FileLog.log(TAG, "导入失败（" + uri + "）：" + (e.message ?: e.javaClass.simpleName))
            val failure = e as? UploadFailure
                ?: UploadFailure("保存文件失败，请检查存储空间与文件访问权限后重试。", e)
            Result.failure(failure)
        }
    }

    /**
     * 把 [uri] 指向的图片归一化后存进 [workspaceRoot]/uploads/（与 DSH 同一套口径）。
     *
     * 三条规则：
     * 1. **能不动就不动**：静态 PNG/WebP、无旋转、总像素与字节都在预算内的，原字节透传，
     *    不重编码（避免二次有损、也避免把透明图转成不透明的 JPEG）。
     * 2. **超预算才处理**：总像素 ≤ [IMAGE_MAX_PIXELS]、最长边 ≤ [IMAGE_MAX_DIM]，
     *    不放大；透明图统一压 WebP（保 alpha），其余压 JPEG。
     * 3. **元数据不进工作区**：JPEG 一律重编码并按 EXIF 方向摆正，原图的 GPS/设备信息随之丢弃。
     *
     * 失败一律返回 Result.failure（不静默丢弃图片），调用方负责在界面提示用户。
     */
    fun importImage(context: Context, uri: Uri, workspaceRoot: File): Result<Imported> {
        var part: File? = null
        return try {
            val dir = File(workspaceRoot, DIR)
            if (!dir.exists()) dir.mkdirs()
            val encoded = normalizeImage(context, uri)
            val name = uniqueImageName(dir, System.currentTimeMillis(), encoded.ext)
            val target = File(dir, name)
            val temp = File(dir, name + ".part")
            part = temp

            temp.writeBytes(encoded.bytes)
            if (!temp.renameTo(target)) throw UploadFailure("保存图片失败，请稍后重试。")
            part = null

            val relPath = DIR + "/" + name
            FileLog.log(TAG, "已导入图片 " + relPath + "（" + encoded.bytes.size + " 字节，来源 " + uri + "）")
            Result.success(Imported(name = name, relPath = relPath, size = encoded.bytes.size.toLong()))
        } catch (e: Exception) {
            // 与 [import] 一致：仅删除本次写入的 .part，不删除目录中已有文件
            part?.delete()
            FileLog.log(TAG, "图片导入失败（" + uri + "）：" + (e.message ?: e.javaClass.simpleName))
            val failure = e as? UploadFailure
                ?: UploadFailure("保存图片失败，请检查存储空间后重试。", e)
            Result.failure(failure)
        }
    }

    // ---- 未引用附件：统计与清理 ----

    /**
     * 只统计、不删：当前工作区 uploads/ 里没有被 [sessions] 引用的文件数与占用字节。
     *
     * [context] 暂未使用，保留是为了与 [import] / [importImage] 保持一致调用方式：
     * 后续若需在日志中携带上下文信息，无需修改全部调用点。
     */
    fun orphanReport(context: Context, sessions: List<ChatSession>, workspaceRoot: File): Pair<Int, Long> =
        survey(sessions, workspaceRoot, delete = false)

    /**
     * 删掉当前工作区 uploads/ 里没有被 [sessions] 引用的文件，返回 删除文件数 / 字节数。
     *
     * 只扫 [workspaceRoot] 一个目录：图片路径是工作区相对的，把几个工作区的 uploads 混在一起
     * 判断「谁引用谁」会误删；调用方负责只传这个工作区（以及未分类）的会话。
     */
    fun cleanOrphans(context: Context, sessions: List<ChatSession>, workspaceRoot: File): Pair<Int, Long> =
        survey(sessions, workspaceRoot, delete = true)

    /**
     * 删除会话后清理其引用的图片：同一工作区中没有其他会话引用时才执行删除。
     *
     * 仅识别该会话 images 中的路径（该字段记录的是 [importImage] 写入 uploads/ 的图片）；
     * 普通附件只出现在正文中，判定不可靠，统一交由「清理未引用附件」处理。
     */
    fun cleanSessionImages(
        removed: ChatSession,
        remaining: List<ChatSession>,
        workspaceRoot: File
    ): Pair<Int, Long> {
        val dir = File(workspaceRoot, DIR)
        val keep = referencedPaths(remaining)
        var count = 0
        var bytes = 0L
        for (raw in removed.messages.flatMap { it.images }.distinct()) {
            val rel = normalize(raw)
            if (keep.contains(rel)) continue
            // 仅识别 uploads/ 目录内的文件：路径不在该目录（旧数据或跨工作区）一律不处理
            val name = rel.substringAfterLast('/')
            if (name.isEmpty() || rel != DIR + "/" + name) continue
            val file = File(dir, name)
            if (!file.isFile) continue
            val size = file.length()
            if (!file.delete()) {
                FileLog.log(TAG, "删除会话后清理图片失败：" + rel)
                continue
            }
            count++
            bytes += size
        }
        FileLog.log(TAG, "会话 " + removed.id + " 删除后清理图片 " + count + " 个 / " + bytes + " 字节")
        return count to bytes
    }

    /** 附件目录总占用（字节）：设置页「附件占用」用，含已引用与未引用的全部文件。 */
    fun usageBytes(workspaceRoot: File): Long {
        val files = File(workspaceRoot, DIR).listFiles() ?: return 0L
        var total = 0L
        for (file in files) if (file.isFile) total += file.length()
        return total
    }

    /** 统计与清理共用的实现：delete = false 只数，true 才真删。 */
    private fun survey(sessions: List<ChatSession>, workspaceRoot: File, delete: Boolean): Pair<Int, Long> {
        val files = File(workspaceRoot, DIR).listFiles() ?: return 0 to 0L
        val referenced = referencedPaths(sessions)
        var count = 0
        var bytes = 0L
        for (file in files) {
            if (!file.isFile) continue
            val rel = DIR + "/" + file.name
            if (referenced.contains(rel)) continue
            val size = file.length()
            if (delete && !file.delete()) {
                FileLog.log(TAG, "未引用附件删除失败：" + rel)
                continue
            }
            count++
            bytes += size
        }
        FileLog.log(
            TAG,
            (if (delete) "清理未引用附件：" else "未引用附件统计：") + count + " 个 / " + bytes + " 字节"
        )
        return count to bytes
    }

    /**
     * [sessions] 引用到的相对路径集合。
     *
     * images 是结构化字段，直接读；普通附件没有字段，只写在提问正文的「【附件·…】」段里，
     * 所以正文里出现过 "uploads/xxx" 也算引用，否则清理会把用户还在用的附件一起删掉。
     * 工具调用参数中也可能出现这些路径（模型曾向 uploads 写入内容），一并计为引用，宁可少删不可多删。
     */
    private fun referencedPaths(sessions: List<ChatSession>): Set<String> {
        val out = LinkedHashSet<String>()
        for (session in sessions) {
            for (message in session.messages) {
                for (rel in message.images) out.add(normalize(rel))
                for (rel in pathsInText(message.content)) out.add(rel)
                for (call in message.toolCalls) {
                    for (rel in pathsInText(call.arguments)) out.add(rel)
                }
            }
        }
        return out
    }

    /** 从正文中提取 "uploads/xxx"：读取至空白或常见标点为止（正文即 prompt 原文）。 */
    private fun pathsInText(text: String): List<String> {
        val marker = DIR + "/"
        if (!text.contains(marker)) return emptyList()
        val out = ArrayList<String>()
        var start = text.indexOf(marker)
        while (start >= 0) {
            var end = start + marker.length
            while (end < text.length && !text[end].isWhitespace() && STOP_CHARS.indexOf(text[end]) < 0) end++
            out.add(text.substring(start, end))
            start = text.indexOf(marker, end)
        }
        return out
    }

    /** 路径统一为正斜杠：旧数据中可能存在反斜杠。 */
    private fun normalize(rel: String): String = rel.replace('\\', '/').trim()

    /** 归一化后的字节与扩展名：扩展名决定发送时 data URL 的类型，必须与真实编码一致。 */
    private class Encoded(val bytes: ByteArray, val ext: String)

    /**
     * 图片归一化的唯一实现（与 DSH 同口径）。
     *
     * 解码分两段：先用 inSampleSize 按 2 的幂粗降（省内存），再精确缩放到目标尺寸。
     * 只用 inSampleSize 的话，1569px 这种略超预算的图会被直接砍成 785px；两段式不会浪费分辨率。
     */
    private fun normalizeImage(context: Context, uri: Uri): Encoded {
        // 部分来源（file:// 分享、个别文件管理器）取不到 MIME，回退到按扩展名判断；
        // 否则 PNG/WebP 会被当成未知类型走进重编码，透明图白白变成有损 WebP。
        val mime = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()?.lowercase(Locale.US).orEmpty()
            .ifEmpty { mimeFromName(uri.lastPathSegment.orEmpty()) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImage(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            throw UploadFailure("无法解码这张图片，请换一张常见格式的图片。")
        }

        // 透传条件对应 DSH 的 canPassThroughNormalization：静态、方向正常、像素与字节都在预算内
        // ORIENTATION_UNDEFINED 等同「没有方向标记」：PNG 没有 eXIf 时部分机型给的是 0 而不是 1
        val orientation = readOrientation(context, uri)
        val upright = orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        val withinBudget = width.toLong() * height <= IMAGE_MAX_PIXELS &&
            maxOf(width, height) <= IMAGE_MAX_DIM
        if ((mime == "image/png" || mime == "image/webp") && withinBudget && upright) {
            // 超过 4MB 时 readCapped 返回 null，退回下面的重编码路径
            val raw = openImage(context, uri).use { readCapped(it, MAX_IMAGE_BYTES) }
            if (raw != null && !isAnimatedWebp(mime, raw)) {
                FileLog.log(TAG, "图片透传：" + mime + " " + width + "x" + height + "，" + raw.size + " 字节")
                return Encoded(raw, if (mime == "image/png") "png" else "webp")
            }
        }

        FileLog.log(
            TAG,
            "图片重编码：" + mime.ifEmpty { "未知类型" } + " " + width + "x" + height +
                "，方向=" + orientation
        )

        val (targetW, targetH) = targetSize(width, height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(width, height, targetW, targetH)
        }
        val decoded = openImage(context, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw UploadFailure("无法解码这张图片，请换一张图片重试。")
        var bitmap = rotate(decoded, orientation)
        try {
            val scaled = scaleToBudget(bitmap)
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
            return encode(bitmap)
        } finally {
            // 中转位图可能仍有数 MB，用完立即回收
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** 每次都重新开流：归一化要读多遍（尺寸、EXIF、原字节），ContentResolver 的流不能复用。 */
    private fun openImage(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw UploadFailure("无法读取这张图片，请重新选择。")

    /** 目标尺寸：总像素预算与长边兜底取更严的一档，且绝不放大。 */
    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val pixels = width.toLong() * height.toLong()
        val byPixels = if (pixels <= IMAGE_MAX_PIXELS) 1.0 else sqrt(IMAGE_MAX_PIXELS.toDouble() / pixels)
        val longest = maxOf(width, height)
        val byDim = if (longest <= IMAGE_MAX_DIM) 1.0 else IMAGE_MAX_DIM.toDouble() / longest
        val scale = minOf(byPixels, byDim)
        if (scale >= 1.0) return width to height
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /**
     * 采样率：取「解码后仍不小于目标尺寸」的最大 2 的幂。
     *
     * 解码位图约为目标像素的 1~4 倍，既不浪费太多内存，也不会像最长边一刀切那样把小图砍半。
     */
    private fun sampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    /** 精确缩放到预算内；已达标（或因不放大而无变化）时原样返回同一个实例。 */
    private fun scaleToBudget(bitmap: Bitmap): Bitmap {
        val (targetW, targetH) = targetSize(bitmap.width, bitmap.height)
        if (targetW == bitmap.width && targetH == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    /** 按文件名后缀推断图片 MIME；只为判断能否透传，不认识的一律返回空串。 */
    private fun mimeFromName(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            else -> ""
        }

    /** 读 EXIF 方向：PNG/WebP 也可能带 eXIf，读不到就按正常方向处理。 */
    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        openImage(context, uri).use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 按 EXIF 方向把位图摆正；需要变换时回收原图，避免两份位图同时占内存。 */
    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * 位图 → 字节：带 alpha 走 WebP（保透明），其余走 JPEG。
     *
     * 与 DSH 的 encodingLadder 一致：同一档位阶梯逐级尝试，首次 ≤4MB 即采用；
     * 全部超限时取最小的一份（用户已经选了这张图，丢弃的代价更大），仅记日志。
     */
    @Suppress("DEPRECATION")
    private fun encode(bitmap: Bitmap): Encoded {
        val hasAlpha = bitmap.hasAlpha()
        val format = if (hasAlpha) Bitmap.CompressFormat.WEBP else Bitmap.CompressFormat.JPEG
        val ext = if (hasAlpha) "webp" else "jpg"
        var smallest: ByteArray? = null
        for (quality in IMAGE_QUALITIES) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(format, quality, out)) {
                throw UploadFailure("压缩这张图片失败，请换一张图片重试。")
            }
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_IMAGE_BYTES) return Encoded(bytes, ext)
            if (bytes.size < (smallest?.size ?: Int.MAX_VALUE)) smallest = bytes
        }
        val bytes = smallest ?: throw UploadFailure("压缩这张图片失败，请换一张图片重试。")
        FileLog.log(TAG, "图片压到最低质量仍有 " + bytes.size + " 字节，超过 4MB 上限")
        return Encoded(bytes, ext)
    }

    /** 读取至多 [max] 字节；超过即返回 null（用于判断能否原字节透传，不把超大文件读进内存）。 */
    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    /** WebP 动图识别：前 64 字节里出现 ANIM 块即视为动图（动图不透传，统一取首帧重编码）。 */
    private fun isAnimatedWebp(mime: String, bytes: ByteArray): Boolean {
        if (mime != "image/webp" || bytes.size < 30) return false
        val limit = minOf(bytes.size, 64)
        for (index in 0..limit - 4) {
            // "ANIM" 的 ASCII：0x41 0x4E 0x49 0x4D
            if (bytes[index] == 0x41.toByte() &&
                bytes[index + 1] == 0x4E.toByte() &&
                bytes[index + 2] == 0x49.toByte() &&
                bytes[index + 3] == 0x4D.toByte()
            ) return true
        }
        return false
    }

    /**
     * 图片文件名 img-<ts>-<n>.<ext>：扩展名跟随真实编码（jpg/webp/png），
     * 发送时按扩展名决定 data URL 的类型；n 从 1 起仅在重名时递增。
     * 主名同样经过 [sanitize]：文件名清洗规则仅保留一处，规则变更时此处同步生效。
     */
    private fun uniqueImageName(dir: File, stamp: Long, ext: String): String {
        val base = sanitize("img-" + stamp)
        var index = 1
        while (true) {
            val candidate = base + "-" + index + "." + ext
            if (!File(dir, candidate).exists()) return candidate
            index++
        }
    }

    /** 显示名：provider 的 DISPLAY_NAME 优先，取不到退回 Uri 末段，再不行就叫 "file"。 */
    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        for (raw in listOf(fromProvider, uri.lastPathSegment)) {
            val text = raw?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return FALLBACK_NAME
    }

    /** 清洗：仅将路径分隔符与控制字符替换为下划线，中文与空格原样保留（模型按路径读取，支持中文）。 */
    private fun sanitize(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            builder.append(if (ch == '/' || ch == '\\' || ch in '\u0000'..'\u001f') '_' else ch)
        }
        var name = builder.toString().trim()
        if (name.length > MAX_NAME) name = name.substring(name.length - MAX_NAME)
        return name.ifEmpty { FALLBACK_NAME }
    }

    /** 可直接展示给用户的失败原因；其他异常统一包装，避免把系统错误串显示在界面上。 */
    private class UploadFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** 目录内重名时增加序号，保留扩展名：base-1.ext、base-2.ext…… */
    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        // 以点开头的隐藏文件没有「主名 + 扩展名」可分，整体当主名
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = base + "-" + index + ext
            if (!File(dir, candidate).exists()) return candidate
            index++
        }
    }
}
