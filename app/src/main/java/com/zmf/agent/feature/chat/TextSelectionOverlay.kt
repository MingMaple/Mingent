package com.zmf.agent.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.theme.Motion
import kotlinx.coroutines.launch

/**
 * 选择文本的全屏页。
 *
 * 手势约定：
 * - 顶部「把手 + 标题」为固定栏，也是唯一的拖拽把手：仅从该栏下拉才关闭整页；
 * - 正文区域可任意上下滑动，仅滚动或选字，页面不会回退。
 *
 * 两个关键约定：
 * - 整页**仅使用一个** SelectionContainer：正文与思考过程共用一个选区，点击任意文字均可取消复制栏；
 * - 退场**全程不透明**：仅做位移，不做整体淡出，否则窗口半透明时文字会与主页文字重叠。
 *
 * 复制栏使用系统 ActionMode：正文顶部留出 top = 48.dp，否则浮条会被挤出窗口而不可见。
 */
@Composable
fun TextSelectionOverlay(
    title: String,
    text: String,
    reasoning: String,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // 关闭目标位移：整块完全移出屏幕（1.05 屏高，保留 5% 余量以保证最后一帧完全移出）
    val closeDistancePx = screenHeightPx * 1.05f
    val maxDragPx = screenHeightPx * 0.4f
    val thresholdPx = with(density) { 96.dp.toPx() }

    var offset by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }

    /** 关闭整页：整块移出屏幕后回调 onDismiss；动画途中重复调用只生效一次。 */
    fun closeNow() {
        if (closing) return
        closing = true
        scope.launch {
            // 关闭使用阻尼 1.0 的弹簧：速度连续、无顿挫，较固定时长 tween 更顺畅、更贴合手势
            animate(
                offset,
                closeDistancePx,
                animationSpec = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessLow)
            ) { value, _ -> offset = value }
            onDismiss()
        }
    }

    /** 未超过阈值：弹回原位，页面保持（offset 归零）。 */
    fun springBack() {
        scope.launch {
            animate(offset, 0f, animationSpec = Motion.spring()) { value, _ -> offset = value }
            offset = 0f
        }
    }

    BackHandler { closeNow() }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = offset },
        color = MaterialTheme.colorScheme.background
    ) {
        Box {
            Column(modifier = Modifier.fillMaxSize().stableStatusBarPadding().navigationBarsPadding()) {
                // 固定栏目：一直停在顶部，内容怎么滚都不动
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            enabled = !closing,
                            state = rememberDraggableState { delta ->
                                // 跟手系数 1.0：手指移动多少，页面移动多少
                                offset = (offset + delta).coerceIn(0f, maxDragPx)
                            },
                            onDragStopped = { velocity ->
                                if (offset > thresholdPx || velocity > 900f) closeNow() else springBack()
                            }
                        )
                        .padding(top = 10.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f),
                                    CircleShape
                                )
                        )
                    }
                    Text(
                        text = title,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 10.dp),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 14.dp)
                ) {
                    // 整页一个选区：正文与思考过程共享同一套选择手柄与复制菜单
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(
                                text = text,
                                fontSize = 15.sp,
                                lineHeight = 24.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (reasoning.isNotEmpty()) {
                                DisableSelection {
                                    Text(
                                        text = "思考过程",
                                        modifier = Modifier.padding(top = 2.dp),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                                Text(
                                    text = reasoning,
                                    fontSize = 13.sp,
                                    lineHeight = 21.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
