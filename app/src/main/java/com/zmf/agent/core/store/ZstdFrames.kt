package com.zmf.agent.core.store

import com.zmf.agent.core.log.FileLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream

/**
 * zstd 帧编解码（D1.3-B）。
 *
 * 文件＝帧的简单拼接，每帧自带外层定长帧头：
 *
 * ```
 * [frameLen: u32 小端][crc32: u32 小端][zstd frame bytes]
 * ```
 *
 * - `frameLen` 是本帧压缩后的字节数（不含 8 字节帧头）；`crc32` 对压缩字节做 `java.util.zip.CRC32`；
 * - zstd 帧内部再开 content checksum（zstd 自带 xxhash64），形成「外层 CRC32 + 帧内 checksum」双校验；
 * - 帧内是 UTF-8 的 JSONL：一行一个事件，末行也带换行。
 *
 * **与 DSH 的差异（有意为之，进论文「仍不同构」节）**：DSH 是纯 zstd 帧拼接、靠帧自带的 checksum；
 * 我方在帧外再加 8 字节长度＋CRC32。理由：Android 侧没有 zstd 的 seekable / frame-info API 直接给出
 * 「上一帧结束偏移」，而崩溃截断必须精确到帧边界；外层长度是最小代价的解法。
 * 语义（独立帧、可校验、可截尾）同构，编码不同。
 *
 * 本文件是**唯一接触 zstd API 的文件**：其余代码只见 ByteArray 与帧边界，不感知压缩实现。
 */
object ZstdFrames {

    private const val TAG = "session"

    /** 帧头字节数：frameLen + crc32，各 4 字节小端。 */
    const val HEADER_BYTES = 8

    /** 压缩级别（D1.3-B 定：3）。 */
    const val LEVEL = 3

    /** 一个解码后的帧。 */
    data class Frame(val payload: ByteArray)

    /**
     * 扫描结果。
     *
     * [validLength] 是「可信字节长度」：最后一个完整通过校验的帧的结束偏移。
     * [truncatedBytes] 大于 0 表示文件尾部存在半帧或坏帧，已在扫描时截断到该长度；
     * [frames] 按文件顺序给出全部可信帧的解压内容。
     */
    data class ScanResult(
        val frames: List<Frame>,
        val validLength: Long,
        val truncatedBytes: Int,
        /** 每个可信帧在文件中的起始偏移（与 [frames] 一一对应）：事件层按 seq 断号截断时用。 */
        val frameStarts: List<Long>
    )

    /**
     * 把一批 JSONL 文本压成一帧：外层帧头 + 带 content checksum 的 zstd 帧。
     *
     * 用 [ZstdOutputStream] 而不是一次性压缩 API：后者不开 content checksum，
     * 流式接口显式 [ZstdOutputStream.setChecksum] 才能开启帧内校验。
     */
    fun compressFrame(payload: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream(payload.size / 2 + 64)
        ZstdOutputStream(buffer, LEVEL).use { zos ->
            zos.setChecksum(true)
            zos.write(payload)
        }
        val compressed = buffer.toByteArray()
        val crc = CRC32()
        crc.update(compressed)
        val out = ByteArray(HEADER_BYTES + compressed.size)
        putUInt32(out, 0, compressed.size.toLong())
        putUInt32(out, 4, crc.value)
        System.arraycopy(compressed, 0, out, HEADER_BYTES, compressed.size)
        return out
    }

    /** 解压一帧；校验失败或内容损坏时抛异常（扫描端据此截断，不往上层漏）。 */
    fun decompressFrame(frame: ByteArray): ByteArray {
        if (frame.size < HEADER_BYTES) throw IllegalArgumentException("帧头不完整")
        val declared = readUInt32(frame, 0)
        val expectedCrc = readUInt32(frame, 4)
        if (declared != (frame.size - HEADER_BYTES).toLong()) {
            throw IllegalArgumentException("帧长不符：声明 " + declared + "，实有 " + (frame.size - HEADER_BYTES))
        }
        val body = frame.copyOfRange(HEADER_BYTES, frame.size)
        val crc = CRC32()
        crc.update(body)
        if (crc.value != expectedCrc) throw IllegalArgumentException("外层 CRC32 不符")
        ZstdInputStream(ByteArrayInputStream(body)).use { zis -> return zis.readAllBytes() }
        // ZstdInputStream 按帧内 checksum 校验：不一致时 read 抛 IOException
    }

    /**
     * 把一帧追加到文件末尾（原子性：先记长度，写完 fsync；任一步失败回滚到原长度，不留半帧）。
     *
     * 句柄只做「定位到末尾、追加、强制落盘」三件事：除截尾外从不回头写，
     * 这就是「已提交事件不重写」在字节层面的含义。
     */
    fun appendFrame(file: File, payload: ByteArray): Boolean {
        val frame = compressFrame(payload)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val raf = RandomAccessFile(file, "rw")
        val before = raf.length()
        try {
            raf.seek(before)
            raf.write(frame)
            raf.channel.force(true)
            return true
        } catch (e: Exception) {
            // 写入或 fsync 失败：回滚到写入前的长度，本帧作废（调用方自行重试或记账）
            try {
                raf.channel.truncate(before)
                raf.channel.force(true)
            } catch (ignored: Exception) {
            }
            FileLog.log(TAG, "帧追加失败，已回滚：" + file.name + "：" + (e.message ?: e.javaClass.simpleName))
            return false
        } finally {
            raf.close()
        }
    }

    /**
     * 顺序扫描全部帧，解出 [frames] 并返回可信长度。
     *
     * 崩溃恢复（D1.3-E）：读 8 字节帧头 → 剩余字节不足（尾部半帧）→ 截到该帧起点；
     * 读满后 CRC 不符 → 同样截到该帧起点；解压失败同样截断。每次截断记一行日志。
     * 截断是本函数的副作用（唯一允许改变文件的操作），后续追加只会接在可信长度之后。
     */
    fun scan(file: File): ScanResult {
        val data = if (file.isFile) file.readBytes() else ByteArray(0)
        val frames = ArrayList<Frame>()
        val starts = ArrayList<Long>()
        var offset = 0
        while (offset + HEADER_BYTES <= data.size) {
            val declared = readUInt32(data, offset)
            if (declared <= 0 || offset + HEADER_BYTES + declared > data.size) break // 半帧或长度异常
            val crc = CRC32()
            crc.update(data, offset + HEADER_BYTES, declared.toInt())
            val storedCrc = readUInt32(data, offset + 4)
            if (crc.value != storedCrc) break // 外层校验不符
            val frameEnd = (offset + HEADER_BYTES + declared).toInt()
            val frame = data.copyOfRange(offset, frameEnd)
            val payload = try {
                decompressFrame(frame)
            } catch (e: Exception) {
                break // 帧内 checksum 或 JSON 层损坏，由调用方按 seq 断号二次判定后截断
            }
            frames.add(Frame(payload))
            starts.add(offset.toLong())
            offset += HEADER_BYTES + declared.toInt()
        }
        val truncated = data.size - offset
        if (truncated > 0 && file.isFile) {
            truncateTo(file, offset.toLong())
            FileLog.log(TAG, "会话文件截断尾部 " + truncated + " 字节（帧 " + frames.size + "，" + file.name + "）")
        }
        return ScanResult(frames, offset.toLong(), truncated, starts)
    }

    /** 把文件截到 [length] 字节并落盘；不存在的文件不做任何事。 */
    fun truncateTo(file: File, length: Long) {
        if (!file.isFile) return
        val raf = RandomAccessFile(file, "rw")
        try {
            if (raf.length() > length) {
                raf.setLength(length)
                raf.channel.force(true)
            }
        } finally {
            raf.close()
        }
    }

    /**
     * 只读帧 0 的解压内容（不扫全文件）：备份导入端用它做
     * 「帧 0 可解压、CRC 通过、header.id 与文件名一致」三项校验（D1.3-G）。
     * 校验失败（半帧 / CRC 不符 / 解压失败 / 无帧）返回 null。
     */
    fun firstFramePayload(file: File): ByteArray? {
        if (!file.isFile) return null
        val raf = RandomAccessFile(file, "r")
        try {
            val len = raf.length()
            if (len < HEADER_BYTES) return null
            val head = ByteArray(HEADER_BYTES)
            raf.readFully(head)
            val declared = readUInt32(head, 0).toInt()
            if (declared <= 0 || HEADER_BYTES.toLong() + declared > len) return null
            val body = ByteArray(declared)
            raf.readFully(body)
            val crc = CRC32()
            crc.update(body)
            if (crc.value != readUInt32(head, 4)) return null
            return decompressFrame(head + body)
        } catch (e: Exception) {
            return null
        } finally {
            raf.close()
        }
    }

    /** u32 小端写入。 */
    private fun putUInt32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /** u32 小端读取（无符号）。 */
    private fun readUInt32(source: ByteArray, offset: Int): Long {
        val a = source[offset].toInt() and 0xFF
        val b = source[offset + 1].toInt() and 0xFF
        val c = source[offset + 2].toInt() and 0xFF
        val d = source[offset + 3].toInt() and 0xFF
        return (a or (b shl 8) or (c shl 16) or (d shl 24)).toLong() and 0xFFFFFFFFL
    }
}
