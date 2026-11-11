package com.zmf.agent.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.uikit.gesture.blockHorizontalAtEdge
import com.zmf.agent.uikit.common.saltCursorBrush
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.icons.ArrowLeftIcon
import com.zmf.agent.uikit.icons.ChevronDownIcon
import com.zmf.agent.uikit.icons.CloseIcon
import com.zmf.agent.uikit.icons.FolderIcon
import com.zmf.agent.uikit.icons.PlusIcon
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import java.io.File

/**
 * 分享模式的面板：**顶替主页底部输入区**，而不是另起一个浮层。
 *
 * ## 为什么在主页里
 *
 * 从任何应用点分享、选到本应用时，系统打开的就是本应用的主界面——这是平台的既定行为，
 * 一个半透明浮层反而会在部分机型上被塞进小窗。既然已经进了主页，就不必再画遮罩，
 * 也不必把面板做成一张悬浮白卡：直接把底部槽位换掉，用户看到的就是「同一个会话页，
 * 只是输入框换了种形态」。
 *
 * ## 生命周期
 *
 * 发送或取消时由父层把可见性置 false：面板整块下滑出屏，常规输入区随即露出并回到原位。
 * 两个面板共用一个槽位、常规输入区始终在下面，因此没有「先消失再出现」的空白帧。
 *
 * ## 结构
 *
 * 自上而下：附件行 → 输入条（正文、目标键、放弃键、发送键）→ 状态行。目标选择器不再是插在
 * 中间的一级，而是锚定在目标键上的独立窗口（见 [TargetPickerPopover]）：开合不改变输入区高度。
 *
 * 附件行与主页待发附件完全是同一套：透明背景、横滑、图片 64dp 缩略图、文件 chip 带类型图标，
 * 右上角 ✕ 删除。它排在输入条之上，因此视觉上浮在会话气泡与输入框之间。
 *
 * ## 内容形态
 *
 * - 短文本（≤ [SHARE_INLINE_TEXT_LIMIT]）：受理时已预填进输入框，可改可删、随消息发出；
 * - 长文本与非文本：作为附件列在上面，正文只写用户自己补的那句话。
 *
 * 这个判定在受理时（[ShareIntake.stage]）已完成，本面板只负责呈现，不再二次判断：
 * 若在这里按「输入框里现在剩多少字」重判，用户删几个字就会让附件跳回正文，形态会来回变。
 *
 * @param draft 已落盘到中转目录的分享内容
 * @param currentSessionId 当前会话：目标键、状态行都按它取数——面板里选目标即真的切换会话，
 *        抽屉里切换会话后目标键也立刻跟随，不另存一份「目标」
 * @param currentWorkspaceId 当前工作区：当前会话还没有内容时，目标键显示它的名字
 * @param sending 正在发送：发送键改为省略号并禁止再次触发
 * @param failure 失败说明；非空时显示在输入条下方
 * @param onRemoveFile 删除某个分享文件（长文附件或普通文件）：父层同步删掉中转文件
 * @param onRemoveImage 删除某张分享图片
 * @param onCancel 放弃本次分享
 * @param onTargetPicked 用户选定了目标：父层据此真正切换会话，上方会话区随之变化
 */
@Composable
internal fun ShareArea(
    draft: ShareDraft,
    workspaces: List<WorkSpace>,
    sessions: List<ChatSession>,
    currentSessionId: String,
    currentWorkspaceId: String?,
    sending: Boolean,
    failure: String?,
    onRemoveFile: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onCancel: () -> Unit,
    onTargetPicked: (ShareTarget) -> Unit,
    onSend: (String) -> Unit
) {
    // 正文：短文本预填，长文本留空（正文已在附件里，输入框只写用户自己的话）。
    // key 取 payload：面板内删附件会复制一份 draft，若以 draft 为 key，用户正在写的字会被重置
    var text by remember(draft.payload) { mutableStateOf(draft.inlineText) }
    // 浮层开 = 挂载，关 = 卸载：没有淡入淡出，也没有需要等待的退场
    var pickerOpen by remember(draft.payload) { mutableStateOf(false) }
    // 目标键的窗口坐标：浮层按它定位，点击前一次布局已经给出
    var targetAnchor by remember(draft.payload) { mutableStateOf<Rect?>(null) }
    val canSend = text.isNotBlank() || draft.hasFiles
    val currentSession = sessions.firstOrNull { it.id == currentSessionId }
    val blankSession = currentSession == null || currentSession.blank

    Column(modifier = Modifier.fillMaxWidth()) {
        // 附件行排在面板之上：它属于「待发内容」，不属于面板本体
        if (draft.hasFiles) {
            AttachmentStrip(
                draft = draft,
                onRemoveFile = onRemoveFile,
                onRemoveImage = onRemoveImage
            )
        }

        // 面板本体铺页面底色：退场途中它要盖住下方的常规输入区，不能透出内容
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            ShareComposer(
                text = text,
                onTextChange = { text = it },
                targetLabel = targetLabel(blankSession, currentSession, currentWorkspaceId, workspaces),
                pickerOpen = pickerOpen,
                sending = sending,
                canSend = canSend && !sending,
                onTargetClick = { pickerOpen = !pickerOpen },
                onTargetAnchor = { targetAnchor = it },
                onCancel = onCancel,
                onSend = { onSend(text) },
                status = statusOf(sessions, currentSessionId)
            )

            failure?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    // 浮层挂在 Popup 窗口里，写在这里只是借用父组合；它不占本页任何布局空间
    if (pickerOpen) {
        val anchor = targetAnchor
        if (anchor != null) {
            TargetPickerPopover(
                anchor = anchor,
                workspaces = workspaces,
                sessions = sessions,
                currentSessionId = currentSessionId,
                currentWorkspaceId = currentWorkspaceId,
                onPick = { picked ->
                    pickerOpen = false
                    onTargetPicked(picked)
                },
                onDismiss = { pickerOpen = false }
            )
        }
    }
}

/**
 * 附件行：图片缩略图在前、文件 chip 在后，同一行横向滚动。
 *
 * 与主页 `ChatComposerArea` 里那行是同一套件（[PendingImageThumb] / [PendingFileChip]），
 * 只是这里没有「添加」入口——内容已经由分享带进来了。普通文件与超阈值长文都走
 * [PendingFileChip]，图标、字号、宽度上限与 ✕ 行为完全一致。
 */
@Composable
private fun AttachmentStrip(
    draft: ShareDraft,
    onRemoveFile: (String) -> Unit,
    onRemoveImage: (String) -> Unit
) {
    val root = File(draft.sourceRootPath)
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 与主页同一拦截器：滑到两端时把横向手势让回给系统返回手势
            .blockHorizontalAtEdge(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        draft.imageRelPaths.forEach { rel ->
            PendingImageThumb(
                relPath = rel,
                root = root,
                // 分享面板里点开大图没有意义（发送后就在会话里），只保留删除
                onClick = {},
                onRemove = { onRemoveImage(rel) }
            )
        }
        // 长文附件与普通文件同一种 chip：对用户而言都是「随消息带走的一个文件」
        (listOfNotNull(draft.longTextRelPath) + draft.fileRelPaths).forEach { rel ->
            PendingFileChip(
                name = rel.substringAfterLast('/'),
                onRemove = { onRemoveFile(rel) }
            )
        }
        if (draft.failedImages > 0) {
            Text(
                text = "有 " + draft.failedImages + " 张图片读取失败",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (draft.failedFiles > 0) {
            Text(
                text = "有 " + draft.failedFiles + " 个文件读取失败",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 输入条本体：正文 + 底部一行（目标按键、放弃键、发送键）。
 *
 * 尺寸与主页 [ChatComposer] 逐项相同——22dp 圆角、`onSurface@4%` 底、上 12/下 8/左 14/右 8 内距，
 * 15sp 正文与 40dp 发送键。用户从分享模式回到常规输入区时，看到的应是同一个输入框。
 *
 * 放弃分享的 ✕ 固定在输入框右上角：不占额外行高，也不与发送键相邻误触。
 */
@Composable
private fun ShareComposer(
    text: String,
    onTextChange: (String) -> Unit,
    targetLabel: String,
    pickerOpen: Boolean,
    sending: Boolean,
    canSend: Boolean,
    onTargetClick: () -> Unit,
    onTargetAnchor: (Rect) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    status: ComposerStatus
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 展开时箭头转 180°，与选择器的开合方向一致
    val chevronTurn by animateFloatAsState(
        targetValue = if (pickerOpen) 180f else 0f,
        animationSpec = tween(180),
        label = "targetChevron"
    )
    // 与主页同一分层：带底色的是「输入容器」，状态行排在容器**外面**的页面底上。
    // 若把状态行塞进容器，两枚胶囊会跟着 4% 底色一起被框住，看起来像输入框的一部分。
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(onSurface.copy(alpha = 0.04f))
        ) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
            ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    // 右上角留给放弃按钮，正文最后一行不钻到它下面
                    .padding(end = 22.dp)
                    .heightIn(min = 22.dp, max = 128.dp),
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = onSurface),
                cursorBrush = saltCursorBrush(),
                maxLines = 6,
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "补充你的要求（可留空）",
                                fontSize = 14.sp,
                                color = onSurface.copy(alpha = 0.35f),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 目标按键：工作区 + 会话合成一个入口，点开即两级列表
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .widthIn(max = 190.dp)
                        .onGloballyPositioned { onTargetAnchor(it.boundsInWindow()) }
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (pickerOpen) UiColors.rowSelected else onSurface.copy(alpha = 0.06f))
                        .saltRowClickable(RoundedCornerShape(9.dp)) { onTargetClick() }
                        .padding(start = 8.dp, end = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = FolderIcon,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = onSurface.copy(alpha = if (pickerOpen) 0.78f else 0.6f)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = targetLabel,
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            color = onSurface.copy(alpha = if (pickerOpen) 1f else 0.7f)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = ChevronDownIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(12.dp)
                                .rotate(chevronTurn),
                            tint = onSurface.copy(alpha = 0.45f)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        // 不可发时整块压到 40%：与主页发送键的禁用态同一口径
                        .background(
                            if (canSend) UiColors.accentFill else UiColors.accentFill.copy(alpha = 0.40f)
                        )
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    if (sending) {
                        Text(
                            text = "…",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = UiColors.onAccent
                        )
                    } else {
                        ArrowUpGlyph(UiColors.onAccent, 18.dp)
                    }
                }
            }
            }
            // 放弃分享：输入框右上角；点它立即退出分享模式并停在新会话
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 10.dp)
                    .size(26.dp)
                    .saltRowClickable(RoundedCornerShape(8.dp)) { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = CloseIcon,
                    contentDescription = "放弃这次分享",
                    tint = Palette.Neutral,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        // 状态行：与主页同一套两枚胶囊，此处只读——分享场景没有明细页可开
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ShareStatusChip(
                modifier = Modifier.weight(1f, fill = false),
                glyph = { tint -> StepsGlyph(tint = tint, iconSize = 11.dp) },
                text = statusLeftText(status)
            )
            ShareStatusChip(
                modifier = Modifier.weight(1f, fill = false),
                glyph = { tint -> com.zmf.agent.uikit.icons.CylinderGlyph(tint = tint, iconSize = 11.dp) },
                text = statusRightText(status)
            )
        }
    }
}

/** 状态行胶囊：与主页 [StatusChip] 同一尺寸，但不可点（分享场景没有明细页可开）。 */
@Composable
private fun ShareStatusChip(
    glyph: @Composable (Color) -> Unit,
    text: String,
    modifier: Modifier = Modifier
) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        glyph(tint)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 目标选择浮层：锚定在目标键上方（上方放不下时翻到下方）的两级面板。
 *
 * 窗口行为照 Windows 右键菜单：**挂载即出现、卸载即消失**，没有淡入淡出，也没有需要等待的退场。
 * 固定 232dp × 300dp，一级工作区页与二级会话页的外框逐像素相同；内容超出时各自在列表内滚动。
 * 从点开目标键到选中会话关闭，始终只有这一个 `Popup` 窗口：两级之间用 [AnimatedContent]
 * 在窗口内部横向推页，内容裁剪在面板圆角内。
 */
@Composable
private fun TargetPickerPopover(
    anchor: Rect,
    workspaces: List<WorkSpace>,
    sessions: List<ChatSession>,
    currentSessionId: String,
    currentWorkspaceId: String?,
    onPick: (ShareTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    // 固定尺寸；只有窗口小到放不下时才按可用空间夹一下。两页共用同一个值，翻页时窗口不缩放
    val width = minOf(PickerPopupWidth, config.screenWidthDp.dp - PickerEdgeMargin * 2)
    val height = minOf(PickerPopupHeight, config.screenHeightDp.dp - PickerEdgeMargin * 2)

    val gapPx = with(density) { PickerPopupGap.roundToPx() }
    val marginPx = with(density) { PickerEdgeMargin.roundToPx() }
    val provider = remember(anchor, gapPx, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                // 左边缘贴住目标键，并向屏幕内收至少 12dp；右侧同样留出边距
                val rightmost = (windowSize.width - popupContentSize.width - marginPx)
                    .coerceAtLeast(marginPx)
                val x = anchor.left.toInt().coerceIn(marginPx, rightmost)
                // 优先浮在目标键上方；上方放不下才翻到下方，两侧都越界时贴住可视区
                val above = anchor.top.toInt() - gapPx - popupContentSize.height
                val below = anchor.bottom.toInt() + gapPx
                val bottomLimit = (windowSize.height - popupContentSize.height - marginPx)
                    .coerceAtLeast(marginPx)
                val y = if (above >= marginPx) above else below.coerceIn(marginPx, bottomLimit)
                return IntOffset(x, y)
            }
        }
    }

    // null = 工作区页；非 null = 该工作区的会话页。「未分类」用 WorkspaceRef(null) 与「没选」区分开
    var openedSpace by remember { mutableStateOf<WorkspaceRef?>(null) }
    val currentSession = sessions.firstOrNull { it.id == currentSessionId }
    val activeSpaceId = currentSession?.workspaceId ?: currentWorkspaceId

    // Popup 会新建窗口：Compose 在新窗口里按系统资源重建 LocalDensity，
    // 主题按字号档位覆盖的 fontScale 不会带进来，面板文字会停在系统字号。
    // 进入 Popup 前取当前 density，再在弹窗内容里原样提供一次。
    val pickerDensity = LocalDensity.current

    Popup(
        popupPositionProvider = provider,
        // 返回键与点外部都走这里：在第二级先退回第一级，其他情况才真正关闭
        onDismissRequest = {
            if (openedSpace != null) openedSpace = null else onDismiss()
        },
        properties = PopupProperties(focusable = true)
    ) {
        CompositionLocalProvider(LocalDensity provides pickerDensity) {
            Surface(
                // 与 `uikit/common/SaltMenuPopover`（抽屉工作区／会话菜单）逐项一致：
                // 18dp 圆角、SurfaceHigh 底色、8dp 投影，不描边。
                // 原先的 10dp 投影 + 1dp 描边是本页独有的一套，浮层看起来比其它面板多一圈包边。
                shape = PickerShape,
                color = if (isDark) Palette.DarkSurfaceHigh else Palette.LightSurfaceHigh,
                shadowElevation = PickerShadow,
                modifier = Modifier
                    .width(width)
                    .height(height)
            ) {
            AnimatedContent(
                targetState = openedSpace,
                transitionSpec = {
                    // 一级 → 二级：工作区页向左退出，会话页从右侧补入；返回时整段反向
                    val forward = targetState != null
                    if (forward) {
                        (slideInHorizontally(tween(PickerPageMillis)) { it } +
                            fadeIn(tween(PickerPageMillis))) togetherWith
                            (slideOutHorizontally(tween(PickerPageMillis)) { -it } +
                                fadeOut(tween(PickerPageMillis)))
                    } else {
                        (slideInHorizontally(tween(PickerPageMillis)) { -it } +
                            fadeIn(tween(PickerPageMillis))) togetherWith
                            (slideOutHorizontally(tween(PickerPageMillis)) { it } +
                                fadeOut(tween(PickerPageMillis)))
                    }
                },
                label = "targetPickerPage",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(PickerShape)
            ) { space ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PickerPopupPadding)
                ) {
                    if (space == null) {
                        SpaceList(
                            workspaces = workspaces,
                            sessions = sessions,
                            activeSpaceId = activeSpaceId,
                            onOpen = { openedSpace = it }
                        )
                    } else {
                        SessionList(
                            workspaceId = space.id,
                            workspaceName = space.label(workspaces),
                            sessions = sessions,
                            current = currentSession,
                            onBack = { openedSpace = null },
                            onPick = onPick
                        )
                    }
                }
            }
            }
        }
    }
}

/** 工作区引用：用 id 区分，「未分类」的 id 为 null 但仍是有效选择。 */
private data class WorkspaceRef(val id: String?) {
    fun label(workspaces: List<WorkSpace>): String =
        id?.let { key -> workspaces.firstOrNull { it.id == key }?.name } ?: "未分类"
}

/** 行高与浮层尺寸：行高与抽屉的列表行对齐；浮层尺寸固定，不随内容或页面增减。 */
private val PickerRowHeight = 40.dp
private val PickerPopupWidth = 232.dp
private val PickerPopupHeight = 300.dp

/**
 * 浮层外观与 `uikit/common/SaltMenuPopover` 逐项一致：18dp 圆角、8dp 投影、不描边。
 *
 * 底面同用 SurfaceHigh（经 AgentTheme 映射为 `colorScheme.surface`），与抽屉菜单、
 * 输入框「＋」面板属于同一层次；不额外画 1dp 描边，避免圆角外出现一圈包边。
 */
private val PickerShape = RoundedCornerShape(18.dp)
private val PickerShadow = 8.dp
private const val PickerPageMillis = 200
private val PickerPopupGap = 8.dp
private val PickerPopupPadding = 8.dp
private val PickerEdgeMargin = 12.dp

/**
 * 面板字号与抽屉列表行同一套（字号只以 sp 表示，由 AgentTheme 的 fontScale 统一缩放，
 * 见 `UiScaleStore.STEPS`「小 / 标准 / 大 / 特大」）。
 *
 * - 工作区行：14sp，与抽屉工作区分组标题同号；
 * - 会话行：13sp、行高 17sp，与抽屉会话行（`AppDrawer` 的 `SessionTreeRow`）的标题完全一致；
 * - 条数 / 时间戳：10sp、行高 13sp，与抽屉会话行的时间行一致。
 *
 * 不再单独乘系数：弹窗内容继承父组合的 `LocalDensity`，档位放大对面板与抽屉同时生效。
 */
private val PickerWorkspaceTitleSize = 14.sp
private val PickerSessionTitleSize = 13.sp
private val PickerTitleLine = 17.sp
private val PickerMetaSize = 10.sp
private val PickerMetaLine = 13.sp

/** 第一页：一行「未分类」加每个工作区，右侧是会话条数。 */
@Composable
private fun SpaceList(
    workspaces: List<WorkSpace>,
    sessions: List<ChatSession>,
    activeSpaceId: String?,
    onOpen: (WorkspaceRef) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PickerRow(
            title = "未分类",
            trailing = countOf(sessions, null),
            selected = activeSpaceId == null,
            icon = { tint -> Icon(FolderIcon, null, Modifier.size(14.dp), tint = tint) },
            titleSize = PickerWorkspaceTitleSize,
            onClick = { onOpen(WorkspaceRef(null)) }
        )
        workspaces.forEach { space ->
            PickerRow(
                title = space.name,
                trailing = countOf(sessions, space.id),
                selected = activeSpaceId == space.id,
                icon = { tint -> Icon(FolderIcon, null, Modifier.size(14.dp), tint = tint) },
                titleSize = PickerWorkspaceTitleSize,
                onClick = { onOpen(WorkspaceRef(space.id)) }
            )
        }
    }
}

/** 第二页：左上角返回 + 工作区名，下面是「新建会话」占位条与该工作区的会话。 */
@Composable
private fun SessionList(
    workspaceId: String?,
    workspaceName: String,
    sessions: List<ChatSession>,
    current: ChatSession?,
    onBack: () -> Unit,
    onPick: (ShareTarget) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val candidates = remember(sessions, workspaceId) {
        sessions.filter { session ->
            !session.archived && session.workspaceId == workspaceId && session.listed
        }.take(40)
    }
    // 当前就停在这个工作区的空会话上时，「新建会话」行即当前目标
    val newSessionHere = current == null || (current.blank && current.workspaceId == workspaceId)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .saltRowClickable(RoundedCornerShape(8.dp)) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ArrowLeftIcon,
                    contentDescription = "返回工作区",
                    modifier = Modifier.size(14.dp),
                    tint = onSurface.copy(alpha = 0.7f)
                )
            }
            Text(
                text = workspaceName,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .weight(1f),
                // 返回标题就是工作区名：与一级列表里的工作区行同号（14sp），缩放走同一套 sp
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = PickerWorkspaceTitleSize,
                    lineHeight = PickerTitleLine
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.55f)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // 「新建会话」行：选定即真的在这个工作区新建并切过去（与抽屉的新建一致），
            // 不再是发送时才创建的占位
            PickerRow(
                title = "新建会话",
                trailing = null,
                selected = newSessionHere,
                icon = { tint -> Icon(PlusIcon, null, Modifier.size(14.dp), tint = tint) },
                onClick = { onPick(ShareTarget.NewSession(workspaceId)) }
            )
            if (candidates.isEmpty()) {
                Text(
                    text = "该工作区还没有会话",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.4f)
                )
            }
            candidates.forEach { session ->
                PickerRow(
                    title = session.title.ifBlank { "新会话" },
                    trailing = SessionStore.relativeTime(session.updatedAt),
                    selected = current?.id == session.id,
                    icon = { tint ->
                        // 会话行复用「轮」字形：与状态行同一枚图标，不另造一个
                        StepsGlyph(tint = tint, iconSize = 14.dp)
                    },
                    onClick = { onPick(ShareTarget.Session(session.id)) }
                )
            }
        }
    }
}

/**
 * 选择器里的一行：图标 + 主标题 + 右侧次要信息（会话条数或相对时间），单行 40dp。
 *
 * 选中态与抽屉的列表行同一口径：中性淡底 + 正文色满不透明 + Medium 字重，**不染字、不加竖条**。
 * 标题占据剩余宽度、超出用省略号：浮层只有屏宽的六成，固定宽度会让右侧时间戳被挤掉。
 *
 * 字号取自抽屉列表行（见文件中的 `Picker*Size` 常量）：标题默认按会话行 13sp／17sp 行高，
 * 工作区行传入 14sp；条数与时间戳统一 10sp／13sp 行高。三者都是 sp，随全局字号档位缩放。
 */
@Composable
private fun PickerRow(
    title: String,
    trailing: String?,
    selected: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    titleSize: TextUnit = PickerSessionTitleSize
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val tint = onSurface.copy(alpha = if (selected) 0.78f else 0.45f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高取「不小于 40dp」：标准档位下就是 40dp，特大档位文字变大时行自然被撑高，不裁字
            .heightIn(min = PickerRowHeight)
            .background(if (selected) UiColors.rowSelected else Color.Transparent)
            .saltRowClickable(RoundedCornerShape(10.dp)) { onClick() }
            .padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon(tint)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            // 右侧的条数/时间戳先量，标题拿剩下的宽度
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = titleSize,
                lineHeight = PickerTitleLine
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) onSurface else onSurface.copy(alpha = 0.62f)
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = PickerMetaSize,
                    lineHeight = PickerMetaLine
                ),
                maxLines = 1,
                color = onSurface.copy(alpha = if (selected) 0.5f else 0.3f)
            )
        }
    }
}

/** 工作区下的会话条数（不含归档）：给用户一个「这里有没有内容」的提示。 */
private fun countOf(sessions: List<ChatSession>, workspaceId: String?): String {
    val count = sessions.count { !it.archived && it.workspaceId == workspaceId && it.listed }
    return if (count == 0) "空" else count.toString() + " 条会话"
}

/**
 * 目标按键上的文字。
 *
 * 目标不是另存的一份选择，而是**当前会话本身**：已有会话只写标题（工作区由会话自身决定），
 * 空会话写「<工作区名> · 新建会话」，让用户一眼看出内容会落到哪里。
 */
private fun targetLabel(
    blankSession: Boolean,
    current: ChatSession?,
    currentWorkspaceId: String?,
    workspaces: List<WorkSpace>
): String {
    if (!blankSession && current != null) return current.title.ifBlank { "新会话" }
    val space = (current?.workspaceId ?: currentWorkspaceId)
        ?.let { id -> workspaces.firstOrNull { it.id == id }?.name }
        ?: "未分类"
    return space + " · 新建会话"
}

/**
 * 状态行显示的目标会话账目。
 *
 * 只按**当前会话**取数：空会话全零，已有会话取它自己的累计值。
 * 轮数按用户消息条数计（与主页 `statusRounds` 同一口径），缓存命中率按命中 / (命中 + 未命中) 折算。
 */
private fun statusOf(sessions: List<ChatSession>, currentSessionId: String): ComposerStatus {
    val session = sessions.firstOrNull { it.id == currentSessionId } ?: return ComposerStatus()
    val hit = session.cacheHitTokens
    val miss = session.cacheMissTokens
    val percent = if (hit + miss > 0) hit * 100f / (hit + miss) else null
    val perSec = if (session.lastTurnModelMillis > 0) {
        (session.lastTurnCompletionTokens * 1000L / session.lastTurnModelMillis).toInt()
    } else {
        null
    }
    return ComposerStatus(
        rounds = session.messages.count { it.role == "user" },
        steps = session.steps,
        tokensPerSec = perSec,
        totalTokens = session.promptTokens + session.completionTokens,
        cacheHitPercent = percent
    )
}
