package com.zmf.agent.ui.chat

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
import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.theme.Palette

/*
 * 模型 / 推理等级的二级窗：点一级窗里的某一行，就在同一张卡片里展开可选项。
 *
 * 为什么不做成「跳设置页」：改个模型要离开对话、进设置、回来、再确认，一趟四步。
 * 为什么不做成「再叠一张浮窗」：一级窗已经贴在输入框上方，再叠一张会把键盘和输入区挤得更远，
 * 而且两张卡片的圆角、阴影、停靠方式都要各自对齐一次，只要有一处偏差看着就散。
 * 就地换内容最省事：卡片、宽度、阴影、动画全是同一套，只是里面换了一页。
 *
 * 二级窗里选完即生效并收起；点标题行的「‹」退回一级，不关面板。
 */

/** 二级窗里的一个服务商：模型列表为空表示「用户自己填」，界面上退回内置模板。 */
data class QuickProvider(val id: String, val name: String, val models: List<String>)

/**
 * 模型 / 推理等级二级窗的全部数据与回调。
 *
 * 打包成一个对象而不是七个参数：这两页要的东西彼此相关（谁当前选中、选中之后干什么），
 * 拆成七个参数后 Composer 的签名会长到没人愿意读，而且每次加一项都要改三层。
 */
data class QuickSheetState(
    val providers: List<QuickProvider> = emptyList(),
    val currentProviderId: String = "",
    val currentModelId: String = "",
    val effort: String = ApiConfig.DEFAULT_EFFORT,
    val thinkingEnabled: Boolean = true,
    val onPickModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    val onPickEffort: (effortKey: String) -> Unit = {}
)

/** 二级窗当前停在哪一层。 */
internal enum class QuickSheetPage { ROOT, MODEL, EFFORT }

/** 「关」不是接口取值（不在 [ApiConfig.EFFORTS] 里），只作界面上的一个选项。 */
internal const val EFFORT_OFF = "off"

/** 模型显示名：内置两个用模板里的中文别名，其余（自定义 provider）直接显示 id。 */
internal fun modelDisplayName(modelId: String): String =
    ApiConfig.MODELS.firstOrNull { it.first == modelId }?.second ?: modelId

/** 强度显示名：界面上一律用英文，接口取值（low / high / max）仍只在数据层出现。 */
internal fun effortDisplayName(effort: String): String = when (effort) {
    EFFORT_OFF -> "Off"
    "low" -> "Low"
    "high" -> "High"
    "max" -> "Max"
    else -> effort
}

/** 二级窗里的一行：左边名字、右边勾。选中只改字色 + 勾，不画背景块，跟设置页的选择行一个口径。 */
@Composable
internal fun QuickOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
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
            color = if (selected) Palette.AccentGreen else onSurface
        )
        if (selected) {
            Text(text = "✓", fontSize = 14.sp, color = Palette.AccentGreen)
        }
    }
}

/**
 * 模型页：按服务商分组，每组下面列它的模型。
 *
 * 一份 provider 的 models 为空表示「交给用户自己填」，这时退回内置模板列表，
 * 否则自定义服务商在这一页会是一个空组，看着像加载失败。
 */
@Composable
internal fun QuickModelPage(
    state: QuickSheetState,
    onPick: (providerId: String, modelId: String) -> Unit
) {
    // 二级窗只列功能行：顶部不再有「‹ 模型」这类层级标题与返回键（只设功能按钮）
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 列表可能很长（十几个自定义服务商），限高后内部竖滚，别把输入框顶出屏幕
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
                val models = provider.models.ifEmpty { ApiConfig.MODELS.map { it.first } }
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

/** 强度页：「关」排在最前，后面才是接口支持的 low / high / max。 */
@Composable
internal fun QuickEffortPage(state: QuickSheetState, onPick: (String) -> Unit) {
    val current = if (state.thinkingEnabled) state.effort else EFFORT_OFF
    Column(modifier = Modifier.fillMaxWidth()) {
        for (key in listOf(EFFORT_OFF) + ApiConfig.EFFORTS) {
            QuickOptionRow(label = effortDisplayName(key), selected = key == current) { onPick(key) }
        }
    }
}
