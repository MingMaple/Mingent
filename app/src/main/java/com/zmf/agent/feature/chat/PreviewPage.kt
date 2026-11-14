package com.zmf.agent.feature.chat

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.zmf.agent.core.model.FileKind
import com.zmf.agent.core.model.FileKinds
import com.zmf.agent.core.store.WorkspaceIo
import com.zmf.agent.uikit.common.NoticeCenter
import com.zmf.agent.uikit.common.TopIconButton
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.abs

/**
 * 预览页：顶栏 + 标签条 + 正文，与「计划」「浏览」同一套交互与层级。
 *
 * 标签只装「用户点开过的文件」，按打开顺序排列，可切换、可关闭；关掉最后一条即退回主页，
 * 顶栏入口随之消失。产物全集不在这里——它在每一轮的回复末尾。
 */
@Composable
internal fun PreviewPage(
    io: WorkspaceIo,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tabs = PreviewTabs.tabs
    val active = PreviewTabs.active
    val scope = rememberCoroutineScope()
    // 每个标签的正文状态（滚动位置、已展开的 PDF 页）各自保存：切回来仍在原处
    val holder = rememberSaveableStateHolder()

    // 顶栏随滑动收起（与 via 同一套判定）：一次手势结束时看竖直位移，
    // 超过 36dp 且以竖直为主才切换——上移收起，下移展开；轻触正文任意位置直接唤出。
    var headerVisible by remember { mutableStateOf(true) }
    val swipeThreshold = with(LocalDensity.current) { 36.dp.toPx() }
    val tapSlop = with(LocalDensity.current) { 8.dp.toPx() }
    // 切换标签回到展开：新标签的阅读位置与上一份无关
    LaunchedEffect(active?.path) { headerVisible = true }

    /** docx 正文由 WebView 自己滚动，竖滑判定在它的 View 层完成；true 表示应收起顶栏。 */
    fun onWebHide(hide: Boolean) {
        headerVisible = !hide
    }

    // 最后一条标签被关掉：这一页已无内容，退回主页
    LaunchedEffect(tabs.size) {
        if (tabs.isEmpty()) onBack()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val version = PreviewTabs.versionOf(active?.path)
            // 版本进键：文件被改写后重新取一次来源，正文随之重读
            val source = remember(active?.path, version, io.displayRoot, io.isSaf) {
                active?.let { previewSourceOf(io, it.path) }
            }
            // 本次进入页面后改过的文件：只留一行说明，不打断阅读
            var refreshed by remember { mutableStateOf(emptySet<String>()) }
            // PDF 的页码：按标签记，交叉淡化期间上一份正文仍能读到自己那一份
            val pdfPages = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
            LaunchedEffect(active?.path, source?.version) {
                val path = active?.path ?: return@LaunchedEffect
                if (PreviewTabs.observe(path, source?.version ?: 0L)) refreshed = refreshed + path
            }

            // 顶栏占正文空间：正文高度已预先确定，收起顶栏后不会重排
            HideableHeader(visible = headerVisible) {
                TabHeaderRow(
                    items = tabs.map { TabStripItem(key = it.path, label = FileKinds.nameOf(it.path)) },
                    selectedKey = active?.path,
                    onSelect = { path -> PreviewTabs.activate(path) },
                    onClose = { path -> PreviewTabs.close(path) },
                    onBack = onBack
                ) {
                    // 导出固定在最右上角：md、pdf、docx、图片、打不开的格式一律同一位置，
                    // 各页正文布局可以不同，这个入口不许动；文件已不存在时不给这个入口
                    if (source != null && source.exists) {
                        TopIconButton(
                            glyph = "↗",
                            fontSize = 17.sp,
                            onClick = {
                                // 缓存副本可能是几十兆，放到后台线程，避免点击时卡住界面
                                scope.launch {
                                    if (!openExternally(context, source)) {
                                        NoticeCenter.info("没有找到能打开这个文件的应用")
                                    }
                                }
                            }
                        )
                    }
                }
            }

            if (active == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "没有打开的预览", fontSize = 14.sp, color = UiColors.subText)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 顶栏显隐与轻触唤出共用一次手势：只读位移、不消费事件，正文照常滚动与点击
                        .pointerInput(swipeThreshold, tapSlop) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                var lastX = down.position.x
                                var lastY = down.position.y
                                var dx = 0f
                                var dy = 0f
                                var multiTouch = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } > 1) multiTouch = true
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    dx += change.position.x - lastX
                                    dy += change.position.y - lastY
                                    lastX = change.position.x
                                    lastY = change.position.y
                                    if (!change.pressed) break
                                }
                                if (multiTouch) return@awaitEachGesture
                                when {
                                    // 竖直且够长：上移收起、下移展开
                                    abs(dy) > swipeThreshold && abs(dy) > abs(dx) -> headerVisible = dy >= 0f
                                    // 轻触：收起状态下点正文即唤出顶栏
                                    abs(dx) < tapSlop && abs(dy) < tapSlop -> headerVisible = true
                                }
                            }
                        }
                ) {
                    // 正文区交叉淡化：与「计划」页同一套过渡，切标签时下方不再硬切
                    //
                    // 过渡目标取路径而不是标签对象：同一路径换新实例（文件被改写、标签刷新）时，
                    // 以对象为目标会同时组合新旧两份，而保存键同为该路径，SaveableStateProvider
                    // 会抛「Key … was used multiple times」。以路径为目标则原地更新、不触发过渡。
                    AnimatedContent(
                        targetState = active.path,
                        transitionSpec = { tabContentTransform() },
                        modifier = Modifier.fillMaxSize(),
                        label = "preview-body"
                    ) { path ->
                        val tab = tabs.firstOrNull { it.path == path } ?: return@AnimatedContent
                        // 每份正文各自取来源：淡出中的那一份仍读自己的路径与版本
                        val tabVersion = PreviewTabs.versionOf(tab.path)
                        val tabSource = remember(tab.path, tabVersion, io.displayRoot, io.isSaf) {
                            previewSourceOf(io, tab.path)
                        }
                        // 标签身份即状态键：切换时各自的位置与展开情况跟着走，切回来接在原处
                        holder.SaveableStateProvider(tab.path) {
                            key(tab.path) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    val pages = pdfPages[tab.path]
                                    val pageText = if (tabSource.kind == FileKind.PDF && pages != null && pages.second > 0) {
                                        " · 第 " + (pages.first + 1) + " / " + pages.second + " 页"
                                    } else {
                                        ""
                                    }
                                    PreviewFileHeader(tabSource, pageText)
                                    if (refreshed.contains(tab.path) && tabSource.exists) {
                                        Text(
                                            text = "文件已更新",
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                                            fontSize = 11.sp,
                                            color = UiColors.subText
                                        )
                                    }
                                    PreviewBody(
                                        source = tabSource,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        onPdfPage = { page, total -> pdfPages[tab.path] = page to total },
                                        onWebHide = { hide -> onWebHide(hide) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单个文件的正文：按类别派发，打不开或超限时落到统一空态。 */
@Composable
private fun PreviewBody(
    source: PreviewSource,
    modifier: Modifier = Modifier,
    onPdfPage: ((Int, Int) -> Unit)? = null,
    /** docx 由 WebView 滚动，方向由它上报给顶栏显隐。 */
    onWebHide: ((Boolean) -> Unit)? = null
) {
    when {
        !source.exists -> PreviewNotice(
            title = "文件已不存在",
            detail = "它可能已被删除或移动，请回到会话确认。",
            modifier = modifier
        )
        source.kind == FileKind.PDF -> PdfBody(source, modifier, onPdfPage)
        source.kind == FileKind.IMAGE -> ImageBody(source, modifier)
        source.kind == FileKind.DOCX -> DocxBody(source, modifier, onWebHide)
        source.kind == FileKind.MARKDOWN -> TextBody(source, markdown = true, modifier = modifier)
        source.kind == FileKind.CODE -> TextBody(source, markdown = false, modifier = modifier)
        source.kind == FileKind.TEXT -> TextBody(source, markdown = false, modifier = modifier)
        else -> PreviewNotice(
            title = "该格式暂不支持预览",
            detail = "如需查看，请用其他应用打开。",
            modifier = modifier
        )
    }
}

/** 文件信息 + 一行说明：空态只讲清是什么情况、下一步去哪里看，不教用户在终端里做事。 */
@Composable
internal fun PreviewNotice(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Text(text = detail, fontSize = 13.sp, color = UiColors.subText, lineHeight = 19.sp)
    }
}

/**
 * 正文顶部的文件说明：文件名 + 类型／大小／目录。
 */
@Composable
private fun PreviewFileHeader(source: PreviewSource, pageText: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            text = source.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (source.exists) {
            Text(
                text = fileSubtitle(source) + pageText,
                fontSize = 11.sp,
                color = UiColors.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 顶栏副标题：类型 + 大小，路径过长时只留末段目录。 */
private fun fileSubtitle(source: PreviewSource): String {
    val size = if (source.size > 0) " · " + exportSizeText(source.size) else ""
    val parent = source.path.substringBeforeLast('/', "").substringAfterLast('/')
    val where = if (parent.isEmpty()) "" else " · " + parent
    return FileKinds.label(source.kind) + size + where
}

// ---- 文件来源与外部打开 ----

/** 预览所需的最小信息：怎么读、多大、能不能在本机按 File 打开。 */
internal class PreviewSource(
    val path: String,
    val name: String,
    val kind: FileKind,
    val size: Long,
    /** 长度与修改时间合成的版本；文件被改写后与上一次记录不一致。 */
    val version: Long,
    val exists: Boolean,
    val open: () -> InputStream,
    /** 可直接交给 PdfRenderer / Intent 的本地文件；SAF 目录下为 null。 */
    val localFile: File?
)

/** 把绝对路径映射成读取入口：工作区内的走 [io]（兼容 SAF），工作区外按普通文件读。 */
internal fun previewSourceOf(io: WorkspaceIo, path: String): PreviewSource {
    val file = File(path)
    val rel = runCatching { io.relativePathOf(file) }.getOrNull()
    val kind = FileKinds.of(path)
    val inWorkspace = rel != null
    val exists = if (rel != null) io.exists(rel) && !io.isDirectory(rel) else file.isFile
    val size = if (rel != null) io.size(rel) else runCatching { file.length() }.getOrDefault(-1L)
    // SAF 目录没有可靠的修改时间，退化为「长度」单一判据
    val modified = if (io.isSaf) 0L else runCatching { file.lastModified() }.getOrDefault(0L)
    val open: () -> InputStream = if (rel != null) {
        { io.openStream(rel) }
    } else {
        { file.inputStream() }
    }
    val local = when {
        !inWorkspace -> file.takeIf { it.isFile }
        io.isSaf -> null
        else -> file.takeIf { it.isFile }
    }
    return PreviewSource(
        path = path,
        name = FileKinds.nameOf(path),
        kind = kind,
        size = size,
        version = size * 31L + modified,
        exists = exists,
        open = open,
        localFile = local
    )
}

/**
 * 用其他应用打开。
 *
 * 一律先落到应用缓存再由 FileProvider 授权读取：targetSdk 28 下 file:// 会被系统直接拒掉，
 * 而缓存副本与源文件解耦，外部应用改内容也不会影响原文件。
 */
internal suspend fun openExternally(context: Context, source: PreviewSource): Boolean {
    val file = source.localFile ?: withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "preview-out").apply { mkdirs() }
            val target = File(dir, source.name)
            source.open().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            target
        }.getOrNull()
    } ?: return false
    return runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(source.kind, source.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

private fun mimeOf(kind: FileKind, name: String): String = when (kind) {
    FileKind.PDF -> "application/pdf"
    FileKind.IMAGE -> when (FileKinds.suffixOf(name)) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }
    FileKind.MARKDOWN, FileKind.TEXT, FileKind.CODE -> "text/plain"
    FileKind.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    FileKind.ARCHIVE -> "application/zip"
    else -> "application/octet-stream"
}
