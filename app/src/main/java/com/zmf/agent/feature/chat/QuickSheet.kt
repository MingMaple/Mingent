package com.zmf.agent.feature.chat
import com.zmf.agent.core.llm.ApiDefaults

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.theme.Palette

/*
 * 模型 / 推理等级的二级窗：点击一级窗中的某一行，即在同张卡片内展开可选项。
 *
 * 为什么不做成「跳设置页」：修改模型需离开对话、进入设置、返回并再次确认，共四步。
 * 为什么不做成「再叠一张浮窗」：一级窗已贴在输入框上方，再叠一张会进一步压缩键盘与输入区，
 * 且两张卡片的圆角、阴影、停靠方式需各自对齐，任一处偏差均会破坏整体观感。
 * 就地替换内容最简单：卡片、宽度、阴影、动画均沿用同一套，仅内部替换一页。
 *
 * 二级窗中选择后即生效并收起；点击标题行的「‹」退回一级，不关闭面板。
 */

/** 二级窗里的一个服务商：模型列表为空表示「用户自己填」，界面上退回内置模板。 */
data class QuickProvider(val id: String, val name: String, val models: List<String>)

/**
 * 模型 / 推理等级二级窗的全部数据与回调。
 *
 * 打包为一个对象而非七个参数：这两页所需数据相互关联（当前选中项及选中后的行为），
 * 拆成七个参数会使 Composer 的签名过长，且每次新增一项需修改三层。
 */
data class QuickSheetState(
    val providers: List<QuickProvider> = emptyList(),
    val currentProviderId: String = "",
    val currentModelId: String = "",
    val effort: String = ApiDefaults.DEFAULT_EFFORT,
    val thinkingEnabled: Boolean = true,
    val onPickModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    val onPickEffort: (effortKey: String) -> Unit = {}
)

/** 二级窗当前停在哪一层。 */
internal enum class QuickSheetPage { ROOT, MODEL, EFFORT }

/** 「关」不是接口取值（不在 [ApiDefaults.EFFORTS] 里），只作界面上的一个选项。 */
internal const val EFFORT_OFF = "off"

/** 模型显示名：内置两项使用模板中的中文别名，其余（自定义 provider）直接显示 id。 */
internal fun modelDisplayName(modelId: String): String =
    ApiDefaults.MODELS.firstOrNull { it.first == modelId }?.second ?: modelId

/** 强度显示名：界面上一律用英文，接口取值（low / high / max）仍只在数据层出现。 */
internal fun effortDisplayName(effort: String): String = when (effort) {
    EFFORT_OFF -> "Off"
    "low" -> "Low"
    "high" -> "High"
    "max" -> "Max"
    else -> effort
}

/** 二级窗中的一行：左侧名称、右侧勾选。选中仅改变字色与勾，不绘制背景块，与设置页的选择行保持一致。 */
@Composable
internal fun QuickOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .saltCardRow { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = onSurface
        )
        if (selected) {
            Text(text = "✓", fontSize = 14.sp, color = onSurface)
        }
    }
}

/**
 * 模型页：按服务商分组，每组下面列它的模型。
 *
 * 某 provider 的 models 为空表示「交由用户自行填写」，此时退回内置模板列表，
 * 否则自定义服务商在该页会显示为空组，看似加载失败。
 */
@Composable
internal fun QuickModelPage(
    state: QuickSheetState,
    onPick: (providerId: String, modelId: String) -> Unit
) {
    // 二级窗仅列功能行：顶部不含「‹ 模型」等层级标题与返回键（仅保留功能按钮）
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 列表可能较长（十几个自定义服务商），限高后内部纵向滚动，避免将输入框推出屏幕
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
        ) {
            for (provider in state.providers) {
                Text(
                    text = provider.name,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                    fontSize = 11.sp,
                    color = Palette.Neutral
                )
                val models = provider.models.ifEmpty { ApiDefaults.MODELS.map { it.first } }
                for (model in models) {
                    QuickOptionRow(
                        label = modelDisplayName(model),
                        selected = provider.id == state.currentProviderId && model == state.currentModelId
                    ) { onPick(provider.id, model) }
                }
            }
        }
    }
}

/** 强度页：「关」排在最前，其后为接口支持的 low / high / max。 */
@Composable
internal fun QuickEffortPage(state: QuickSheetState, onPick: (String) -> Unit) {
    val current = if (state.thinkingEnabled) state.effort else EFFORT_OFF
    Column(modifier = Modifier.fillMaxWidth()) {
        for (key in listOf(EFFORT_OFF) + ApiDefaults.EFFORTS) {
            QuickOptionRow(label = effortDisplayName(key), selected = key == current) { onPick(key) }
        }
    }
}
