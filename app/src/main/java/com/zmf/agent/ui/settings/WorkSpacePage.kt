package com.zmf.agent.ui.settings

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
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette
import java.io.File

/**
 * 「新建工作区」弹窗（工作区目录选择的界面入口）。
 *
 * 默认状态**只有**名称输入、确定、取消三样东西：下面挂一条可展开的「自定义」，
 * 展开后才是目录选择区（[WorkspaceDirPicker]），**在原窗口内展开**，不另弹窗口。
 *
 * 「确定」的口径：名称不能空；目录留空 = 老行为（用应用私有目录）；
 * 填了路径就必须是存在的目录，否则原地提示一句、不关窗、不建工作区。
 * 建工作区这件事本身交给 [onCreate] 的宿主（它去调 WorkSpaceStore.createWithRoot），
 * 这里只管收输入、做校验，不碰存储。
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

    // 确定：只做校验与回调，落盘/建目录全在宿主那一侧（弹窗不认识 WorkSpaceStore）
    fun submit() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            hint = "先给工作区起个名字"
            return
        }
        val normalized = normalizePath(path)
        if (normalized.isNotEmpty()) {
            val dir = File(normalized)
            if (!dir.isDirectory) {
                hint = "这个路径不是存在的目录"
                return
            }
            if (!dir.canRead()) {
                hint = "这个目录读不了：先去「权限」页开启存储权限"
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
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    // 内距在滚动层外面：滚起来时四周留白不跟着跑
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 名称：默认折叠时这就是整个弹窗的主体，和极简输入框一个手感
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
                        cursorBrush = SolidColor(Palette.AccentGreen),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        decorationBox = { innerField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    // 聚焦时底色略深一档：Salt 的输入反馈是「按下去深一点」，不是描一圈亮边
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
                        color = Palette.AccentBlue
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

                // 可展开栏目：左「自定义」、右箭头，点哪儿都行，展开区在原窗口内长出来
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
                        // 手输 / 点进子目录都算「按路径走」：之前的 SAF 授权就作废，免得两套根打架
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

/** 折叠栏的标题行：左「自定义」、右「▾」，展开时箭头弹簧翻转 180°（跟提示词折叠卡同一套手感）。 */
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
