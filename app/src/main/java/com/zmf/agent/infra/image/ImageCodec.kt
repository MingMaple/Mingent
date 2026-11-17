package com.zmf.agent.infra.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * 文件头判定得到的图片信息。
 *
 * [mime] 由文件头而非扩展名得出；[bytes] 为文件字节数。
 */
data class ImageProbe(
    val mime: String,
    val width: Int,
    val height: Int,
    val bytes: Long
)

/**
 * 图片读取与编码。
 *
 * 全部方法都会访问磁盘，必须在 IO 线程调用。格式一律按文件头判定，不得信任扩展名。
 * 解码前必须拒绝超过 [MAX_PIXELS] 的图片；单文件 20 MB 上限由调用方判定。
 */
object ImageCodec {

    /** 允许解码的最大像素数（40 MP）。超过时 [dataUrl] 抛出 [IllegalArgumentException]。 */
    const val MAX_PIXELS: Long = 40_000_000L

    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_SIGNATURE = intArrayOf(0xFF, 0xD8, 0xFF)

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis"
    )

    /**
     * 按文件头判定图片格式与尺寸。
     *
     * 支持的格式为 PNG、JPEG、WebP 与 GIF；无法识别时返回 null。
     * 文件头属于受支持格式但内容不足以解析尺寸时，抛出 [IllegalArgumentException]。
     */
    fun probe(file: File): ImageProbe? {
        if (!file.isFile) return null
        val bytes = file.length()
        if (bytes <= 0L) return null
        val header = readHeader(file, HEADER_BYTES)
        if (startsWith(header, *PNG_SIGNATURE)) {
            if (header.size < 24) throw IllegalArgumentException("PNG 文件头不完整，无法读取尺寸")
            val width = intBE(header, 16)
            val height = intBE(header, 20)
            if (width <= 0 || height <= 0) {
                throw IllegalArgumentException("PNG 尺寸不合法：" + width + " × " + height)
            }
            return ImageProbe("image/png", width, height, bytes)
        }
        if (startsWith(header, *JPEG_SIGNATURE)) {
            val size = jpegSize(file)
                ?: throw IllegalArgumentException("JPEG 文件头不完整或已损坏，无法读取尺寸")
            return ImageProbe("image/jpeg", size.first, size.second, bytes)
        }
        if (isGif(header)) {
            val width = u16LE(header, 6)
            val height = u16LE(header, 8)
            if (width <= 0 || height <= 0) {
                throw IllegalArgumentException("GIF 尺寸不合法：" + width + " × " + height)
            }
            return ImageProbe("image/gif", width, height, bytes)
        }
        if (isWebp(header)) {
            return webpProbe(header, bytes)
        }
        return null
    }

    /**
     * 文件头是否属于图片容器（包含本类不支持的 BMP、TIFF、PSD、HEIC/AVIF、ICO）。
     *
     * 用于区分「不是图片」与「格式不受支持」；不得用于取尺寸。
     */
    fun isImageContainer(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val header = readHeader(file, HEADER_BYTES)
        if (startsWith(header, *PNG_SIGNATURE)) return true
        if (startsWith(header, *JPEG_SIGNATURE)) return true
        if (isGif(header)) return true
        if (isWebp(header)) return true
        // BMP
        if (startsWith(header, 0x42, 0x4D)) return true
        // TIFF
        if (startsWith(header, 0x49, 0x49, 0x2A, 0x00)) return true
        if (startsWith(header, 0x4D, 0x4D, 0x00, 0x2A)) return true
        // PSD
        if (startsWith(header, 0x38, 0x42, 0x50, 0x53)) return true
        // HEIF / AVIF
        if (header.size >= 12 && ascii(header, 4, 4) == "ftyp") {
            if (ascii(header, 8, 4) in HEIF_BRANDS) return true
        }
        // ICO
        if (startsWith(header, 0x00, 0x00, 0x01, 0x00)) return true
        return false
    }

    /**
     * 生成 `data:image/...;base64,...`。
     *
     * 先按 bounds 读取并以 2 的幂降采样，再按长边 [maxSide] 精确缩放。
     * PNG 与 GIF（首帧）在 [keepAlpha] 为真时编码为 PNG，其余情形编码为 JPEG，
     * JPEG 质量取 [quality]。失败返回 null；像素超过 40 MP 时抛出 [IllegalArgumentException]。
     */
    fun dataUrl(file: File, maxSide: Int, quality: Int, keepAlpha: Boolean): String? {
        val info = probe(file) ?: return null
        val pixels = info.width.toLong() * info.height.toLong()
        require(pixels <= MAX_PIXELS) {
            "图像像素 " + megaPixels(pixels) + " 超过上限 40 MP，必须先在读取前缩小"
        }

        val target = targetSize(info.width, info.height, maxSide)
        val targetWidth = target.first
        val targetHeight = target.second

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(info.width, info.height, targetWidth, targetHeight)
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        var bitmap = decoded
        try {
            if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
                val scaled = try {
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                } catch (e: OutOfMemoryError) {
                    return null
                }
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            val asPng = keepAlpha && (info.mime == "image/png" || info.mime == "image/gif")
            val format = if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val output = ByteArrayOutputStream()
            val compressed = try {
                bitmap.compress(format, quality.coerceIn(1, 100), output)
            } catch (e: Exception) {
                false
            }
            if (!compressed) return null
            val mime = if (asPng) "image/png" else "image/jpeg"
            return "data:" + mime + ";base64," +
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 按长边上限等比缩放后编码为图片字节。
     *
     * [format] 为 `png` 时使用 PNG；为 `jpeg` 时使用 JPEG 并丢弃透明通道，质量取 [quality]。
     * [maxSide] 为长边上限，绝不放大；缩放产生的临时位图在编码完成后回收，传入的 [bitmap] 不受影响。
     * 编码失败时抛出 [IllegalStateException]。
     */
    fun encode(bitmap: Bitmap, maxSide: Int, format: String, quality: Int): ByteArray {
        val target = targetSize(bitmap.width, bitmap.height, maxSide)
        var scaled = bitmap
        var created = false
        if (target.first != bitmap.width || target.second != bitmap.height) {
            scaled = Bitmap.createScaledBitmap(bitmap, target.first, target.second, true)
            created = scaled !== bitmap
        }
        return try {
            val asPng = format.equals("png", ignoreCase = true)
            val output = ByteArrayOutputStream()
            val compressed = scaled.compress(
                if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                quality.coerceIn(1, 100),
                output
            )
            check(compressed) { "图片编码失败：" + format }
            output.toByteArray()
        } finally {
            if (created && !scaled.isRecycled) scaled.recycle()
        }
    }

    /** 目标尺寸：长边不超过 [maxSide]，且绝不放大。 */
    private fun targetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val side = maxSide.coerceAtLeast(1)
        val longest = maxOf(width, height)
        if (longest <= side) return width to height
        val scale = side.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to
            maxOf(1, Math.round(height * scale).toInt())
    }

    /** 取「解码后仍不小于目标尺寸」的最大 2 的幂。 */
    private fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun webpProbe(header: ByteArray, bytes: Long): ImageProbe {
        if (header.size < 30) throw IllegalArgumentException("WebP 文件头不完整，无法读取尺寸")
        val chunk = ascii(header, 12, 4)
        val width: Int
        val height: Int
        when (chunk) {
            "VP8X" -> {
                width = read24LE(header, 24) + 1
                height = read24LE(header, 27) + 1
            }

            "VP8 " -> {
                if (header.size < 30 || (header[23].toInt() and 0xFF) != 0x9D) {
                    throw IllegalArgumentException("WebP（VP8）文件头不完整，无法读取尺寸")
                }
                width = u16LE(header, 26) and 0x3FFF
                height = u16LE(header, 28) and 0x3FFF
            }

            "VP8L" -> {
                if ((header[20].toInt() and 0xFF) != 0x2F) {
                    throw IllegalArgumentException("WebP（VP8L）文件头不完整，无法读取尺寸")
                }
                val value = (header[21].toInt() and 0xFF) or
                    ((header[22].toInt() and 0xFF) shl 8) or
                    ((header[23].toInt() and 0xFF) shl 16) or
                    ((header[24].toInt() and 0xFF) shl 24)
                width = (value and 0x3FFF) + 1
                height = ((value shr 14) and 0x3FFF) + 1
            }

            else -> throw IllegalArgumentException("WebP 变体不受支持：" + chunk)
        }
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("WebP 尺寸不合法：" + width + " × " + height)
        }
        return ImageProbe("image/webp", width, height, bytes)
    }

    /** 扫描 JPEG 段结构，读取 SOF 段中的宽高。 */
    private fun jpegSize(file: File): Pair<Int, Int>? {
        BufferedInputStream(file.inputStream()).use { input ->
            if (input.read() != 0xFF) return null
            if (input.read() != 0xD8) return null
            while (true) {
                var marker = input.read()
                if (marker < 0) return null
                if (marker != 0xFF) continue
                var code = input.read()
                while (code == 0xFF) code = input.read()
                if (code < 0) return null
                when (code) {
                    0xD8, 0xD9 -> continue
                    0x01, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7 -> continue
                    else -> {
                        val length = readU16(input)
                        if (length < 2) return null
                        val isSof = code in 0xC0..0xCF &&
                            code != 0xC4 && code != 0xC8 && code != 0xCC
                        if (isSof) {
                            input.read()
                            val height = readU16(input)
                            val width = readU16(input)
                            return if (width > 0 && height > 0) width to height else null
                        }
                        skipFully(input, length - 2)
                    }
                }
            }
        }
    }

    private fun readU16(input: InputStream): Int {
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) return -1
        return (high shl 8) or low
    }

    private fun skipFully(input: InputStream, count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) return
            remaining--
        }
    }

    private fun readHeader(file: File, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        file.inputStream().use { input ->
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    private fun startsWith(header: ByteArray, vararg values: Int): Boolean {
        if (header.size < values.size) return false
        for (index in values.indices) {
            if ((header[index].toInt() and 0xFF) != (values[index] and 0xFF)) return false
        }
        return true
    }

    private fun isGif(header: ByteArray): Boolean {
        if (header.size < 10) return false
        val signature = ascii(header, 0, 6)
        return signature == "GIF87a" || signature == "GIF89a"
    }

    private fun isWebp(header: ByteArray): Boolean =
        header.size >= 12 && ascii(header, 0, 4) == "RIFF" && ascii(header, 8, 4) == "WEBP"

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        String(bytes, offset, count, Charsets.US_ASCII)

    private fun intBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun u16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun read24LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)

    private fun megaPixels(pixels: Long): String =
        String.format(Locale.US, "%.1f MP", pixels / 1_000_000.0)

    /** 文件头读取长度：足以覆盖 WebP 的 VP8X / VP8 / VP8L 头部。 */
    private const val HEADER_BYTES = 64
}
