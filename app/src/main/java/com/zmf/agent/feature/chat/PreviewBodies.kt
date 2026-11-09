package com.zmf.agent.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.model.FileKind
import com.zmf.agent.infra.preview.PdfDocument
import com.zmf.agent.infra.preview.PreviewReader
import com.zmf.agent.uikit.markdown.MarkdownText
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_PDF_BYTES = 40L * 1024 * 1024
private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024

/** docx 上限：再大就只提示导出，避免一次把几十兆塞进 WebView。 */
private const val MAX_DOCX_BYTES = 8L * 1024 * 1024

/** 文本正文：按页累积加载，Markdown 走统一渲染器，代码与非 Markdown 文本用等宽带行号。 */
@Composable
internal fun TextBody(source: PreviewSource, markdown: Boolean, modifier: Modifier = Modifier) {
    // 切标签回来时沿用已经读出的部分：不重读，也不把滚动位置拉回顶部（位置由外层按标签保存）
    val cached = remember(source.path, source.version) { PreviewTextCache.get(source.path, source.version) }
    var text by remember(source.path, source.version) { mutableStateOf(cached?.text.orEmpty()) }
    var nextLine by remember(source.path, source.version) { mutableStateOf(cached?.nextLine ?: 0) }
    var eof by remember(source.path, source.version) { mutableStateOf(cached?.eof ?: false) }
    var loading by remember(source.path, source.version) { mutableStateOf(cached == null) }
    var binary by remember(source.path, source.version) { mutableStateOf(cached?.binary ?: false) }
    val scope = rememberCoroutineScope()
    val onSurface = MaterialTheme.colorScheme.onSurface

    fun cache() {
        if (!binary) PreviewTextCache.put(source.path, PreviewText(source.version, text, nextLine, eof, binary))
    }

    suspend fun loadPage() {
        loading = true
        val page = withContext(Dispatchers.IO) { PreviewReader.readTextPage(source.open, nextLine) }
        if (page == null) {
            // 读到 NUL 或读失败：只有第一页时说明不是文本，之后的页说明文件被改动
            if (nextLine == 0) binary = true
            loading = false
            return
        }
        if (page.lines.isNotEmpty()) {
            text = if (text.isEmpty()) {
                page.lines.joinToString("\n")
            } else {
                text + "\n" + page.lines.joinToString("\n")
            }
        }
        nextLine = page.nextLine
        eof = page.eof
        loading = false
        cache()
    }

    LaunchedEffect(source.path, source.version) { if (cached == null) loadPage() }

    when {
        binary -> PreviewNotice(
            title = "这个文件无法按文本显示",
            detail = "它的内容不是纯文本。如需查看，请用其他应用打开。",
            modifier = modifier
        )
        else -> Column(modifier = modifier) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (markdown) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        MarkdownText(text = text, fontSize = 15.sp, color = onSurface)
                        // 「加载更多」挂在正文末尾：滑到已读内容的最下方才出现，不占版面、不挡视线
                        if (!eof) LoadMoreRow(loading, nextLine) { scope.launch { loadPage() } }
                    }
                } else {
                    // 代码：行号列固定，只有代码体横向滑动。
                    // 关闭自动换行，一个逻辑行就是一行：否则长行折行后行号会与内容错位
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(modifier = Modifier.padding(vertical = 12.dp)) {
                            if (source.kind == FileKind.CODE) {
                                // 行号列不参与横滑：长行向右滚走时，行号必须留在原地
                                Text(
                                    text = remember(text) { lineNumbers(text) },
                                    modifier = Modifier.padding(start = 14.dp),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = UiColors.subText.copy(alpha = 0.7f),
                                    softWrap = false,
                                    textAlign = TextAlign.End
                                )
                                Spacer(Modifier.width(10.dp))
                                val hScroll = rememberScrollState()
                                Box(modifier = Modifier.weight(1f)) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .horizontalScroll(hScroll)
                                            .padding(end = 14.dp)
                                    ) {
                                        Text(
                                            text = text,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = onSurface,
                                            softWrap = false
                                        )
                                    }
                                    // 右缘渐隐：长行被裁掉时，用户才知道还能横向滑动
                                    if (hScroll.value < hScroll.maxValue) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .fillMaxHeight()
                                                .width(24.dp)
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(
                                                            Color.Transparent,
                                                            MaterialTheme.colorScheme.background
                                                        )
                                                    )
                                                )
                                        )
                                    }
                                }
                            } else {
                                // 纯文本按行宽折行：普通文章没有「不该断」的语义，
                                // 像代码那样裁到屏幕外，读者只会以为文件被截断了
                                Text(
                                    text = text,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 14.dp),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = onSurface
                                )
                            }
                        }
                        if (!eof) LoadMoreRow(loading, nextLine) { scope.launch { loadPage() } }
                    }
                }
                if (loading && text.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** 分页按钮：贴在已读内容的末尾，随正文滚走；滑到底才看得到，不占固定版面。 */
@Composable
private fun LoadMoreRow(loading: Boolean, nextLine: Int, onLoad: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = onLoad, enabled = !loading) {
            Text(
                text = if (loading) "正在读取…" else "加载更多（已读 $nextLine 行）",
                fontSize = 13.sp,
                color = UiColors.accentFg
            )
        }
    }
}

/** 行号列：与代码体逐行对齐，等宽字体与同一行高是唯一前提。 */
private fun lineNumbers(text: String): String {
    val count = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
    return (1..count).joinToString("\n") { it.toString() }
}

/** docx 正文：体积上限内整份读出，交给 mammoth 转 HTML 显示。 */
@Composable
internal fun DocxBody(source: PreviewSource, modifier: Modifier = Modifier) {
    var bytes by remember(source.path, source.version) { mutableStateOf<ByteArray?>(null) }
    var failure by remember(source.path, source.version) { mutableStateOf<String?>(null) }
    LaunchedEffect(source.path, source.version) {
        val loaded = withContext(Dispatchers.IO) {
            if (source.size > MAX_DOCX_BYTES) null else PreviewReader.readBytes(source.open, MAX_DOCX_BYTES)
        }
        if (loaded == null) {
            failure = if (source.size > MAX_DOCX_BYTES) {
                "文件超过 8 MB，应用内无法解析。如需查看，请用其他应用打开。"
            } else {
                "读不到这个文件，它可能已被删除或移动。"
            }
        } else {
            bytes = loaded
        }
    }
    when {
        failure != null -> PreviewNotice(title = "这个文档打不开", detail = failure!!, modifier = modifier)
        bytes == null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        else -> DocxHtmlView(bytes = bytes!!, modifier = modifier)
    }
}

/**
 * PDF：整份开一次，页位图按需渲染；页面按纵向序列排，未渲染的页留 3:4 占位。
 *
 * [onPage] 把「当前第几页 / 共几页」回传给页面顶栏：翻到第三页时，用户要知道自己在哪。
 */
@Composable
internal fun PdfBody(
    source: PreviewSource,
    modifier: Modifier = Modifier,
    onPage: ((Int, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    if (source.size > MAX_PDF_BYTES) {
        PreviewNotice(
            title = "这份 PDF 无法在应用内打开",
            detail = "文件超过 40 MB。如需查看，请用其他应用打开。",
            modifier = modifier
        )
        return
    }
    // SAF 目录里的 PDF 没有可直接打开的本地路径：先复制到缓存再交给 PdfRenderer；复制放到 IO 线程
    val prepared by produceState(PreparedPdf(source.localFile, source.localFile != null), source.path) {
        if (value.file == null) {
            value = PreparedPdf(withContext(Dispatchers.IO) { cacheCopy(context, source) }, settled = true)
        }
    }
    val local = prepared.file
    if (local == null) {
        if (prepared.settled) {
            PreviewNotice(
                title = "这份 PDF 打不开",
                detail = "读不到这个文件，它可能已被删除或移动。",
                modifier = modifier
            )
        } else {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }
        return
    }
    PdfViewer(file = local, initialFraction = 0f, modifier = modifier, onPage = onPage)
}

/**
 * PDF 本地文件准备结果：[settled] 为 true 表示复制已结束，[file] 为 null 即确实读不到。
 */
private class PreparedPdf(val file: File?, val settled: Boolean)

/**
 * PDF 视图本体：docx 转换产物与 pdf 文件共用。
 *
 * [initialFraction] 是进入时的阅读进度（0–1），用于从文本态换到 PDF 时大致落在同一位置。
 */
@Composable
internal fun PdfViewer(
    file: File,
    initialFraction: Float,
    modifier: Modifier = Modifier,
    onPage: ((Int, Int) -> Unit)? = null
) {
    var document by remember(file.path) { mutableStateOf<PdfDocument?>(null) }
    var failure by remember(file.path) { mutableStateOf(false) }
    LaunchedEffect(file.path) {
        val loaded = withContext(Dispatchers.IO) { runCatching { PdfDocument(file) }.getOrNull() }
        if (loaded == null) failure = true else document = loaded
    }
    DisposableEffect(file.path) {
        onDispose { document?.close() }
    }
    val doc = document
    when {
        failure -> PreviewNotice(
            title = "这份 PDF 打不开",
            detail = "它可能已加密、需要密码，或文件已损坏。如需查看，请用其他应用打开。",
            modifier = modifier
        )
        doc == null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(doc, initialFraction) {
                if (initialFraction > 0f) {
                    val target = (initialFraction * doc.pageCount).toInt()
                        .coerceIn(0, doc.pageCount - 1)
                    listState.scrollToItem(target)
                }
            }
            LaunchedEffect(doc, listState) {
                onPage?.invoke(listState.firstVisibleItemIndex, doc.pageCount)
                snapshotFlow { listState.firstVisibleItemIndex }
                    .collect { onPage?.invoke(it, doc.pageCount) }
            }
            LazyColumn(modifier = modifier, state = listState) {
                items(doc.pageCount) { index -> PdfPageView(doc, index) }
            }
        }
    }
}

@Composable
private fun PdfPageView(document: PdfDocument, index: Int) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val widthPx = with(density) { (maxWidth - 16.dp).roundToPx() }
        var bitmap by remember(document, index, widthPx) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(document, index, widthPx) {
            bitmap = withContext(Dispatchers.IO) { document.renderPage(index, widthPx) }
        }
        val page = bitmap
        if (page == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.707f)
                    .padding(horizontal = 8.dp)
                    .background(UiColors.stroke.copy(alpha = 0.18f))
            )
        } else {
            Image(
                bitmap = page.asImageBitmap(),
                contentDescription = "第 " + (index + 1) + " 页",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    // 一像素描边：白页贴在浅色底上，没有边就看不出页的边界
                    .border(1.dp, UiColors.stroke.copy(alpha = 0.55f)),
                contentScale = ContentScale.FillWidth
            )
        }
    }
}

/** 图片：先量尺寸再按屏宽采样解码，避免一张手机原图直接按原始分辨率进内存。 */
@Composable
internal fun ImageBody(source: PreviewSource, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val targetPx = with(density) { maxWidth.roundToPx() }
        var bitmap by remember(source.path) { mutableStateOf<Bitmap?>(null) }
        var failure by remember(source.path) { mutableStateOf(false) }
        LaunchedEffect(source.path) {
            bitmap = withContext(Dispatchers.IO) {
                if (source.size > MAX_IMAGE_BYTES) return@withContext null
                decodeSampled(source, targetPx)
            }
            failure = bitmap == null
        }
        val image = bitmap
        when {
            failure -> PreviewNotice(
                title = "这张图片打不开",
                detail = if (source.size > MAX_IMAGE_BYTES) {
                    "图片超过 24 MB。如需查看，请用其他应用打开。"
                } else {
                    "无法识别它的格式，文件可能已损坏。如需查看，请用其他应用打开。"
                },
                modifier = Modifier.fillMaxSize()
            )
            image == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            else -> {
                // 双指缩放 + 双击在 1 倍与 2.5 倍之间切换：长截图铺满宽度后字太小，
                // 不能放大等于只能凑近看。放大后才允许平移，且平移量夹在图片边界内
                var scale by remember(source.path) { mutableFloatStateOf(1f) }
                var offset by remember(source.path) { mutableStateOf(Offset.Zero) }
                // 双击变焦走弹簧动画：直接改值会瞬切，与查看器的双击手感不一致
                var zoomToken by remember(source.path) { mutableIntStateOf(0) }
                val scope = rememberCoroutineScope()
                val maxScale = 6f
                // 像素尺寸在组合里换好：手势回调不在 Density 作用域内
                val boxWidthPx = with(density) { maxWidth.toPx() }
                val boxHeightPx = with(density) { maxHeight.toPx() }
                /** 缩放与平移同时过渡，视觉上是一次连续的变焦；手势 / 再次双击可通过 token 作废旧动画。 */
                fun animateZoom(targetScale: Float, targetOffset: Offset) {
                    val token = ++zoomToken
                    val spec = spring<Float>(dampingRatio = 1f, stiffness = 420f)
                    scope.launch {
                        coroutineScope {
                            launch {
                                animate(scale, targetScale, animationSpec = spec) { value, _ ->
                                    if (token == zoomToken) scale = value
                                }
                            }
                            launch {
                                animate(offset.x, targetOffset.x, animationSpec = spec) { value, _ ->
                                    if (token == zoomToken) offset = Offset(value, offset.y)
                                }
                            }
                            launch {
                                animate(offset.y, targetOffset.y, animationSpec = spec) { value, _ ->
                                    if (token == zoomToken) offset = Offset(offset.x, value)
                                }
                            }
                        }
                    }
                }
                val transform = rememberTransformableState { zoom, pan, _ ->
                    // 手势接管时立刻止步，避免动画与捏合互相拉扯
                    zoomToken++
                    val next = (scale * zoom).coerceIn(1f, maxScale)
                    val boundX = boxWidthPx * (next - 1f) / 2f
                    val boundY = boxHeightPx * (next - 1f) / 2f
                    scale = next
                    offset = if (next <= 1f) {
                        Offset.Zero
                    } else {
                        Offset(
                            (offset.x + pan.x).coerceIn(-boundX, boundX),
                            (offset.y + pan.y).coerceIn(-boundY, boundY)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 放大的图不能溢出自己的格子：否则会盖住顶栏与标签条
                        .clipToBounds()
                        .pointerInput(source.path) {
                            detectTapGestures(
                                onDoubleTap = {
                                    animateZoom(if (scale > 1f) 1f else 2.5f, Offset.Zero)
                                }
                            )
                        }
                        .transformable(transform),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = source.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }
    }
}

/** 两遍解码：第一遍只量尺寸，第二遍按 2 的幂采样，内存占用降到目标宽度的量级。 */
private fun decodeSampled(source: PreviewSource, targetWidthPx: Int): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val width = bounds.outWidth
        if (width > 0 && targetWidthPx > 0) {
            while (width / (sample * 2) >= targetWidthPx) sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        source.open().use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}

/** SAF 目录里的 PDF 没有可直接打开的本地路径：先复制到缓存再交给 PdfRenderer。 */
private fun cacheCopy(context: Context, source: PreviewSource): File? {
    return runCatching {
        val dir = File(context.cacheDir, "preview-pdf").apply { mkdirs() }
        val target = File(dir, source.name)
        source.open().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        target.takeIf { it.isFile }
    }.getOrNull()
}
