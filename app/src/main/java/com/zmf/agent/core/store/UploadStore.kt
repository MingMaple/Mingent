package com.zmf.agent.core.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.log.FileLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

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

    /** 单文件上限 50MB。 */
    private const val MAX_BYTES = 50L * 1024L * 1024L

    /** 图片最长边上限：1568px 是主流多模态接口的推荐上限，更大只会额外消耗 token 与流量。 */
    private const val IMAGE_MAX_DIM = 1568

    /** 单张图片压缩后的目标上限 2MB：base64 还会再涨约 1/3，超过这个量请求体太大。 */
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    /** JPEG 质量档位，由高到低尝试，首次压缩至 2MB 以内即采用。 */
    private val IMAGE_QUALITIES = intArrayOf(85, 70, 50)

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
                ?: throw IOException("打不开这个文件")
            var size = 0L
            input.use { source ->
                temp.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        // 边写边计字节：超过上限立刻中止，不把整个大文件落完再判断
                        size += read
                        if (size > MAX_BYTES) throw IOException("文件超过 50MB")
                        sink.write(buffer, 0, read)
                    }
                    sink.flush()
                }
            }
            if (!temp.renameTo(target)) throw IOException("重命名失败：" + target.name)
            part = null

            val relPath = DIR + "/" + name
            FileLog.log(TAG, "已导入 " + relPath + "（" + size + " 字节，来源 " + uri + "）")
            Result.success(Imported(name = name, relPath = relPath, size = size))
        } catch (e: Exception) {
            // 任一步骤失败仅删除本次写入的 .part，不删除目录中已有文件
            part?.delete()
            FileLog.log(TAG, "导入失败（" + uri + "）：" + (e.message ?: e.javaClass.simpleName))
            Result.failure(e)
        }
    }

    /**
     * 把 [uri] 指向的图片（系统相册/文件选择器给的）压成 JPEG 存进 [workspaceRoot]/uploads/。
     *
     * 与原样复制（[import]）的区别：原图常见尺寸为 4000×3000、体积数 MB，多模态接口对尺寸有限制
     * 且按 token 计费；此处统一降采样至最长边 ≤1568 并压缩至 2MB 以内，落盘内容可直接发送。
     * 失败一律返回 Result.failure（不静默丢弃图片），调用方负责在界面提示用户。
     */
    fun importImage(context: Context, uri: Uri, workspaceRoot: File): Result<Imported> {
        var part: File? = null
        return try {
            val dir = File(workspaceRoot, DIR)
            if (!dir.exists()) dir.mkdirs()
            val jpeg = encodeJpeg(context, uri)
            val name = uniqueImageName(dir, System.currentTimeMillis())
            val target = File(dir, name)
            val temp = File(dir, name + ".part")
            part = temp

            temp.writeBytes(jpeg)
            if (!temp.renameTo(target)) throw IOException("重命名失败：" + target.name)
            part = null

            val relPath = DIR + "/" + name
            FileLog.log(TAG, "已导入图片 " + relPath + "（" + jpeg.size + " 字节，来源 " + uri + "）")
            Result.success(Imported(name = name, relPath = relPath, size = jpeg.size.toLong()))
        } catch (e: Exception) {
            // 与 [import] 一致：仅删除本次写入的 .part，不删除目录中已有文件
            part?.delete()
            FileLog.log(TAG, "图片导入失败（" + uri + "）：" + (e.message ?: e.javaClass.simpleName))
            Result.failure(e)
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

    /**
     * Uri → JPEG 字节：先只读尺寸算出采样率，再按采样率真解码。
     *
     * 两次解码的原因：inJustDecodeBounds 仅读取文件头，可避免将 8000×6000 的原图整体读入内存
     * （低端设备会直接 OOM）。降采样后再按 [IMAGE_QUALITIES] 由高到低压缩。
     */
    private fun encodeJpeg(context: Context, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImage(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("这不是能解码的图片")

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = openImage(context, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("图片解码失败")
        try {
            var last = ByteArray(0)
            for (quality in IMAGE_QUALITIES) {
                val out = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    throw IOException("图片编码失败")
                }
                last = out.toByteArray()
                if (last.size <= MAX_IMAGE_BYTES) return last
            }
            // 最低质量仍超过 2MB 时采用该版本（用户已选择图片，舍弃的代价更大），仅记录日志
            FileLog.log(TAG, "图片压到最低质量仍有 " + last.size + " 字节，超过 2MB 上限")
            return last
        } finally {
            // 原图采样后的位图可能仍有数 MB，用完立即回收
            bitmap.recycle()
        }
    }

    /** 每次都重新开流：[encodeJpeg] 要读两遍，ContentResolver 给的流不能复用。 */
    private fun openImage(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("打不开这张图片")

    /**
     * 采样率：BitmapFactory 的 inSampleSize 是 2 的幂，所以只能按 2 倍递减，
     * 保证「缩小后最长边一定 ≤ [IMAGE_MAX_DIM]」。代价是 1569px 这类略超限的图片会被缩小一半，
     * 换来的是解码内存可预测（不会出现仅少量缩小反而导致 OOM 的情况）。
     */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > IMAGE_MAX_DIM) sample *= 2
        return sample
    }

    /**
     * 图片文件名 img-<ts>-<n>.jpg：时间戳通常可避免与已有文件同名，n 从 1 起仅在重名时递增。
     * 主名同样经过 [sanitize]：文件名清洗规则仅保留一处，规则变更时此处同步生效。
     */
    private fun uniqueImageName(dir: File, stamp: Long): String {
        val base = sanitize("img-" + stamp)
        var index = 1
        while (true) {
            val candidate = base + "-" + index + ".jpg"
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
