package com.zmf.agent.infra.preview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

/** PDF 分页渲染：整份文件只开一次，每页渲染完立刻关闭（PdfRenderer 同时只留一页，且非线程安全）。 */
class PdfDocument(file: File) : Closeable {

    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private val renderer: PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        // 构造失败时调用方拿不到实例，close() 不会执行，此处自行释放文件描述符
        runCatching { descriptor.close() }
        throw e
    }

    val pageCount: Int = renderer.pageCount

    /** 单页位图像素上限：4 百万像素约 16 MB 一位图，超过就按比例缩，宁可糊也不 OOM。 */
    private val maxPixels = 4_000_000L

    /** 页面宽高比（高 / 宽）；打不开时按 A4 竖版兜底。 */
    fun aspectOf(index: Int): Float {
        return runCatching {
            synchronized(this) {
                renderer.openPage(index).use { page -> page.height.toFloat() / page.width.toFloat() }
            }
        }.getOrDefault(1.414f)
    }

    /** 按目标宽度渲染一页；宽度按位图上限再收一次。 */
    fun renderPage(index: Int, widthPx: Int): Bitmap? {
        if (widthPx <= 0 || index < 0 || index >= pageCount) return null
        return runCatching {
            synchronized(this) {
                renderer.openPage(index).use { page ->
                    var width = widthPx
                    var height = (page.height.toFloat() / page.width.toFloat() * width).toInt()
                        .coerceAtLeast(1)
                    val pixels = width.toLong() * height
                    if (pixels > maxPixels) {
                        val shrink = sqrt(maxPixels.toDouble() / pixels).toFloat()
                        width = (width * shrink).toInt().coerceAtLeast(1)
                        height = (height * shrink).toInt().coerceAtLeast(1)
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PDF 页面背景本身透明：不铺白，深色主题下会与底色混成一片灰
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { synchronized(this) { renderer.close() } }
        runCatching { descriptor.close() }
    }
}
