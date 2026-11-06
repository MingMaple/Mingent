package com.zmf.agent.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.feature.settings.WorkspaceDirPicker
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.UiColors
import java.io.File

/** 弹窗内列表项的圆角：弹窗内部的行，取比卡片更小的弧。 */
private val DialogRowShape = RoundedCornerShape(10.dp)

/**
 * 「更改工作区」单层弹窗：选择已有工作区，或就地新建一个后一次提交。
 *
 * 为什么不做二级弹窗：新建与移动是同一个决定的两个分支，分两步会让用户在两个弹窗之间
 * 反复确认同一件事；此处在同一窗口内展开名称与目录，确定时按当前分支提交。
 *
 * 「确定」的可用条件：已选中一个已有工作区，或新建区的名称非空且目录通过校验。
 */
@Composable
internal fun ChangeWorkspaceDialog(
    sessionTitle: String,
    workspaces: List<WorkSpace>,
    currentWorkspaceId: String?,
    onDismiss: () -> Unit,
    onMove: (workspaceId: String) -> Unit,
    onCreateAndMove: (name: String, rootPath: String?, treeUri: String?) -> Unit,
    /** 目录占用检查：返回占用者名称，未占用返回 null。 */
    occupiedBy: (rootPath: String?, treeUri: String?) -> String?
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var picked by remember { mutableStateOf(currentWorkspaceId) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var treeUri by remember { mutableStateOf<String?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(creating) { if (creating) focusRequester.requestFocus() }

    fun submit() {
        if (creating) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                hint = "请输入工作区名称"
                return
            }
            val normalized = normalizeDirPath(path)
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
            // 同一目录不允许对应两个工作区；仅被会话引用的目录可以复用存储单元
            occupiedBy(normalized.takeIf { it.isNotEmpty() }, treeUri)?.let { owner ->
                hint = "该目录已由工作区「" + owner + "」使用"
                return
            }
            hint = null
            onCreateAndMove(trimmed, normalized.takeIf { it.isNotEmpty() }, treeUri)
            return
        }
        val target = picked
        if (target == null) {
            hint = "请选择目标工作区"
            return
        }
        onMove(target)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .widthIn(max = LocalUiWindow.current.dialogMaxWidth)
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(
                    text = "更改工作区",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface
                )
                Text(
                    text = "「" + sessionTitle + "」",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
                Spacer(Modifier.height(10.dp))

                // 已有工作区：当前归属预选；超出高度时内部滚动
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    workspaces.forEach { space ->
                        PickRow(
                            label = space.name,
                            selected = !creating && picked == space.id,
                            onClick = {
                                creating = false
                                picked = space.id
                                hint = null
                            }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                PickRow(
                    label = if (creating) "＋ 新建工作区（填写中）" else "＋ 新建工作区",
                    selected = creating,
                    onClick = {
                        creating = true
                        picked = null
                        hint = null
                    }
                )

                if (creating) {
                    Spacer(Modifier.height(10.dp))
                    NameField(
                        name = name,
                        focusRequester = focusRequester,
                        onNameChange = {
                            name = it
                            hint = null
                        },
                        onSubmit = { submit() }
                    )
                    Spacer(Modifier.height(8.dp))
                    // 目录可留空：留空即使用应用私有目录
                    CustomHeader(
                        title = if (path.isBlank() && treeUri == null) "选择目录（可留空）" else "已选目录",
                        expanded = customOpen,
                        onToggle = { customOpen = !customOpen }
                    )
                    AnimatedVisibility(
                        visible = customOpen,
                        enter = expandVertically(animationSpec = Motion.spring()) + fadeIn(Motion.fadeIn()),
                        exit = shrinkVertically(animationSpec = Motion.spring()) + fadeOut(Motion.fadeOut())
                    ) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            WorkspaceDirPicker(
                                path = path,
                                treeUri = treeUri,
                                onPathEdited = { edited ->
                                    path = edited
                                    treeUri = null
                                    hint = null
                                },
                                onTreePicked = { pickedTree, pickedPath ->
                                    treeUri = pickedTree
                                    path = pickedPath.orEmpty()
                                    hint = null
                                }
                            )
                        }
                    }
                }

                hint?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = 8.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "取消",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(10.dp), onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        color = onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "确定",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(10.dp), onClick = { submit() })
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UiColors.accentFg
                    )
                }
            }
        }
    }
}

/**
 * 「更改储存路径」弹窗：先说明后果，再选目录。
 *
 * 说明文案由控制器按原目录类型给出（外部目录 / 内置非空 / 内置为空），
 * 校验与落盘都在控制器，此处只收集路径。
 */
@Composable
internal fun ChangeStorageDialog(
    workspaceName: String,
    prompt: String,
    onDismiss: () -> Unit,
    onConfirm: (rootPath: String?, treeUri: String?) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var path by remember { mutableStateOf("") }
    var treeUri by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val normalized = normalizeDirPath(path)
        if (normalized.isEmpty() && treeUri == null) {
            hint = "请选择目录"
            return
        }
        if (normalized.isNotEmpty()) {
            val dir = File(normalized)
            if (!dir.isDirectory) {
                hint = "该路径不是已存在的目录"
                return
            }
            if (!dir.canWrite()) {
                hint = "该目录不可写"
                return
            }
        }
        hint = null
        onConfirm(normalized.takeIf { it.isNotEmpty() }, treeUri)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .widthIn(max = LocalUiWindow.current.dialogMaxWidth)
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = LocalUiWindow.current.dialogMaxHeight)
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "更改储存路径？",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface
                )
                Text(
                    text = "工作区「" + workspaceName + "」",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
                Text(
                    text = prompt,
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(10.dp))
                WorkspaceDirPicker(
                    path = path,
                    treeUri = treeUri,
                    onPathEdited = { edited ->
                        path = edited
                        treeUri = null
                        hint = null
                    },
                    onTreePicked = { pickedTree, pickedPath ->
                        treeUri = pickedTree
                        path = pickedPath.orEmpty()
                        hint = null
                    }
                )
                hint?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = 8.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "取消",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(10.dp), onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        color = onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "确定",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(10.dp), onClick = { submit() })
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UiColors.accentFg
                    )
                }
            }
        }
    }
}

/** 单选行：左侧圆点 + 名称；选中态用强调色圆点与满不透明文字。 */
@Composable
private fun PickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(DialogRowShape, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selected) "●" else "○",
            fontSize = 13.sp,
            color = if (selected) UiColors.accentFg else onSurface.copy(alpha = 0.35f)
        )
        Spacer(Modifier.padding(horizontal = 5.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = onSurface.copy(alpha = if (selected) 1f else 0.75f)
        )
    }
}

/** 名称输入：底色块与整页输入框同一套（4% 底色、16dp 圆角）。 */
@Composable
private fun NameField(
    name: String,
    focusRequester: FocusRequester,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = onSurface),
        cursorBrush = SolidColor(UiColors.accentFg),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(onSurface.copy(alpha = 0.04f))
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) {
                if (name.isEmpty()) {
                    Text(text = "工作区名称", fontSize = 14.sp, color = onSurface.copy(alpha = 0.35f))
                }
                inner()
            }
        }
    )
}

/** 折叠栏：展开时箭头弹簧翻转 180°，与工作区弹窗的「自定义」一致。 */
@Composable
private fun CustomHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.spring(),
        label = "dirPickerArrow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(DialogRowShape, onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 13.sp, color = onSurface.copy(alpha = 0.75f))
        Spacer(Modifier.weight(1f))
        Text(
            text = "▾",
            modifier = Modifier.rotate(turn),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
    }
}

/** 路径规整：去首尾空白与尾斜杠；空串表示未选择。 */
private fun normalizeDirPath(raw: String): String {
    var text = raw.trim()
    while (text.length > 1 && text.endsWith("/")) text = text.dropLast(1)
    return if (text == "/") "" else text
}
