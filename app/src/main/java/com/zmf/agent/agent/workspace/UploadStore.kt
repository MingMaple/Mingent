package com.zmf.agent.agent.workspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.zmf.agent.agent.session.ChatSession
import com.zmf.agent.core.log.FileLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 系统文件选择器给的 Uri → 当前工作区里的真实文件。
 *
 * 为什么要复制：模型只能靠 read_file 读工作区里的相对路径，content:// 这种 Uri
 * 它既拿不到也用不了。复制到 <工作区>/uploads/ 下，relPath 就能直接喂给 read_file。
 *
 * 两个约束：单文件 50MB 上限（超了直接中止，不留半截文件）；
 * 落盘先写 `.part` 再 rename，中途失败/被杀都不会留下一个「看起来能用」的坏文件。
 */
object UploadStore {

    private const val TAG = "upload"

    /** 上传目录（相对工作区根；relPath 一律用正斜杠）。 */
    const val DIR = "uploads"

    /** 单文件上限 50MB。 */
    private const val MAX_BYTES = 50L * 1024L * 1024L

    /** 图片最长边上限：1568px 是主流多模态接口的推荐上限，再大只是白烧 token 和流量。 */
    private const val IMAGE_MAX_DIM = 1568

    /** 单张图片压缩后的目标上限 2MB：base64 还会再涨约 1/3，超过这个量请求体太大。 */
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    /** JPEG 质量档位，从高到低试，第一次压到 2MB 以内就收。 */
    private val IMAGE_QUALITIES = intArrayOf(85, 70, 50)

    private const val BUFFER_SIZE = 8 * 1024

    /** 显示名最长 60 字符，超了取后 60 个（后缀更重要，一眼能看出是什么文件）。 */
    private const val MAX_NAME = 60

    private const val FALLBACK_NAME = "file"

    /** 从正文里读 "uploads/xxx" 时的终止字符：空白之外还要在这些标点处断开。 */
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
            // 重名不覆盖：已有 myslides.pdf 就依次试 myslides-1.pdf、myslides-2.pdf……
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
            // 任何一步失败都只删自己写出来的 .part，绝不碰目录里已有的文件
            part?.delete()
            FileLog.log(TAG, "导入失败（" + uri + "）：" + (e.message ?: e.javaClass.simpleName))
            Result.failure(e)
        }
    }

    /**
     * 把 [uri] 指向的图片（系统相册/文件选择器给的）压成 JPEG 存进 [workspaceRoot]/uploads/。
     *
     * 为什么不像 [import] 那样原样复制：原图动辄 4000×3000、好几 MB，多模态接口既吃不准尺寸
     * 又要按 token 收费；这里统一降采样到最长边 ≤1568 再压到 2MB 以内，落盘的就是能直接发的样子。
     * 失败一律 Result.failure（不静默丢图），调用方负责在界面提示用户。
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
            // 跟 [import] 一样：只删自己写出来的 .part，不碰目录里已有的文件
            part?.delete()
            FileLog.log(TAG, "图片导入失败（" + uri + "）：" + (e.message ?: e.javaClass.simpleName))
            Result.failure(e)
        }
    }

    // ---- 未引用附件：统计与清理 ----

    /**
     * 只统计、不删：当前工作区 uploads/ 里没有被 [sessions] 引用的文件数与占用字节。
     *
     * [context] 暂时用不到，保留它是为了和 [import] / [importImage] 同一套调用口径：
     * 将来要在日志里带上下文信息时，不用再把所有调用点改一遍。
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
     * 删除会话后清理它引用的图片：同一工作区里没有别的会话再引用，才真删。
     *
     * 只认这条会话 images 里的路径（那一定是 [importImage] 写进 uploads/ 的图片）；
     * 普通附件只出现在正文里、判定不可靠，宁可留着让「清理未引用附件」统一处理。
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
            // 只认 uploads/ 这一层的文件：路径不在这个目录里（老数据 / 跨工作区）一律不动手
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
     * 所以正文里出现过 "uploads/xxx" 也算引用——否则清理会把用户还在用的附件一起删掉。
     * 工具调用的参数里也可能出现这些路径（模型自己往 uploads 里写过东西），一并算引用，宁少删不多删。
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

    /** 从正文里抠出 "uploads/xxx"：读到空白或常见标点为止（正文里就是 prompt 原文）。 */
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

    /** 路径统一成正斜杠：老数据里可能出现反斜杠。 */
    private fun normalize(rel: String): String = rel.replace('\\', '/').trim()

    /**
     * Uri → JPEG 字节：先只读尺寸算出采样率，再按采样率真解码。
     *
     * 分两次解码是因为 inJustDecodeBounds 只读文件头，能避免把 8000×6000 的原图整张读进内存
     * （低端机上直接 OOM）。降采样后再按 [IMAGE_QUALITIES] 从高到低压。
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
            // 最低质量仍超 2MB：接受这一版（用户已经选了图，丢掉比发大一点点更糟），只记日志
            FileLog.log(TAG, "图片压到最低质量仍有 " + last.size + " 字节，超过 2MB 上限")
            return last
        } finally {
            // 原图采样后的位图可能还有几 MB，用完立刻还给系统
            bitmap.recycle()
        }
    }

    /** 每次都重新开流：[encodeJpeg] 要读两遍，ContentResolver 给的流不能复用。 */
    private fun openImage(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("打不开这张图片")

    /**
     * 采样率：BitmapFactory 的 inSampleSize 是 2 的幂，所以只能按 2 倍递减，
     * 保证「缩小后最长边一定 ≤ [IMAGE_MAX_DIM]」。代价是 1569px 这种刚超一点的图会被砍半，
     * 换来的是解码内存可预测（不会出现只缩了一点反而 OOM 的情况）。
     */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > IMAGE_MAX_DIM) sample *= 2
        return sample
    }

    /**
     * 图片文件名 img-<ts>-<n>.jpg：时间戳保证一般不会撞上旧文件，n 从 1 起只在同名时递增。
     * 主名照样过 [sanitize]：文件名清洗规则只留一处，将来改规则这里一起生效。
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

    /** 清洗：只把路径分隔符和控制字符换成下划线，中文/空格原样保留（模型按路径读，中文没问题）。 */
    private fun sanitize(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            builder.append(if (ch == '/' || ch == '\\' || ch in '\u0000'..'\u001f') '_' else ch)
        }
        var name = builder.toString().trim()
        if (name.length > MAX_NAME) name = name.substring(name.length - MAX_NAME)
        return name.ifEmpty { FALLBACK_NAME }
    }

    /** 目录里撞名就加序号，扩展名保留：base-1.ext、base-2.ext…… */
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
