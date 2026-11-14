package com.zmf.agent.feature.chat

import android.content.Context
import android.util.Base64
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * docx 正文：用 mammoth 把文档映射成语义 HTML，再交给 WebView 显示。
 *
 * 为什么是它：约 400 KB，能带出标题、列表、表格、粗斜体与内嵌图片，比纯文本高一个档次；
 * 更还原版面的方案要么再拖一个 JSZip，要么上 POI 的十几兆。
 * WebView 在这里只是离线文档容器：禁网络、禁跳转，配色由主题注入。
 */
@Composable
internal fun DocxHtmlView(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    /** 顶栏显隐提示：true 表示应收起。WebView 不经 Compose，手势在 View 层判定。 */
    onHideHint: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    // factory 只执行一次：回调必须跟随最新组合，否则切换标签后仍写到上一份的显隐状态
    val hideHint by rememberUpdatedState(onHideHint)
    // 主题色必须在组合内先读出来：UiColors 的几个取值是 @Composable 属性，不能进 remember 的 lambda
    val background = MaterialTheme.colorScheme.background.toArgb()
    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
    val subText = UiColors.subText.toArgb()
    val stroke = UiColors.stroke.toArgb()
    // 文档文字随全局基准字号缩放：与浏览页同一口径，只改文字，版式与图片尺寸不变
    val textZoomPercent = (LocalDensity.current.fontScale * 100f).roundToInt()
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f).toArgb()
    // 组装包含读模板与整篇 base64 编码，放到 IO 线程，页面先空一帧再载入。
    // 字节尚未读到时保持空页：WebView 先挂载，文档到达后只换内容，视图本身不被替换。
    val page by produceState("", bytes, background, onSurface, subText, stroke, codeBg) {
        value = if (bytes.isEmpty()) {
            ""
        } else {
            withContext(Dispatchers.IO) {
                buildDocxPage(context.applicationContext, bytes, background, onSurface, subText, stroke, codeBg)
            }
        }
    }
    // 已载入内容只用于去重，不参与重组，用普通数组持有
    val loadedPage = remember { arrayOfNulls<String>(1) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                // 文档是本地文件，不许它自己发请求：blockNetworkLoads 连 img/script 的外链一起挡掉
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportZoom(false)
                settings.textZoom = textZoomPercent
                setBackgroundColor(background)
                // 文档首次绘制时，WebView 的绘制请求会并入窗口的绘制列表；那一帧窗口内容尚未
                // 重新提交，整页（含顶栏）会空白一帧。自持硬件层后，首帧只更新这层位图，
                // 窗口其余内容照常合成。该层随 WebView 存在，onRelease 调用 destroy() 时一并释放。
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                // 顶栏收起同样走竖滑判定（与浏览页、via 一致）：监听器返回 false，
                // 文档自身的点击、滑动与缩放不受影响
                installHeaderSwipe(this) { hide -> hideHint?.invoke(hide) }
                // 任何跳转一律拦下：文档里的链接不应把用户带出预览页
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                }
            }
        },
        update = { webView ->
            // 字号变更时页面已载入，只需更新缩放，不重新装载文档
            if (webView.settings.textZoom != textZoomPercent) {
                webView.settings.textZoom = textZoomPercent
            }
            if (page.isNotEmpty() && loadedPage[0] != page) {
                loadedPage[0] = page
                webView.loadDataWithBaseURL("file:///android_asset/docx/", page, "text/html", "utf-8", null)
            }
        },
        // 每次进入预览都会新建一个 WebView，离开时释放，避免持有页面对象
        onRelease = { it.destroy() }
    )
}

/** 组装页面：配色注入 CSS 变量，文档字节以 base64 交给 mammoth。 */
private fun buildDocxPage(
    context: Context,
    bytes: ByteArray,
    background: Int,
    onSurface: Int,
    subText: Int,
    stroke: Int,
    codeBg: Int
): String {
    val template = docxTemplate(context) ?: return fallbackPage()
    return template
        .replace("__BG__", hex(background))
        .replace("__FG__", hex(onSurface))
        .replace("__SUB__", hex(subText))
        .replace("__STROKE__", hex(stroke))
        .replace("__CODE__", hex(codeBg))
        .replace("__DOCX_BASE64__", Base64.encodeToString(bytes, Base64.NO_WRAP))
}

/** 模板只读一次：assets 里是纯文本，重复读取没有意义。 */
private var cachedTemplate: String? = null

private fun docxTemplate(context: Context): String? {
    cachedTemplate?.let { return it }
    val loaded = runCatching {
        context.assets.open("docx/viewer.html").use { it.bufferedReader().readText() }
    }.getOrNull()
    cachedTemplate = loaded
    return loaded
}

/** 模板读不到时至少别白屏：直接说明原因与下一步。 */
private fun fallbackPage(): String =
    "<html><body style=\"padding:16px;font-size:14px\">无法显示这个文档：预览组件缺失，请更新应用后重试。</body></html>"

private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
