package com.zmf.agent.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.UiColors
import java.io.File

/**
 * 「新建工作区」弹窗，包含工作区目录选择的界面入口。
 *
 * 默认仅显示名称输入、确定与取消；「自定义」展开后在当前窗口内显示目录选择区（[WorkspaceDirPicker]）。
 * 名称不能为空；目录留空表示使用应用私有目录，填写路径则必须是存在且可读的目录，否则原地提示且不关闭窗口。
 * 建工作区由 [onCreate] 的宿主调用 WorkSpaceStore.createWithRoot 完成，此处仅负责收集输入与校验。
 */
@Composable
fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, rootPath: String?, treeUri: String?) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var name by remember { mutableStateOf("") }
    var nameFocused by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var treeUri by remember { mutableStateOf<String?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 提交：仅做校验与回调，落盘与建目录由宿主负责
    fun submit() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            hint = "请输入工作区名称"
            return
        }
        val normalized = normalizePath(path)
        if (normalized.isNotEmpty()) {
            val dir = File(normalized)
            if (!dir.isDirectory) {
                hint = "该路径不是已存在的目录"
                return
            }
            if (!dir.canRead()) {
                hint = "该目录不可读，请先在「权限」页授予存储权限"
                return
            }
        }
        hint = null
        onCreate(trimmedName, normalized.takeIf { it.isNotEmpty() }, treeUri)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = LocalUiWindow.current.dialogMaxWidth)
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = LocalUiWindow.current.dialogMaxHeight)
                    // 内距置于滚动层之外，滚动时四周留白保持固定
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            hint = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { nameFocused = it.isFocused },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = onSurface),
                        cursorBrush = SolidColor(UiColors.accentFg),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        decorationBox = { innerField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    // 聚焦时底色加深一档，沿用 Salt 以底色深浅表达输入反馈的方式
                                    .background(onSurface.copy(alpha = if (nameFocused) 0.07f else 0.04f))
                                    .padding(horizontal = 12.dp, vertical = 11.dp)
                            ) {
                                if (name.isEmpty()) {
                                    Text(
                                        text = "工作区名称",
                                        fontSize = 15.sp,
                                        color = onSurface.copy(alpha = 0.35f)
                                    )
                                }
                                innerField()
                            }
                        }
                    )
                    Text(
                        text = "✓",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(50)) { submit() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        fontSize = 20.sp,
                        color = UiColors.accentFg
                    )
                    Text(
                        text = "×",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(50)) { onDismiss() }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        fontSize = 20.sp,
                        color = onSurface.copy(alpha = 0.4f)
                    )
                }

                val currentHint = hint
                if (currentHint != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = currentHint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 可展开栏目：左「自定义」、右箭头，展开区在当前窗口内显示
                WorkspaceCustomHeader(
                    expanded = customOpen,
                    onToggle = { customOpen = !customOpen }
                )
                AnimatedVisibility(
                    visible = customOpen,
                    enter = expandVertically(Motion.spring()) + fadeIn(Motion.fadeIn()),
                    exit = shrinkVertically(Motion.spring()) + fadeOut(Motion.fadeOut())
                ) {
                    WorkspaceDirPicker(
                        path = path,
                        treeUri = treeUri,
                        // 手动输入或进入子目录均按路径处理，同时清除 SAF 授权，避免两种根路径冲突
                        onPathEdited = { edited ->
                            path = edited
                            treeUri = null
                            hint = null
                        },
                        onTreePicked = { picked, pickedPath ->
                            treeUri = picked
                            path = pickedPath.orEmpty()
                            hint = null
                        }
                    )
                }
            }
        }
    }
}

/** 折叠栏标题行：展开时箭头弹簧翻转 180°，动效与提示词折叠卡一致。 */
@Composable
private fun WorkspaceCustomHeader(expanded: Boolean, onToggle: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.spring(),
        label = "workspaceCustomArrow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .saltCardRow() { onToggle() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "自定义", fontSize = 14.sp, color = onSurface)
        Spacer(Modifier.weight(1f))
        Text(
            text = "▾",
            modifier = Modifier.rotate(turn),
            fontSize = 13.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
    }
}
