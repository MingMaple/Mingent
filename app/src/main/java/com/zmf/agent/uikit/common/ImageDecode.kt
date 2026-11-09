package com.zmf.agent.uikit.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 按最长边采样解码。列表缩略图只需几十 dp 的像素，先读文件头再按需降采样，
 * 避免整幅原图进入内存。文件缺失或格式不支持时返回 null，由调用方退到底色。
 *
 * 内部读取文件，须在 IO 线程调用。
 */
fun decodeSampledBitmap(path: String, maxEdgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxEdgePx || bounds.outHeight / (sample * 2) >= maxEdgePx) {
        sample *= 2
    }
    return runCatching {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
