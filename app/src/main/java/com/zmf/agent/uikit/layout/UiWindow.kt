package com.zmf.agent.uikit.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 窗口宽度档位：按窗口实际可用宽度判定，不按设备型号。 */
enum class UiWidthClass { Compact, Medium, Expanded }

/** 窗口高度档位：横屏手机常见 Compact（<480dp）。 */
enum class UiHeightClass { Compact, Medium, Expanded }

private val DRAWER_MIN = 260.dp
private val DRAWER_MAX = 304.dp
private val DRAWER_PERMANENT = 280.dp
private val PAGE_COLUMN_MAX = 640.dp
private val CHAT_COLUMN_MAX = 720.dp

/**
 * 一次布局所依据的窗口尺寸与派生尺寸。
 *
 * [widthDp]/[heightDp] 为根窗口（已扣除横向安全区）；[paneWidthDp] 为当前内容区实际可用宽度
 * （横屏常驻抽屉时已扣掉抽屉，轨迹双栏时已扣掉辅窗格）。页面列限宽、气泡上限以及
 * 「还能不能放下一行」的判断，一律以 [paneWidthDp] 为准，避免把抽屉宽度误算进内容。
 *
 * 这些值全部是纯派生属性：窗口每次重组都会重新计算，不缓存「设备类型」，
 * 分屏、折叠、旋转后立即生效。
 */
@Immutable
data class UiWindow(
    val widthDp: Dp,
    val heightDp: Dp,
    val paneWidthDp: Dp = widthDp,
    val paneHeightDp: Dp = heightDp
) {
    val widthClass: UiWidthClass
        get() = when {
            widthDp < 600.dp -> UiWidthClass.Compact
            widthDp < 840.dp -> UiWidthClass.Medium
            else -> UiWidthClass.Expanded
        }

    val heightClass: UiHeightClass
        get() = when {
            heightDp < 480.dp -> UiHeightClass.Compact
            heightDp < 900.dp -> UiHeightClass.Medium
            else -> UiHeightClass.Expanded
        }

    val isLandscape: Boolean get() = widthDp > heightDp

    /** 横屏宽窗口：抽屉常驻，不再覆盖页面。 */
    val permanentDrawer: Boolean get() = isLandscape && widthDp >= 720.dp

    /** 抽屉宽度：覆盖式按窗口比例收敛，常驻式固定 280dp。 */
    val drawerWidth: Dp get() = if (permanentDrawer) DRAWER_PERMANENT else drawerWidthCover

    /** 覆盖式抽屉宽度：320dp 窗口为 260dp，393dp 及以上回到现行的 304dp。 */
    val drawerWidthCover: Dp
        get() = (widthDp.value * 0.78f).dp.coerceIn(DRAWER_MIN, DRAWER_MAX)

    /** 扣掉常驻抽屉后的内容宽度。 */
    val contentWidth: Dp get() = (widthDp - DRAWER_PERMANENT).coerceAtLeast(0.dp)

    /** 内容宽度仍 ≥840dp 时，轨迹页以右侧辅窗格展示。 */
    val traceSplitPane: Boolean get() = permanentDrawer && contentWidth >= 840.dp

    /** 轨迹辅窗格宽度：内容宽度的 38%，夹在 320–420dp。 */
    val tracePaneWidth: Dp
        get() = (contentWidth.value * 0.38f).dp.coerceIn(320.dp, 420.dp)

    /** 设置类页面的内容列上限。 */
    val contentMaxWidth: Dp get() = PAGE_COLUMN_MAX

    /** 聊天内容列上限。 */
    val chatMaxWidth: Dp get() = CHAT_COLUMN_MAX

    /** 横屏高度紧张：顶栏与输入区收紧纵向内距。 */
    val compactChrome: Boolean get() = heightClass == UiHeightClass.Compact

    /** 用户气泡上限：保持 340dp，但在窄内容区自动退让，避免贴边。 */
    val userBubbleMaxWidth: Dp
        get() = (paneWidthDp - 48.dp).coerceIn(200.dp, 340.dp)

    /** 助手气泡上限：保持 350dp，窄内容区同样退让。 */
    val assistantBubbleMaxWidth: Dp
        get() = (paneWidthDp - 48.dp).coerceIn(200.dp, 350.dp)

    /** 弹窗最大宽度：宽屏不再按窗口比例铺开。 */
    val dialogMaxWidth: Dp get() = 560.dp

    /** 弹窗最大高度：以窗口高度而不是屏幕高度为准，横屏与分屏下不会超出窗口。 */
    val dialogMaxHeight: Dp get() = (heightDp - 48.dp).coerceIn(240.dp, 560.dp)

    /** 同一次布局内切换到一个更窄的内容区（常驻抽屉、轨迹辅窗格）。 */
    fun withinPane(paneWidth: Dp, paneHeight: Dp = this.paneHeightDp): UiWindow =
        copy(paneWidthDp = paneWidth, paneHeightDp = paneHeight)
}

/**
 * 默认值只服务于预览与独立测试；真实页面必须由 [UiWindowProvider] 提供。
 * 取 360×800 作为手机常见基准，派生属性不会因此失真。
 */
val LocalUiWindow = staticCompositionLocalOf { UiWindow(widthDp = 360.dp, heightDp = 800.dp) }

/**
 * 用 [BoxWithConstraints] 读取实时窗口，向下提供 [LocalUiWindow]。
 *
 * 不使用 `LocalConfiguration.screenWidthDp`：窗口级 edgeToEdge + 自由窗口下，
 * 屏幕尺寸与真实窗口不一致，分屏时尤其明显。
 */
@Composable
fun UiWindowProvider(content: @Composable (UiWindow) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val window = UiWindow(widthDp = maxWidth, heightDp = maxHeight)
        CompositionLocalProvider(LocalUiWindow provides window) {
            content(window)
        }
    }
}
