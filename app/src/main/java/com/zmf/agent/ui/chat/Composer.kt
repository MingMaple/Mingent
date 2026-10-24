package com.zmf.agent.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.settings.BusySendMode
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette

/** 输入框下面那行状态：第几轮第几步、生成速度、累计 token 与上下文水位。 */
data class ComposerStatus(
    val rounds: Int = 0,
    val steps: Int = 0,
    val tokensPerSec: Int? = null,
    val totalTokens: Int = 0,
    val waterPercent: Int = 0
)

/** 上下文浮窗要显示的三段明细；窗口大小单独给，百分比在这里算，不麻烦调用方。 */
data class ContextUsageInfo(
    val used: Int = 0,
    val window: Int = 1_048_576,
    val systemTokens: Int = 0,
    val toolTokens: Int = 0,
    val messageTokens: Int = 0
)

/**
 * 「＋」浮窗里可点的条目，父会话按它分发到具体动作。
 *
 * 技能不进枚举：技能名本身就是数据，枚举里塞一个 SKILL 等于让调用方再回头去猜是哪一个。
 */
enum class ComposerCommand {
    ADD_FILE,
    ADD_GOAL,
    ADD_PLAN,
    COMPACT,
    DOWNLOAD_LOGS,
    CONTEXT
}

/** 当前开着哪一层浮窗。只有输入框自己关心，外面不用传。 */
private enum class ComposerPanelKind { NONE, PLUS, PERMISSION, CONTEXT, MODEL }

/**
 * 主页底部的输入区：排队消息 + 输入容器 + 状态行，四个浮窗浮在输入容器上方。
 *
 * 浮窗是纯浮层：自己只报 0 高度、向上溢出到输入框上方，所以它开合时消息列表和输入框一动不动。
 * 遮罩不归本组件管：父层在消息区之上、输入条之下铺一个 [ComposerScrim]，点到面板和输入条以外就收。
 * 面板开关只认三件事——点对应标签、点/划面板与输入条以外、返回键；键盘开合与焦点变化一律不动它。
 * 开关状态仍然全在这里 remember，父层只通过 [ComposerPanelHost] 拿「开着没」和「关掉」。
 */
@Composable
fun ChatComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isStreaming: Boolean,
    hasAttachment: Boolean,
    planMode: Boolean,
    activeGoal: Boolean,
    busyMode: BusySendMode,
    permissionLabel: String,
    permissionLevel: PermissionLevel,
    skills: List<String>,
    queue: List<QueuedMessage>,
    onEditQueued: (QueuedMessage) -> Unit,
    onDeleteQueued: (QueuedMessage) -> Unit,
    onSendQueued: (QueuedMessage) -> Unit,
    onPlus: () -> Unit,
    onMention: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPermissionPick: (PermissionLevel) -> Unit,
    onComposerCommand: (ComposerCommand) -> Unit,
    onSkillPick: (String) -> Unit,
    status: ComposerStatus,
    usage: ContextUsageInfo,
    onOpenContextPage: () -> Unit,
    modelLabel: String,
    effortLabel: String,
    // 模型 / 推理等级二级窗：点一级窗里那两行就在同一张卡片里展开，不再跳设置页
    quick: QuickSheetState,
    modifier: Modifier = Modifier,
    // 父层拿它铺遮罩：不给也不会坏（自建一份就没人看）。给了就由本组件单向写 open、父层回调 dismiss
    panelHost: ComposerPanelHost = remember { ComposerPanelHost() }
) {
    var panel by remember { mutableStateOf(ComposerPanelKind.NONE) }
    val panelOpen = panel != ComposerPanelKind.NONE
    var editingQueueId by remember { mutableStateOf<Long?>(null) }
    // 插完 "/plan " 这类模板后面板必须马上让位：否则模板本身又被当成一次过滤，浮窗赖着不走
    var slashClosed by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = Palette.AccentGreen
    // 只挂附件不写字也是有效发送：曾经只按文本判，图片/文件就永远发不出去
    val canSend = value.text.isNotBlank() || hasAttachment
    // 被编辑的那条已经被删掉 / 发出去时，描边跟着消失，不会留一条幽灵编辑态
    val editingId = editingQueueId?.takeIf { id -> queue.any { it.id == id } }

    // 收面板只有三个入口：标签（上面那几个 clickable）、父层的 ComposerScrim、这里这条返回键。
    // 键盘开合、焦点变化、点输入框、往框里打字都不许收——「点输入框面板就消失」就是这么来的 bug。
    val closePanel: () -> Unit = { panel = ComposerPanelKind.NONE }
    BackHandler(enabled = panelOpen) { closePanel() }
    // 对外只开一个把手：open 决定遮罩铺不铺，dismiss 收面板。外面不用知道现在是哪一层浮窗
    SideEffect {
        panelHost.bind(open = panelOpen, close = closePanel)
    }

    // 斜杠指令：以 "/" 开头就把同一张面板当过滤结果用；删掉 "/" 或一条都不命中时它自己就收了，
    // 所以不需要额外的关闭状态，输入框里的文本就是唯一真相
    val slashQuery: String? = if (value.text.startsWith("/")) value.text.drop(1).trim() else null
    val slashActive = !slashClosed && slashQuery != null && composerPanelHasMatch(skills, slashQuery)
    val commandPanelOpen = panel == ComposerPanelKind.PLUS || slashActive

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = queue.isNotEmpty(),
            enter = fadeIn(Motion.fadeIn()) + slideInVertically(Motion.spring()) { it / 2 },
            exit = fadeOut(Motion.fadeOut()) + slideOutVertically(Motion.spring()) { it / 2 }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                queue.forEachIndexed { index, message ->
                    if (index > 0) Spacer(modifier = Modifier.height(4.dp))
                    QueuedRow(
                        text = message.text,
                        editing = message.id == editingId,
                        onEdit = {
                            editingQueueId = message.id
                            onEditQueued(message)
                        },
                        onDelete = {
                            editingQueueId = null
                            onDeleteQueued(message)
                        },
                        onSendOne = {
                            editingQueueId = null
                            onSendQueued(message)
                        }
                    )
                }
            }
        }

        // 浮窗不进布局流：只画在输入框上方，开合时不占一丝高度。
        // 之前它们跟输入框并排排在 Column 里，一展开就把上面 weight(1f) 的消息列表压小，
        // 用户看到的「窗口开关时突然跳一下」就是这么来的。
        // 它跟输入条同在这个 Column 里，所以键盘推上来时两者是同一段位移，浮窗不用另外跟 IME。
        // 当前该显示哪一张面板：斜杠过滤优先复用「＋」那张
        val activePanel = if (commandPanelOpen) ComposerPanelKind.PLUS else panel
        ComposerPanelOverlay(onDismiss = closePanel) {
            // 整个浮层只做一次进出动画，面板之间切换交给 Crossfade 换内容。
            // 以前每张卡片各带一套上滑 + 淡入，切面板时旧卡片往下滑、新卡片往上滑，
            // 两张同时在场互相穿过，看起来就是「切换不连贯」
            AnimatedVisibility(
                visible = activePanel != ComposerPanelKind.NONE,
                enter = fadeIn(Motion.fadeIn()) +
                    slideInVertically(Motion.spring()) { it / 6 } +
                    scaleIn(Motion.spring(), initialScale = 0.96f),
                exit = fadeOut(Motion.fadeOut()) +
                    slideOutVertically(Motion.spring()) { it / 6 } +
                    scaleOut(Motion.spring(), targetScale = 0.96f)
            ) {
                Crossfade(
                    targetState = activePanel,
                    animationSpec = Motion.fadeIn(),
                    label = "composerPanel"
                ) { kind ->
                    when (kind) {
                        ComposerPanelKind.NONE -> Unit
                        ComposerPanelKind.PLUS -> ComposerCommandPanel(
                            skills = skills,
                            planMode = planMode,
                            activeGoal = activeGoal,
                            onPick = { command ->
                                // 斜杠调出来的那一条选完必须抹掉 "/xxx"，否则这句话会连着发第二次
                                if (slashActive) onValueChange(TextFieldValue(""))
                                panel = ComposerPanelKind.NONE
                                onComposerCommand(command)
                            },
                            onPickSkill = { name ->
                                // 这里只管清空；插不插提示文本交给父会话，输入框的最终内容只有一个写入口
                                if (slashActive) onValueChange(TextFieldValue(""))
                                panel = ComposerPanelKind.NONE
                                onSkillPick(name)
                            },
                            // /plan、/goal 只往输入框里递一个模板；执行留给 ViewModel 的斜杠解析
                            onPickTemplate = { template ->
                                slashClosed = true
                                panel = ComposerPanelKind.NONE
                                onValueChange(TextFieldValue(template, TextRange(template.length)))
                            },
                            slashMode = slashActive,
                            filter = slashQuery.orEmpty()
                        )

                        ComposerPanelKind.PERMISSION -> ComposerPermissionPanel(
                            current = permissionLevel,
                            onPick = { level ->
                                panel = ComposerPanelKind.NONE
                                onPermissionPick(level)
                            }
                        )

                        ComposerPanelKind.CONTEXT -> ComposerContextPanel(
                            usage = usage,
                            onOpenDetail = {
                                panel = ComposerPanelKind.NONE
                                onOpenContextPage()
                            }
                        )

                        // 模型 / 推理等级：一级窗两行「当前值 + ›」，点开在该卡片内就地展开二级列表
                        ComposerPanelKind.MODEL -> ComposerModelPanel(
                            modelLabel = modelLabel,
                            effortLabel = effortLabel,
                            quick = quick,
                            onClose = { panel = ComposerPanelKind.NONE }
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(onSurface.copy(alpha = 0.04f))
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
        ) {
            // 这里以前挂过「按下就收面板」：键盘一弹、手指一碰输入框，面板就没了，写完还得再点一次标签。
            // 现在点输入框、焦点进出、换行都不动面板——收面板只认标签、遮罩外点、返回键三件事。
            BasicTextField(
                value = value,
                onValueChange = { updated ->
                    // 只复位斜杠模板标记：面板不随打字收，斜杠面板靠文本自己判活
                    slashClosed = false
                    onValueChange(updated)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 22.dp, max = 128.dp),
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = onSurface),
                cursorBrush = SolidColor(accent),
                maxLines = 6,
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                text = if (queue.isEmpty()) {
                                    "发消息，/ 调用指令"
                                } else {
                                    "发送排队消息，长按 ↑ 插话发送全部"
                                },
                                // 比正文小 1sp，且硬性一行：换行会把输入框撑成两行高，占位提示不该有这种存在感
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
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            panel = if (panel == ComposerPanelKind.PLUS) {
                                ComposerPanelKind.NONE
                            } else {
                                ComposerPanelKind.PLUS
                            }
                            onPlus()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = PlusIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = onSurface
                    )
                }
                PermissionChip(
                    label = permissionLabel,
                    onClick = {
                        panel = if (panel == ComposerPanelKind.PERMISSION) {
                            ComposerPanelKind.NONE
                        } else {
                            ComposerPanelKind.PERMISSION
                        }
                    }
                )
                // 计划 / 目标是「正开着」的状态灯，不是按钮：不可点，因此没有 ripple，也不吃手势；
                // 不开的时候连位置都不留，空白不该冒充入口
                if (planMode) {
                    ModeBadge {
                        Icon(
                            imageVector = PlanIcon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = accent
                        )
                    }
                }
                if (activeGoal) {
                    ModeBadge { TargetGlyph(tint = accent, iconSize = 16.dp) }
                }
                // 「@」删掉了：选文件在「＋」里已经有入口，同一个动作给两个按钮只会让人犹豫
                Spacer(modifier = Modifier.weight(1f))
                // 第一颗：模型 + 推理等级。空心细圆圈里一颗实心点，比原来的思考圆点更明确是「设定」
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            panel = if (panel == ComposerPanelKind.MODEL) {
                                ComposerPanelKind.NONE
                            } else {
                                ComposerPanelKind.MODEL
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    ModelGlyph(tint = onSurface.copy(alpha = 0.72f), iconSize = 20.dp)
                }
                // 第二颗：上下文占用。缩略态就是一个圆环：底环是暗的，亮起来的弧长就是占比
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            panel = if (panel == ComposerPanelKind.CONTEXT) {
                                ComposerPanelKind.NONE
                            } else {
                                ComposerPanelKind.CONTEXT
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    ContextRing(
                        percent = status.waterPercent,
                        iconSize = 22.dp,
                        trackTint = onSurface.copy(alpha = 0.12f),
                        progressTint = accent
                    )
                }
                if (isStreaming) {
                    // 停止和发送是两件事：一个是打断，一个是按繁忙策略投递，所以并排放两个键
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(onSurface.copy(alpha = 0.12f))
                            .clickable(onClick = onStop),
                        contentAlignment = Alignment.Center
                    ) {
                        UnitGlyph(iconSize = 16.dp) {
                            drawRoundRect(
                                color = onSurface.copy(alpha = 0.85f),
                                topLeft = Offset(4.6f, 4.6f),
                                size = Size(8.8f, 8.8f),
                                cornerRadius = CornerRadius(2.6f, 2.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (canSend) accent else accent.copy(alpha = 0.35f))
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    // 忙时的发送键保留自己的身份：排队 / 插话，不跟停止键混用同一个图标
                    when {
                        isStreaming && busyMode == BusySendMode.QUEUE -> QueueGlyph(Palette.LightText, 18.dp)
                        isStreaming && busyMode == BusySendMode.STEER -> SteerGlyph(Palette.LightText, 18.dp)
                        else -> ArrowUpGlyph(Palette.LightText, 18.dp)
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val left = buildString {
                append(status.rounds).append(" 轮 ").append(status.steps).append(" 步")
                // 没有速度就是没在生成，这段干脆不占位置
                status.tokensPerSec?.let { append(" · ").append(it).append(" tok/s") }
            }
            Text(
                text = left,
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.4f),
                maxLines = 1
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = composerTokenText(status.totalTokens) + " tok · 水位 " + status.waterPercent + "%",
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.4f),
                maxLines = 1
            )
        }
    }
}

/**
 * 模型 / 推理等级小窗：两行，左边是名字，右边是当前值加一个「›」。
 *
 * 观感跟 [ComposerCommandPanel] 那几个浮窗完全一致（20dp 圆角、surface 底，不投影不描边），
 * 但不用 Material 的 DropdownMenu：菜单自带自己的底色、内边距和定位逻辑，
 * 浮在输入框上方会跟本工程这套卡片观感打架，也不受 ComposerPanelOverlay 的停靠控制。
 */
@Composable
private fun ComposerModelPanel(
    modelLabel: String,
    effortLabel: String,
    quick: QuickSheetState,
    onClose: () -> Unit
) {
    // 一级窗两行入口 / 二级窗列表，就靠这一个状态切换：卡片、宽度、圆角全是同一套。
    // 面板收起时本组件整个离开组合，remember 自然归零，下次点开仍然是一级窗
    var page by remember { mutableStateOf(QuickSheetPage.ROOT) }
    val shape = RoundedCornerShape(20.dp)
    // 卡片本体不投影、不描边：投影按矩形包围盒落影，圆角外侧会露出一圈直角阴影，
    // 看着像「圆弧贴在一张方卡上」；靠 surface 底色与页面底色区分就够了，也更符合这套扁平风格
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        // 卡片自己吃掉点击，不然点空白处会落到输入框上把键盘拉起来
        modifier = Modifier
            .fillMaxWidth()
            .blockTapsOnly()
    ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        // 换页时高度跟着变，直接换会「啪」地跳一下：交给弹簧动画收尾
                        .animateContentSize(Motion.spring())
                ) {
                    when (page) {
                        QuickSheetPage.ROOT -> {
                            ModelPickRow(
                                title = "模型",
                                value = modelLabel,
                                onClick = { page = QuickSheetPage.MODEL }
                            )
                            ModelPickRow(
                                title = "推理等级",
                                value = effortLabel,
                                onClick = { page = QuickSheetPage.EFFORT }
                            )
                        }

                        QuickSheetPage.MODEL -> QuickModelPage(
                            state = quick,
                            onPick = { providerId, modelId ->
                                quick.onPickModel(providerId, modelId)
                                onClose()
                            }
                        )

                        QuickSheetPage.EFFORT -> QuickEffortPage(
                            state = quick,
                            onPick = { key ->
                                quick.onPickEffort(key)
                                onClose()
                            }
                        )
                    }
                }
    }
}

/** 小窗里的一行：「模型      DeepSeek-V4.1-Flash  ›」，点整行走。 */
@Composable
private fun ModelPickRow(title: String, value: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 15.sp, color = onSurface)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            // 值还没拿到时给一个「—」，留白会让这一行看着像坏了
            text = value.ifBlank { "—" },
            modifier = Modifier.widthIn(max = 200.dp),
            fontSize = 13.sp,
            color = onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(6.dp))
        ChevronRightGlyph(tint = onSurface.copy(alpha = 0.35f), iconSize = 12.dp)
    }
}

/**
 * 浮窗停靠层：浮窗**浮在**输入框正上方，左右各收 12dp，且不占任何布局高度。
 *
 * 这一版回到「上报同宽 × 0 高、内容往负方向画出去」：用户的口径很明确——面板是最上层窗口，
 * 消息区不该跟着上下移。曾经有一版改成上报真实高度，理由是「0 高节点收不到触摸」，
 * 那个判断**是错的**（当时点击坐标按缩放后的预览尺寸算，差了一倍）；旧版面板行本来就点得动。
 * 结论：0 高度只影响「本节点自己」的命中，溢出的子节点照常收事件，别拿这条当理由改成占高。
 *
 * [onDismiss]：面板左右那 12dp 留白也属于「面板以外」，点它同样收面板。
 */
@Composable
private fun ComposerPanelOverlay(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
                )
                layout(placeable.width, 0) {
                    placeable.place(x = 0, y = -placeable.height - 8.dp.roundToPx())
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // 换面板时高度会变（「＋」是一长列、模型只有两行）：这一层把量到的高度做成弹簧过渡，
        // 内容贴底对齐，高度长出来时往上长（输入条那侧永远不动）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 这一层的边界就是「面板 + 左右各 12dp 留白」的可见轮廓：
                // 点留白等于点面板以外，收面板；点面板本身由卡片的子节点先接
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = 12.dp)
                .animateContentSize(animationSpec = Motion.spring()),
            contentAlignment = Alignment.BottomCenter
        ) {
            content()
        }
    }
}

/**
 * 一条排队消息：左边空心圆、中间单行文本、右边三个 36dp 热区（编辑 / 删除 / 立即发送）。
 * 正在编辑的那条描一圈 AccentGreen，因为用户改完回头看，需要知道改的是哪一条。
 */
@Composable
private fun QueuedRow(
    text: String,
    editing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSendOne: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val tint = if (editing) Palette.AccentGreen else onSurface.copy(alpha = 0.4f)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(onSurface.copy(alpha = 0.06f))
            .then(if (editing) Modifier.border(1.dp, Palette.AccentGreen, shape) else Modifier)
            .height(40.dp)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UnitGlyph(iconSize = 13.dp) {
            drawCircle(color = tint, radius = 5.2f, center = Offset(9f, 9f), style = Stroke(width = 1.7f))
        }
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        QueueAction(onClick = onEdit) { actionTint -> EditGlyph(actionTint, 17.dp) }
        QueueAction(onClick = onDelete) { actionTint -> TrashGlyph(actionTint, 17.dp) }
        QueueAction(onClick = onSendOne) { actionTint -> ArrowUpGlyph(actionTint, 17.dp) }
    }
}

/** 排队行右边的小图钉：36dp 热区，图标本身比热区小一圈，避免误触相邻那一颗。 */
@Composable
private fun QueueAction(onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        glyph(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

/** 权限芯片：「⚠ 仅可查看」，点一下弹出三档选择器。 */
@Composable
private fun PermissionChip(label: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UnitGlyph(iconSize = 13.dp) {
            drawPath(
                path = unitPath {
                    moveTo(9f, 2.8f)
                    lineTo(15.6f, 15.2f)
                    lineTo(2.4f, 15.2f)
                    close()
                },
                color = onSurface.copy(alpha = 0.65f),
                style = unitStroke(1.6f)
            )
            drawPath(
                path = unitPath {
                    moveTo(9f, 7.4f)
                    lineTo(9f, 11.2f)
                },
                color = onSurface.copy(alpha = 0.65f),
                style = unitStroke(1.6f)
            )
            drawCircle(color = onSurface.copy(alpha = 0.65f), radius = 0.9f, center = Offset(9f, 13.2f))
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 权限 chip 右边的一枚状态灯：只报告「当前处于这个模式」。
 *
 * 刻意不加 clickable：它背后没有对应页面可跳，做成按钮只会让人点两下才发现点不动。
 * 26dp 只是给 16dp 图标留呼吸位，这个尺寸下不消费任何手势。
 */
@Composable
private fun ModeBadge(glyph: @Composable () -> Unit) {
    Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        glyph()
    }
}

/** 目标：同心圆靶心，跟面板里「目标」那一行同形，缩到 16dp 也不会糊。 */
@Composable
private fun TargetGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawCircle(color = tint, radius = 5.6f, center = Offset(9f, 9f), style = unitStroke(1.6f))
        drawCircle(color = tint, radius = 1.9f, center = Offset(9f, 9f), style = unitStroke(1.6f))
    }
}

/** 竖箭头：发送，也用作「立即发出这条」。 */
@Composable
private fun ArrowUpGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawPath(
            path = unitPath {
                moveTo(9f, 15f)
                lineTo(9f, 4f)
                moveTo(4.6f, 8.4f)
                lineTo(9f, 4f)
                lineTo(13.4f, 8.4f)
            },
            color = tint,
            style = unitStroke(1.9f)
        )
    }
}

/** 排队：两条「圆点 + 横线」，就是待发消息排成一行行的意思。 */
@Composable
private fun QueueGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawPath(
            path = unitPath {
                moveTo(8f, 6.2f)
                lineTo(15.2f, 6.2f)
            },
            color = tint,
            style = unitStroke(1.7f)
        )
        drawPath(
            path = unitPath {
                moveTo(8f, 11.8f)
                lineTo(15.2f, 11.8f)
            },
            color = tint,
            style = unitStroke(1.7f)
        )
        drawCircle(color = tint, radius = 1.5f, center = Offset(4.2f, 6.2f))
        drawCircle(color = tint, radius = 1.5f, center = Offset(4.2f, 11.8f))
    }
}

/** 插话：一道闪电，跟「排队」区分开。 */
@Composable
private fun SteerGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        val bolt = Path().apply {
            moveTo(10.8f, 3.4f)
            lineTo(6f, 10f)
            lineTo(9.2f, 10f)
            lineTo(7.4f, 14.9f)
            lineTo(12.6f, 8.4f)
            lineTo(9.4f, 8.4f)
            close()
        }
        drawPath(path = bolt, color = tint)
    }
}

/** 编辑排队消息：一支斜向下的笔，形似 DSH 的方框笔但不加外框，小尺寸下更清楚。 */
@Composable
private fun EditGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawPath(
            path = unitPath {
                moveTo(11.6f, 3.0f)
                lineTo(15.0f, 6.4f)
                lineTo(7.4f, 14.0f)
                lineTo(3.4f, 14.6f)
                lineTo(4.0f, 10.6f)
                close()
            },
            color = tint,
            style = unitStroke(1.5f)
        )
        drawPath(
            path = unitPath {
                moveTo(4.0f, 10.6f)
                lineTo(7.4f, 14.0f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
    }
}

/** 删除排队消息：垃圾桶。 */
@Composable
private fun TrashGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawPath(
            path = unitPath {
                moveTo(3.6f, 5.4f)
                lineTo(14.4f, 5.4f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
        drawPath(
            path = unitPath {
                moveTo(6.8f, 5.4f)
                lineTo(6.8f, 3.4f)
                lineTo(11.2f, 3.4f)
                lineTo(11.2f, 5.4f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
        drawPath(
            path = unitPath {
                moveTo(5.0f, 5.4f)
                lineTo(5.8f, 15.0f)
                lineTo(12.2f, 15.0f)
                lineTo(13.0f, 5.4f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
        drawPath(
            path = unitPath {
                moveTo(7.9f, 8.2f)
                lineTo(7.9f, 12.4f)
                moveTo(10.1f, 8.2f)
                lineTo(10.1f, 12.4f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
    }
}

/** 上下文 / 模型共用的圆柱体：数据装在该装的地方，一眼看得懂。 */
@Composable
internal fun CylinderGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawOval(
            color = tint,
            topLeft = Offset(3.6f, 2.6f),
            size = Size(10.8f, 3.6f),
            style = unitStroke(1.5f)
        )
        drawPath(
            path = unitPath {
                moveTo(3.6f, 4.4f)
                lineTo(3.6f, 12.4f)
                moveTo(14.4f, 4.4f)
                lineTo(14.4f, 12.4f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(3.6f, 10.6f),
            size = Size(10.8f, 3.6f),
            style = unitStroke(1.5f)
        )
        drawPath(
            path = unitPath {
                moveTo(4.8f, 8.6f)
                lineTo(13.2f, 8.6f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
    }
}

/** 模型：一个细线圆圈 + 中央一颗实心点，像正对着的一枚镜头，不依赖图标库。 */
@Composable
private fun ModelGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawCircle(
            color = tint,
            radius = 6.4f,
            center = Offset(9f, 9f),
            style = unitStroke(1.3f)
        )
        drawCircle(color = tint, radius = 2.1f, center = Offset(9f, 9f))
    }
}

/**
 * 上下文圆环：底环永远画满一圈（暗），进度弧从 12 点方向顺时针铺开，弧长就是占用百分比。
 *
 * 环内故意不写字——缩略态要的是「还剩多少」的一眼观感，写百分比在 22dp 里反而糊成一团。
 * 满 100% 时直接画整圆：360° 的圆弧在部分设备上会因为起点终点重合而不出墨。
 */
@Composable
private fun ContextRing(percent: Int, iconSize: Dp, trackTint: Color, progressTint: Color) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val strokeWidth = 2.2.dp.toPx()
        // 描边是从圆心半径往外扩 strokeWidth / 2 的，减掉它才不会被裁掉半条线
        val radius = (size.minDimension - strokeWidth) / 2f
        val middle = center
        val fraction = percent.coerceIn(0, 100) / 100f
        drawCircle(
            color = trackTint,
            radius = radius,
            center = middle,
            style = Stroke(width = strokeWidth)
        )
        if (fraction <= 0f) return@Canvas
        val arcStyle = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        if (fraction >= 1f) {
            drawCircle(color = progressTint, radius = radius, center = middle, style = arcStyle)
        } else {
            drawArc(
                color = progressTint,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(middle.x - radius, middle.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = arcStyle
            )
        }
    }
}

/** 右侧的「›」：告诉用户这一行点下去会换一页。 */
@Composable
private fun ChevronRightGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawPath(
            path = unitPath {
                moveTo(7.0f, 4.6f)
                lineTo(11.4f, 9f)
                lineTo(7.0f, 13.4f)
            },
            color = tint,
            style = unitStroke(1.7f)
        )
    }
}

/**
 * 18×18 单位坐标系的小图标：调用方按 18 的坐标写路径，这里按实际 dp 等比缩放。
 * 图标尺寸换了几档线宽也不会跟着变粗，深色浅色共用同一份形状。
 */
@Composable
internal fun UnitGlyph(iconSize: Dp, draw: DrawScope.() -> Unit) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val unit = this.size.minDimension / 18f
        scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
            draw()
        }
    }
}

/** 单位坐标系里的描边规格：端点、拐角都收圆，跟 DSH 图标的观感一致。 */
private fun unitStroke(width: Float): Stroke =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 拼路径的小工具，专门给 [UnitGlyph] 里的 18×18 坐标用。 */
private fun unitPath(build: Path.() -> Unit): Path = Path().apply(build)

/** 11300 → 11.3K、1048576 → 1M；用整数算，避免浮点在状态行上抖出 11.299999。 */
internal fun composerTokenText(count: Int): String {
    if (count < 1_000) return count.toString()
    val unit = if (count < 1_000_000) 1_000 else 1_000_000
    val tenths = (count + unit / 20) / (unit / 10)
    val whole = tenths / 10
    val rest = tenths % 10
    val suffix = if (unit == 1_000) "K" else "M"
    return if (rest == 0) whole.toString() + suffix else whole.toString() + "." + rest + suffix
}
