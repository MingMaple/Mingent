package com.zmf.agent.uikit.common

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.icons.DshIcons
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.delay

/** 提示语气，只影响图标与颜色。 */
enum class NoticeTone { Neutral, Success, Error }

/** 一条提示。[id] 用于区分同文案的两次提示。 */
data class Notice(
    val id: Long,
    val text: String,
    val tone: NoticeTone = NoticeTone.Neutral,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
    val durationMs: Long = 2600L
)

/**
 * 提示通道。
 *
 * 单例而非 CompositionLocal：调用点多为普通函数，取不到组合。
 */
object NoticeCenter {

    private var seq = 0L

    private val _notice = mutableStateOf<Notice?>(null)

    val notice: State<Notice?> = _notice

    val current: Notice? get() = _notice.value

    private val main = Handler(Looper.getMainLooper())

    /**
     * 投递一条提示；空白文案直接忽略。
     *
     * 可在任意线程调用：非主线程时经主线程 Handler 转发。同一时刻只显示一条，后到的覆盖先到的。
     */
    fun show(
        text: String,
        tone: NoticeTone = NoticeTone.Neutral,
        action: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long = if (action == null) 2600L else 4200L
    ) {
        if (text.isBlank()) return
        val post = {
            seq += 1
            _notice.value = Notice(seq, text, tone, action, onAction, durationMs)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) post() else main.post(post)
    }

    fun info(text: String) = show(text)

    fun success(text: String) = show(text, NoticeTone.Success)

    fun error(text: String) = show(text, NoticeTone.Error)

    /** 可撤销的反馈，只用于可逆操作。 */
    fun undo(text: String, action: String = "撤销", onAction: () -> Unit) {
        show(text, NoticeTone.Success, action, onAction)
    }

    /** 传 id 时只在仍是该条时收起，避免旧提示的计时器关掉新提示。 */
    fun dismiss(id: Long? = null) {
        val now = _notice.value ?: return
        if (id == null || now.id == id) _notice.value = null
    }
}

/**
 * 提示里的名称最多 6 字，超出以省略号收尾。
 *
 * 名称长度直接决定提示条长度：字号最大可放大到 16/14（约 1.14 倍），13sp 汉字约占 14.9dp，
 * 加上内距、图标、间距与动作的固定开销，名称每多 1 字提示条就加宽约 15dp。
 * 会话标题多由首句自动生成，长标题本就没有辨识度，硬截不影响判断。
 */
fun noticeName(raw: String, max: Int = 6): String {
    val text = raw.trim()
    if (text.isEmpty()) return "未命名"
    return if (text.length <= max) text else text.take(max).trimEnd() + "…"
}

/** 宿主：铺满全屏，只在顶部画一条，不拦截下方点击。 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val current by NoticeCenter.notice
    // 退场期间 current 已为 null，靠副本维持这一帧
    var last by remember { mutableStateOf<Notice?>(current) }
    LaunchedEffect(current) { if (current != null) last = current }

    LaunchedEffect(current?.id) {
        val shown = current ?: return@LaunchedEffect
        delay(shown.durationMs)
        NoticeCenter.dismiss(shown.id)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically(tween(180)) { -it },
            exit = slideOutVertically(tween(140)) { -it }
        ) {
            last?.let { NoticeBanner(it) }
        }
    }
}

/** 提示条：宽度随内容、单行、卡片式胶囊。 */
@Composable
private fun NoticeBanner(notice: Notice) {
    // 取本应用的卡片语言：卡片底色 + 描边 + 阴影，深浅色由主题给出
    val content = MaterialTheme.colorScheme.onSurface
    val accent = UiColors.accentFg
    val okColor = UiColors.success
    val errColor = MaterialTheme.colorScheme.error

    val density = LocalDensity.current
    var dragged by remember(notice.id) { mutableStateOf(0f) }
    val threshold = with(density) { 24.dp.toPx() }

    Surface(
        modifier = Modifier
            // 页头普遍为 62dp，提示条让过页头，避免盖住标题
            .padding(top = 64.dp)
            .padding(horizontal = 16.dp)
            // 宽度上限按最大倍率（16/14 ≈ 1.14）复核：328dp 为 360dp 屏宽减去左右 16dp
            .widthIn(max = 328.dp)
            .wrapContentWidth()
            .draggable(
                state = rememberDraggableState { delta -> dragged += delta },
                orientation = Orientation.Vertical,
                onDragStopped = {
                    if (dragged < -threshold) NoticeCenter.dismiss(notice.id)
                    dragged = 0f
                }
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        // 与伴随面板同档的 8dp：贴圆角一圈，边界清楚又不发虚
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = when (notice.tone) {
                    NoticeTone.Success -> DshIcons.Check
                    NoticeTone.Error -> DshIcons.Alert
                    NoticeTone.Neutral -> DshIcons.Info
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = when (notice.tone) {
                    NoticeTone.Success -> okColor
                    NoticeTone.Error -> errColor
                    NoticeTone.Neutral -> content.copy(alpha = 0.55f)
                }
            )
            Text(
                text = notice.text,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = content
            )
            notice.action?.let { label ->
                Text(
                    text = label,
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(6.dp)) {
                            // 先执行动作，再按本条 id 收起：动作通常会弹下一条提示
                            notice.onAction?.invoke()
                            NoticeCenter.dismiss(notice.id)
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent
                )
            }
        }
    }
}
