package com.zmf.agent.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 帧层回归（D1.3-B/D1.3-E）：写入 → 读取回放一致、半帧截断、CRC 破坏截断。
 * 纯 JVM：zstd-jni 的 testImplementation（jar artifact，带桌面原生库）。
 */
class ZstdFramesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 多帧写入 → scan 全部解出且内容逐帧一致。 */
    @Test
    fun writeAndScanRoundtrip() {
        val file = tmp.newFile("s.jsonl")
        val payloads = listOf("第一帧：abc\n", "second frame\nwith lines\n", "中文帧 ✅\n\n尾部换行\n")
        for (payload in payloads) {
            assertTrue(ZstdFrames.appendFrame(file, payload.toByteArray(Charsets.UTF_8)))
        }
        val scan = ZstdFrames.scan(file)
        assertEquals(payloads.size, scan.frames.size)
        assertEquals(file.length(), scan.validLength)
        assertEquals(0, scan.truncatedBytes)
        assertEquals(payloads.size, scan.frameStarts.size)
        // 帧起点互相衔接：帧 0 从 0 开始，每帧起点 = 上一帧起点 + 上一帧长度
        assertEquals(0L, scan.frameStarts[0])
        for (i in payloads.indices) {
            assertEquals(payloads[i], String(scan.frames[i].payload, Charsets.UTF_8))
        }
    }

    /** 尾部半帧：截掉最后 3 字节后 scan 只损失半帧，其余帧完整，文件被截回帧边界。 */
    @Test
    fun truncatedTailIsCutToFrameBoundary() {
        val file = tmp.newFile("s.jsonl")
        val full = "frame one\n"
        val second = "frame two\n"
        assertTrue(ZstdFrames.appendFrame(file, full.toByteArray(Charsets.UTF_8)))
        val afterFirst = file.length()
        assertTrue(ZstdFrames.appendFrame(file, second.toByteArray(Charsets.UTF_8)))
        // 人为撕裂：截掉 3 字节
        ZstdFrames.truncateTo(file, file.length() - 3)
        val scan = ZstdFrames.scan(file)
        assertEquals(1, scan.frames.size)
        assertEquals(full, String(scan.frames[0].payload, Charsets.UTF_8))
        assertEquals(afterFirst, scan.validLength)
        // 截断是 scan 的副作用：文件回到帧边界，后续追加接在可信长度之后
        assertEquals(afterFirst, file.length())
        assertTrue(ZstdFrames.appendFrame(file, "frame three\n".toByteArray(Charsets.UTF_8)))
        assertEquals(2, ZstdFrames.scan(file).frames.size)
    }

    /** 外层 CRC 破坏：该帧连同其后的字节一并截掉。 */
    @Test
    fun corruptedCrcIsTruncated() {
        val file = tmp.newFile("s.jsonl")
        assertTrue(ZstdFrames.appendFrame(file, "good frame\n".toByteArray(Charsets.UTF_8)))
        val goodEnd = file.length()
        assertTrue(ZstdFrames.appendFrame(file, "bad frame\n".toByteArray(Charsets.UTF_8)))
        // 破坏第二帧正文中的一个字节（帧头后 1 字节）
        val bytes = file.readBytes()
        bytes[(goodEnd + ZstdFrames.HEADER_BYTES).toInt()] =
            (bytes[(goodEnd + ZstdFrames.HEADER_BYTES).toInt()].toInt() xor 0x5A).toByte()
        file.writeBytes(bytes)
        val scan = ZstdFrames.scan(file)
        assertEquals(1, scan.frames.size)
        assertEquals(goodEnd, scan.validLength)
        assertEquals(goodEnd, file.length())
    }

    /** 解压失败（帧内 checksum 破坏但外层 CRC 巧合未破）同样按帧截断。 */
    @Test
    fun undecodableFrameIsTruncated() {
        val file = tmp.newFile("s.jsonl")
        assertTrue(ZstdFrames.appendFrame(file, "ok\n".toByteArray(Charsets.UTF_8)))
        val goodEnd = file.length()
        // 构造一个外层 CRC 正确但 zstd 内容损坏的帧
        val payload = "some payload".toByteArray(Charsets.UTF_8)
        val frame = ZstdFrames.compressFrame(payload)
        // 破坏压缩字节中的一个（zstd 帧内 checksum 必然不符），再重算外层 CRC 使其通过
        val fixed = frame.copyOf()
        fixed[ZstdFrames.HEADER_BYTES + 1] =
            (fixed[ZstdFrames.HEADER_BYTES + 1].toInt() xor 0x5A).toByte()
        val crc = java.util.zip.CRC32()
        crc.update(fixed, ZstdFrames.HEADER_BYTES, fixed.size - ZstdFrames.HEADER_BYTES)
        putUInt32(fixed, 4, crc.value)
        file.appendBytes(fixed)
        val scan = ZstdFrames.scan(file)
        assertEquals(1, scan.frames.size)
        assertEquals(goodEnd, scan.validLength)
    }

    /** 空文件与不存在的文件：scan 返回空结果，不做任何截断。 */
    @Test
    fun emptyOrMissingFileScansClean() {
        val empty = tmp.newFile("empty.jsonl")
        val scan = ZstdFrames.scan(empty)
        assertEquals(0, scan.frames.size)
        assertEquals(0, scan.truncatedBytes)
        val missing = File(tmp.root, "missing.jsonl")
        val scan2 = ZstdFrames.scan(missing)
        assertEquals(0, scan2.frames.size)
    }

    /** firstFramePayload：只读帧 0；帧 0 半帧/损坏返回 null（备份导入校验用）。 */
    @Test
    fun firstFramePayloadValidation() {
        val file = tmp.newFile("s.jsonl")
        assertNull(ZstdFrames.firstFramePayload(file))
        assertTrue(ZstdFrames.appendFrame(file, "header line\n".toByteArray(Charsets.UTF_8)))
        val firstEnd = file.length()
        assertTrue(ZstdFrames.appendFrame(file, "event\n".toByteArray(Charsets.UTF_8)))
        val first = ZstdFrames.firstFramePayload(file)
        assertNotNull(first)
        assertEquals("header line\n", String(first!!, Charsets.UTF_8))
        // 尾部帧被撕裂（截掉末尾 2 字节伤的是帧 1）：帧 0 仍完整，照常读出
        ZstdFrames.truncateTo(file, file.length() - 2)
        assertNotNull(ZstdFrames.firstFramePayload(file))
        // 帧 0 本身不完整（长度字段声明超过实有）：判 null
        ZstdFrames.truncateTo(file, firstEnd - 2)
        assertNull(ZstdFrames.firstFramePayload(file))
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
