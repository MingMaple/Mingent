package com.zmf.agent.feature.chat
import com.zmf.agent.core.model.BusySendMode
import com.zmf.agent.uikit.icons.PlusIcon
import com.zmf.agent.uikit.icons.CylinderGlyph
import com.zmf.agent.uikit.icons.PlanIcon
import com.zmf.agent.uikit.icons.UnitGlyph
import com.zmf.agent.uikit.icons.unitPath
import com.zmf.agent.uikit.icons.unitStroke

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.theme.Motion
import kotlin.math.roundToInt
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors

/**
 * 输入框下面那行状态：左「轮 / 步 / 速度」、右「累计 token / 缓存命中」，两枚都可点开明细。
 *
 * 该区域不显示上下文水位：水位已在「上下文」按钮的浮窗中显示，
 * 一屏两处报同一个百分比只是噪音。
 */
data class ComposerStatus(
    val rounds: Int = 0,
    val steps: Int = 0,
    /** 输出速度（tok/s）；没有样本时为 null，这一格就不占位置。 */
    val tokensPerSec: Int? = null,
    /** 本会话累计处理过的 tokens（输入 + 输出）。 */
    val totalTokens: Int = 0,
    /** 提示词缓存命中率（0–100）；服务端没报过这项就是 null，界面显示「—」。 */
    val cacheHitPercent: Float? = null
)

/**
 * 两枚状态胶囊点开的那两张浮窗要的明细。
 *
 * 全部来自本会话累计（[com.zmf.agent.feature.chat.TimingSnapshot]）：换会话就换一套数，
 * 用户看的是「这条会话到底花了多少」。没有样本的项给 null，界面写「—」，绝不写 0。
 */
data class ComposerStatsInfo(
    val modelMillis: Long = 0,
    val toolMillis: Long = 0,
    /** 首 token 平均等待；一次都没等到过就是 null。 */
    val ttftAverageMillis: Long? = null,
    val tokensPerSec: Int? = null,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val cacheHitPercent: Float? = null,
    val outputTokens: Int = 0
)

/** 上下文浮窗要显示的三段明细；窗口大小单独给出，百分比在此计算，减轻调用方负担。 */
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
 * 技能不纳入枚举：技能名本身即为数据，枚举中增加一个 SKILL 会使调用方需再次判断具体是哪一个。
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
private enum class ComposerPanelKind { NONE, PLUS, PERMISSION, CONTEXT, MODEL, STATS, TOKEN }

/**
 * 主页底部的输入区：排队消息 + 输入容器 + 状态行，四个浮窗浮在输入容器上方。
 *
 * 浮窗是纯浮层：自己只报 0 高度、向上溢出到输入框上方，所以它开合时消息列表和输入框一动不动。
 * 遮罩不归本组件管：父层在消息区之上、输入条之下铺一个 [ComposerScrim]，点到面板和输入条以外就收。
 * 面板开关只认三件事：点对应标签、点/划面板与输入条以外、返回键；键盘开合与焦点变化一律不动它。
 * 开关状态仍然全在这里 remember，父层只通过 [ComposerPanelHost] 拿「开着没」和「关掉」。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    /**
     * 长按发送键：插话发送全部排队消息（对应 DSH 的 Ctrl/Cmd+Enter）。
     *
     * 与短按互补：短按按「繁忙时发送行为」投递当前草稿，长按则把队列整体插进正在跑的这一轮。
     */
    onSteerAll: () -> Unit,
    onStop: () -> Unit,
    onPermissionPick: (PermissionLevel) -> Unit,
    onComposerCommand: (ComposerCommand) -> Unit,
    onSkillPick: (String) -> Unit,
    status: ComposerStatus,
    /** 两枚状态胶囊点开的明细（会话统计 / Token 用量）。 */
    stats: ComposerStatsInfo,
    usage: ContextUsageInfo,
    onOpenContextPage: () -> Unit,
    modelLabel: String,
    effortLabel: String,
    // 模型 / 推理等级二级窗：点击一级窗中的两行即可在同张卡片内展开，无需跳转设置页
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
    // 三条分工：线/图标用 accentFg，填充面用 accentFill，压在填充上的图标用 onAccent。
    // 浅色三者分别是 #4176E6 / #4176E6 / 白；深色是 #7AAAFF / #7AAAFF / 墨。
    val accent = UiColors.accentFg
    val accentFill = UiColors.accentFill
    val onAccent = UiColors.onAccent
    // 只挂附件不写字也是有效发送：曾经只按文本判，图片/文件就永远发不出去。
    // 草稿为空但队列非空时同样可用：此时发送键的语义是「发送（插话）排队消息」，与占位提示一致
    val canSend = value.text.isNotBlank() || hasAttachment || queue.isNotEmpty()
    // 被编辑的消息已删除或发送后，描边随之消失，不会残留无效编辑态
    val editingId = editingQueueId?.takeIf { id -> queue.any { it.id == id } }

    // 收面板只有三个入口：标签（上面那几个 clickable）、父层的 ComposerScrim、这里这条返回键。
    // 键盘开合、焦点变化、点输入框、往框里打字都不许收：「点输入框面板就消失」就是这么来的。
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
        // 此前它们与输入框并列于 Column 中，展开时会将上方 weight(1f) 的消息列表压缩，
        // 用户观察到的「窗口开关时突然跳动」即由此产生。
        // 它跟输入条同在这个 Column 里，所以键盘推上来时两者是同一段位移，浮窗不用另外跟 IME。
        // 当前该显示哪一张面板：斜杠过滤优先复用「＋」那张
        val activePanel = if (commandPanelOpen) ComposerPanelKind.PLUS else panel
        ComposerPanelOverlay(onDismiss = closePanel) {
            // **只有一套动效**，与「本轮用量」弹层完全一致：出现时围绕底边从 0.90 弹到 1，
            // 关闭时整块立即摘除；面板互切同样是旧的直接消失、新的从头播一遍。
            // 缩放施加在卡片**外层**的 graphicsLayer 上（与 SaltPopover 相同）：卡片连同阴影
            // 先按原尺寸画好，再由外层整体缩放。若把缩放落到卡片自身节点上，阴影会在动画期间
            // 按矩形包围盒重绘——出现时又深又硬，落定后才变回那圈浅影。
            if (activePanel != ComposerPanelKind.NONE) {
                // 换面板时从 0.90 重新弹起：animateFloatAsState 的动画实例会跨面板复用，
                // 直接改目标值会先往 0.90 回缩一段再弹起，因此用 Animatable 显式重播。
                val scale = remember { Animatable(0.90f) }
                LaunchedEffect(activePanel) {
                    scale.snapTo(0.90f)
                    scale.animateTo(1f, Motion.softSpring())
                }
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                ) {
                    when (activePanel) {
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

                        // 状态行左胶囊：本会话的时间账与输出速度
                        ComposerPanelKind.STATS -> ComposerStatsPanel(stats = stats)

                        // 状态行右胶囊：token 总账与提示词缓存
                        ComposerPanelKind.TOKEN -> ComposerTokenPanel(stats = stats)

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
            // 此处此前曾设置「按下即收起面板」：键盘弹起、手指触到输入框后面板即消失，输入完成后还需再次点击标签。
            // 现在点输入框、焦点进出、换行都不动面板：收面板只认标签、遮罩外点、返回键三件事。
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
                // 第一颗：模型 + 推理等级。空心细圆圈内含实心点，较原思考圆点更明确表示「设定」
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        // 面板展开时给底色：DSH 的触发按钮在 aria-expanded 时同样吃 interactive-bg-hover，
                        // 让「这个按钮正开着」不必只靠面板本身表达
                        .background(if (panel == ComposerPanelKind.MODEL) UiColors.rowSelected else Color.Transparent)
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
                        .background(if (panel == ComposerPanelKind.CONTEXT) UiColors.rowSelected else Color.Transparent)
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
                        // 跟上下文浮窗里那个百分号同一口径（同一个 used / window、同样「不足 1% 显示 1%」），
                        // 圆环和浮窗两处报的必须是同一个数
                        percent = contextPercent(usage),
                        iconSize = 22.dp,
                        // 环的深浅照 DSH：底槽取 border-l3，进度弧取 label-tertiary。
                        // 该环不使用强调蓝：蓝在本工程只表示主操作与选中，用量水位不属于这两类
                        trackTint = UiColors.meterRingTrack,
                        progressTint = UiColors.meterTertiary
                    )
                }
                if (isStreaming) {
                    // 停止和发送是两件事：一个是打断，一个是按繁忙策略投递，所以并排放两个键
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
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
                    // 40dp 是视觉尺寸：屏幕只有 360dp，44 的圆钮在整屏里偏重
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        // 不可发时整块压到 40%：DSH 的发送键禁用态就是 opacity .4
                        .background(if (canSend) accentFill else accentFill.copy(alpha = 0.40f))
                        // 短按投递草稿；长按把排队消息整体插话发送（DSH 的互补手势）
                        .combinedClickable(
                            enabled = canSend,
                            onClick = onSend,
                            onLongClick = onSteerAll
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // 忙时的发送键保留自己的身份：排队 / 插话，不跟停止键混用同一个图标
                    when {
                        isStreaming && busyMode == BusySendMode.QUEUE -> QueueGlyph(onAccent, 18.dp)
                        isStreaming && busyMode == BusySendMode.STEER -> SteerGlyph(onAccent, 18.dp)
                        else -> ArrowUpGlyph(onAccent, 18.dp)
                    }
                }
            }
        }
        // 状态行：两枚胶囊，各自点开一张浮窗（跟「＋」「权限」「模型」「上下文」同一套浮层机制）。
        // 只是把只读的文字换成可点的胶囊，不新增任何高度，消息区一动不动。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            // 两枚胶囊直接贴着左右边缘排，中间不留「能抢权重」的空节点。
            // 此前中间的 Spacer 带 weight(1f)，三等分导致每枚胶囊仅获得 1/3 屏宽，
            // 右侧的百分比正好被压成省略号：用户看到的「数字跑出界面外」就是这么来的。
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatusChip(
                // 权重只用来在真不够宽时收缩：正常宽度下 fill=false 会让它按内容自适应
                modifier = Modifier.weight(1f, fill = false),
                glyph = { tint -> StepsGlyph(tint = tint, iconSize = 11.dp) },
                text = statusLeftText(status),
                onClick = {
                    panel = if (panel == ComposerPanelKind.STATS) {
                        ComposerPanelKind.NONE
                    } else {
                        ComposerPanelKind.STATS
                    }
                }
            )
            StatusChip(
                modifier = Modifier.weight(1f, fill = false),
                glyph = { tint -> CylinderGlyph(tint = tint, iconSize = 11.dp) },
                text = statusRightText(status),
                onClick = {
                    panel = if (panel == ComposerPanelKind.TOKEN) {
                        ComposerPanelKind.NONE
                    } else {
                        ComposerPanelKind.TOKEN
                    }
                }
            )
        }
    }
}

/**
 * 上下文圆环用的百分比：与 [ComposerContextPanel] 里的算法逐字相同。
 *
 * 两处各写一遍迟早会分叉：一个显示 12%、另一个 11%，用户只会以为某一个坏了。
 */
internal fun contextPercent(usage: ContextUsageInfo): Int {
    val window = if (usage.window > 0) usage.window else 1
    if (usage.used <= 0) return 0
    return ((usage.used * 100 + window / 2) / window).coerceAtLeast(1)
}

/** 状态行左边那半句：轮数、步数、输出速度；没有速度样本时最后一段不占位置。 */
private fun statusLeftText(status: ComposerStatus): String = buildString {
    append(status.rounds).append(" 轮 ").append(status.steps).append(" 步")
    status.tokensPerSec?.let { append(" · ").append(it).append(" tok/s") }
}

/** 状态行右边那半句：累计 tokens 与缓存命中率。服务商不报缓存就写「—」，不写 0%。 */
private fun statusRightText(status: ComposerStatus): String = buildString {
    // 百分比放最前：这一行最窄的时候一定会截尾巴，被截掉的应该是总数的零头，
    // 而不是用户最关心的命中率（屏幕窄，顺序让位给可读性）
    append("命中 ")
    append(status.cacheHitPercent?.let { percentText(it) } ?: "—")
    append(" · ")
    append(composerTokenText(status.totalTokens)).append(" tok")
}

/** 百分比只留一位小数；整数时不写 .0（「99.6%」「100%」是同一套写法）。 */
internal fun percentText(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    return if (tenths % 10 == 0) {
        (tenths / 10).toString() + "%"
    } else {
        (tenths / 10).toString() + "." + (tenths % 10) + "%"
    }
}

/**
 * 状态行上的一枚胶囊：小图标 + 一行浅灰字，整块可点、带涟漪。
 *
 * 内边距刻意小（左右 6dp、上下 2dp）：它占的是输入条下面那一行，点不到 36dp 也不该把行撑高。
 */
@Composable
private fun StatusChip(
    glyph: @Composable (Color) -> Unit,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            // 左右 4dp：这一行本来就窄，内边距每省 2dp 就多一个字的位置
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        glyph(tint)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            // 11sp：这一行要装两段数字，12sp 在窄屏上必然截尾；11sp 让两段都完整
            fontSize = 11.sp,
            color = tint,
            maxLines = 1,
            // 宁可看到省略号，也不要被裁掉半截数字：用户要看到的是「命中 xx%」
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 轮/步那一枚的小图标：一段开口圆环 + 箭头，读作「循环推进」。18×18 单位坐标。 */
@Composable
internal fun StepsGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawArc(
            color = tint,
            startAngle = -50f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(2.6f, 2.6f),
            size = Size(12.8f, 12.8f),
            style = Stroke(width = 1.6f, cap = StrokeCap.Round)
        )
        // 箭头置于弧的收口处：两笔短线即可，三角形在该尺寸下会模糊
        drawPath(
            path = unitPath {
                moveTo(8.2f, 1.6f)
                lineTo(11.6f, 3.2f)
                lineTo(8.2f, 5.0f)
            },
            color = tint,
            style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
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
    // 六张面板必须是同一套观感与同一套动画：
    // 该卡片此前为独立 Surface、圆角 20dp、且**完全不投影**。
    // 「投影按矩形包围盒落影，圆角外会露直角」为 elevation 阴影跟随形状之前的判断，已不成立。
    // 现在与 PanelCard 对齐：圆角 24dp、同底色、elevation 从 0 长到 14dp（与面板升起同步）。
    val shape = RoundedCornerShape(24.dp)
    var risen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { risen = true }
    val panelShadow by animateDpAsState(
        targetValue = if (risen) 14.dp else 0.dp,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "modelPanelShadow"
    )
    Surface(
        shape = shape,
        // 与其它伴随面板同一底色：比页面与气泡亮一档
        color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Palette.DarkSurfaceHigh
        } else {
            Palette.LightSurfaceHigh
        },
        shadowElevation = panelShadow,
        // 卡片自身消费点击，否则点击空白处会落到输入框并弹出键盘
        modifier = Modifier
            .fillMaxWidth()
            .blockTapsOnly()
    ) {
                Column(
                    modifier = Modifier
                        // 换页时高度随之变化，直接切换会产生明显跳动：改为单调缓动收尾
                        .animateContentSize(tween(durationMillis = 180, easing = FastOutSlowInEasing))
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
            // 最小高度固定，字号放大时随内容增高，避免大字体下文字被裁
            .heightIn(min = 44.dp)
            .saltCardRow { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 15.sp, color = onSurface)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            // 值尚未取得时显示「—」，留白会使该行看似故障
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
 * 这一版回到「上报同宽 × 0 高、内容往负方向画出去」：面板是最上层窗口，
 * 消息区不应随之上移。若上报真实高度，理由是「0 高节点收不到触摸」，
 * 该判断**是错误的**（点击坐标按缩放后的预览尺寸计算，相差一倍）；面板行原本即可点击。
 * 结论：0 高度仅影响「本节点自身」的命中，溢出的子节点仍正常接收事件，不可据此改为占高。
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
                .padding(horizontal = 12.dp),
            // 这里**不能**再包 animateContentSize。
            // 它会把自己的内容裁到「动画中的矩形」里，代价有两条，都被用户当场看见：
            // ① 卡片 14dp 的柔和阴影被裁在矩形内 → 24dp 圆角之外、矩形之内的四角漏出一块方形黑影；
            // ② 高度动画期间内容被逐帧裁开 → 换面板时「与旧面板等高的下半截先出来，
            //    高出的上半截再向上伸长」，读起来就是两段式。
            // 高度由内层 AnimatedContent 直接取目标面板的尺寸，不做过渡；本层只负责停靠，
            // 内容贴底对齐，所以容器高度怎么变都不会带动内容位移。
            contentAlignment = Alignment.BottomCenter
        ) {
            content()
        }
    }
}

/**
 * 一条排队消息：左边空心圆、中间单行文本、右边三个 36dp 热区（编辑 / 删除 / 插话发送）。
 * 正在编辑的那条描一圈 accent，因为用户改完回头看，需要知道改的是哪一条。
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
    val tint = if (editing) UiColors.accentFg else onSurface.copy(alpha = 0.4f)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(onSurface.copy(alpha = 0.06f))
            .then(if (editing) Modifier.border(1.dp, UiColors.accentFg, shape) else Modifier)
            .heightIn(min = 40.dp)
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

/** 权限芯片：「⚠ 仅可查看」，点击后弹出三档选择器。 */
@Composable
private fun PermissionChip(label: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .heightIn(min = 28.dp)
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

/** 目标：同心圆靶心，与面板中「目标」一行同形，缩至 16dp 亦不会模糊。 */
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

/** 插话：闪电图形，与「排队」区分。 */
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

/** 删除图标：用于删除排队消息。 */
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
 * 环内有意不书写文字：缩略态需要「剩余多少」的直观观感，在 22dp 内书写百分比反而难以辨认。
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
