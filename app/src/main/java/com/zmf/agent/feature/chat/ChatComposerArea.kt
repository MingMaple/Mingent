package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.store.GoalPhase
import com.zmf.agent.core.store.TodoStore
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.feature.chat.ChatComposer
import com.zmf.agent.feature.chat.ComposerCommand
import com.zmf.agent.feature.chat.ComposerStatus
import com.zmf.agent.feature.chat.ContextUsageInfo
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import com.zmf.agent.uikit.gesture.blockHorizontalAtEdge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 输入区之前的一组「条」：进行中提示、错误条、待发附件、清单进度，最后为输入框本身。
 *
 * 均在 Column 中按顺序堆叠，均不使用 `weight`，因此为普通组合而非作用域扩展。
 */
@Composable
internal fun ChatComposerArea(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    imageRoot: File,
    statusRounds: Int,
    statusSteps: Int,
    /** 「选文件」入口在 [ChatScreen] 手里（要跟输入法开合联动），这里只发一个请求。 */
    onPickFile: () -> Unit,
    /** 下载日志的落点由调用方选（SAF）。 */
    onLogLaunch: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var viewer by ui.viewer
    var wasAtBottom by ui.wasAtBottom
    var todoBarHidden by ui.todoBarHidden
    var todoDialogVisible by ui.todoDialogVisible
    var inputBarHeightPx by ui.inputBarHeightPx
    val composerPanel = ui.composerPanel
    val openContextHub: (Boolean) -> Unit = ui::openContextHub

                    viewModel.activityText?.let { activity ->
                        Text(
                            text = activity,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            // 提示条属于「状态文字」，非可点击强调项：与图标、次要文字使用同一中性灰
                            color = Palette.Neutral
                        )
                    }

                    viewModel.lastError?.let { error ->
                        Text(
                            text = error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp)
                                .clickable { viewModel.clearError() },
                            fontSize = 12.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // 待发送附件：文件与图片合并为**同一行**，按添加顺序从左至右排列，超出屏幕时左右滑动。
                    // 此前为上下两行（文件一行、图片一行），图片行将文件行顶起并相互遮挡。
                    // 两条队列在 ViewModel 中分开存储（图片需单独压缩 JPEG），无法获得统一入队顺序，
                    // 故按各自落盘时间戳还原实际先后（见 mergePending）。
                    val pendingFiles = viewModel.attachments.toList()
                    val pendingImagesNow = viewModel.pendingImages.toList()
                    val pendingEntries = remember(imageRoot, pendingFiles, pendingImagesNow) {
                        mergePending(imageRoot, pendingFiles, pendingImagesNow)
                    }
                    if (pendingEntries.isNotEmpty()) {
                        // 同一 state 提供给拦截器与横向行：判断「是否到达边界」与实际滑动必须基于同一份状态
                        val pendingScroll = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // blockHorizontalAtEdge 必须先挂载（位于外层），否则边界余量无法接收
                                .blockHorizontalAtEdge(pendingScroll)
                                .horizontalScroll(pendingScroll)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pendingEntries.forEach { entry ->
                                // relPath 在本次入队中唯一：列表中途插入删除时，缩略图的滑动位置与
                                // 解码缓存不会被相邻项覆盖
                                key(entry.pending.relPath) {
                                    if (entry.isImage) {
                                        PendingImageThumb(
                                            relPath = entry.pending.relPath,
                                            root = imageRoot,
                                            onClick = { viewer = ViewerTarget(listOf(entry.pending.relPath), 0) },
                                            onRemove = { viewModel.detachImage(entry.index) }
                                        )
                                    } else {
                                        PendingFileChip(
                                            name = entry.pending.name,
                                            onRemove = { viewModel.detachFile(entry.index) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 清单条：模型通过 todo_write 写入清单后显示一行进度，点击可查看全表（只读）
                    // 收起后清单内容一旦变化（新一轮再次写入）则自动展开：否则收起一次后将无法再看到进度
                    val todoSignature = viewModel.todos.joinToString("\n") { it.status + " " + it.content }
                    LaunchedEffect(todoSignature) { todoBarHidden = false }
                    if (viewModel.todos.isNotEmpty() && !todoBarHidden) {
                        val doneCount = viewModel.todos.count { it.status == TodoStore.COMPLETED }
                        val currentTodo = viewModel.todos.firstOrNull { it.status == TodoStore.IN_PROGRESS }
                            ?: viewModel.todos.lastOrNull { it.status == TodoStore.PENDING }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { todoDialogVisible = true }
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = doneCount.toString() + "/" + viewModel.todos.size + " 完成 · 当前: " +
                                    (currentTodo?.content ?: "全部完成"),
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (doneCount == viewModel.todos.size) {
                                    UiColors.success
                                } else {
                                    UiColors.warning
                                }
                            )
                            Text(
                                text = "›",
                                modifier = Modifier.padding(start = 6.dp),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                            // 内层可点击优先消费本次点击，整行的「打开全表」不会被误触发
                            Text(
                                text = "×",
                                modifier = Modifier
                                    .clickable { todoBarHidden = true }
                                    .padding(start = 12.dp, end = 2.dp),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { inputBarHeightPx = it.size.height }
                    ) {
                    ChatComposer(
                        value = viewModel.inputText,
                        onValueChange = { viewModel.updateInput(it) },
                        isStreaming = viewModel.isStreaming,
                        // 仅挂载图片/文件、未输入文字也视为可发送：发送键此前仅按文本判断可发送
                        hasAttachment = viewModel.attachments.isNotEmpty() ||
                            viewModel.pendingImages.isNotEmpty(),
                        planMode = viewModel.planMode,
                        // 目标激活时输入框右侧才显示图标；流程结束（complete/pause）后 ViewModel 会将 goal 置为非 ACTIVE，图标自动消失
                        activeGoal = viewModel.goal?.phase == GoalPhase.ACTIVE,
                        skills = viewModel.skillNames,
                        // 技能不走命令枚举：面板将技能名传入，此处仅补充为 /名字 交由用户继续填写
                        onSkillPick = { name -> viewModel.updateInput(TextFieldValue("/" + name)) },
                        busyMode = viewModel.busyMode,
                        permissionLabel = viewModel.permissionLevel.displayName(),
                        permissionLevel = viewModel.permissionLevel,
                        queue = viewModel.pendingQueue.toList(),
                        // 「编辑」= 从队列取出并回填输入框：不保留在队列中，避免同一内容被发送两次
                        onEditQueued = { queued ->
                            viewModel.updateInput(TextFieldValue(queued.text, TextRange(queued.text.length)))
                            viewModel.deleteQueued(queued.id)
                        },
                        onDeleteQueued = { queued -> viewModel.deleteQueued(queued.id) },
                        onSendQueued = { queued -> viewModel.sendQueuedNow(queued.id, scope) },
                        // 长按发送键：把队列整体插话发送（DSH 的 Ctrl/Cmd+Enter）
                        onSteerAll = { viewModel.steerAllQueued(scope) },
                        onPlus = {},
                        // 本项目没有 @ 会话/文件 的补全列表，「@」暂时等同「选择文件」
                        onMention = { onPickFile() },
                        onSend = {
                            // 用户主动发送 = 需回到对话底部；否则在向上翻阅时发送消息后仍停留在原位置
                            wasAtBottom = true
                            val text = viewModel.inputText.text
                            if (text.isNotBlank() || viewModel.attachments.isNotEmpty() ||
                                viewModel.pendingImages.isNotEmpty()
                            ) {
                                // 空闲 = 直接发送；流式中按「繁忙时发送行为」排队或插话（与停止不同）
                                viewModel.sendWhileBusy(text, scope)
                                viewModel.updateInput(TextFieldValue(""))
                            } else if (viewModel.pendingQueue.isNotEmpty()) {
                                // 草稿为空而队列非空：发送键此时就是「发送排队消息」，
                                // 与输入框的占位提示一致（此前这一下什么都不做，点了像没反应）
                                viewModel.sendQueuedNow(viewModel.pendingQueue.first().id, scope)
                            }
                        },
                        onStop = { viewModel.stop() },
                        // 面板入口交由 Composer：仅用于让父层铺设遮罩，不承担其他职责
                        panelHost = composerPanel,
                        onPermissionPick = { level -> viewModel.setPermission(level) },
                        onComposerCommand = { command ->
                            when (command) {
                                ComposerCommand.ADD_FILE -> onPickFile()
                                // 目标已无对应页面：向输入框补充 /goal 模板，由用户继续填写内容后发送，
                                // 实际解析交由数据层的斜杠命令（与面板「命令」组插入的模板同一来源，带尾随空格）
                                ComposerCommand.ADD_GOAL -> viewModel.updateInput(
                                    TextFieldValue("/goal ", TextRange("/goal ".length))
                                )
                                ComposerCommand.ADD_PLAN -> viewModel.togglePlanMode()
                                ComposerCommand.COMPACT -> viewModel.summarizeNow()
                                ComposerCommand.DOWNLOAD_LOGS -> {
                                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                    onLogLaunch("mingent-logs-" + stamp + ".zip")
                                }
                                // 上下文仅保留「洞察」一个入口：从加号面板进入的同样为主页，返回时回主页
                                ComposerCommand.CONTEXT -> openContextHub(true)
                            }
                        },
                        status = ComposerStatus(
                            // 轮数 / 步数仅采用 ViewModel 中的实时状态：stats 中的对应字段需进入统计页才刷新，
                            // 用户会看到「发送下一句后数字才变化」
                            rounds = statusRounds,
                            steps = statusSteps,
                            // 速度、累计 token、缓存命中均来自本会话的 timing 快照（会落盘，重启不丢失）；
                            // 水位不在此行显示：「上下文」按钮的浮窗中已包含百分比
                            tokensPerSec = viewModel.sessionTiming.tokensPerSec,
                            totalTokens = viewModel.sessionTiming.promptTokens +
                                viewModel.sessionTiming.completionTokens,
                            cacheHitPercent = viewModel.sessionTiming.cacheHitPercent
                        ),
                        stats = ComposerStatsInfo(
                            modelMillis = viewModel.sessionTiming.modelMillis,
                            toolMillis = viewModel.sessionTiming.toolMillis,
                            ttftAverageMillis = viewModel.sessionTiming.ttftAverageMillis,
                            tokensPerSec = viewModel.sessionTiming.tokensPerSec,
                            cacheHitTokens = viewModel.sessionTiming.cacheHitTokens,
                            cacheMissTokens = viewModel.sessionTiming.cacheMissTokens,
                            cacheHitPercent = viewModel.sessionTiming.cacheHitPercent,
                            outputTokens = viewModel.sessionTiming.completionTokens
                        ),
                        usage = ContextUsageInfo(
                            used = viewModel.contextSnapshot.estimatedTokens,
                            window = viewModel.contextSnapshot.window,
                            systemTokens = viewModel.contextBreakdown.systemTokens,
                            toolTokens = viewModel.contextBreakdown.toolTokens,
                            messageTokens = viewModel.contextBreakdown.messageTokens
                        ),
                        onOpenContextPage = { openContextHub(true) },
                        // 当前模型显示名：除内置 DeepSeek 的两个别名外（自定义 provider）显示 id 本身
                        modelLabel = modelDisplayName(viewModel.model),
                        // 强度一律显示中文：一级窗向用户展示「高／最高」，接口取值仅在数据层出现
                        effortLabel = if (viewModel.thinkingEnabled) effortDisplayName(viewModel.thinkingEffort) else "Off",
                        // 二级窗就地展开：选择后即生效并收起，无需离开对话进入设置页
                        quick = QuickSheetState(
                            providers = viewModel.providers.map { QuickProvider(it.id, it.name, it.models) },
                            currentProviderId = viewModel.currentProviderId,
                            currentModelId = viewModel.model,
                            effort = viewModel.thinkingEffort,
                            thinkingEnabled = viewModel.thinkingEnabled,
                            onPickModel = { providerId, modelId ->
                                // 先切换服务商再切换模型：切换服务商会将模型重置为其上次使用项，
                                // 顺序相反时该步骤会被覆盖，用户点击后看似未生效
                                if (providerId.isNotBlank() && providerId != viewModel.currentProviderId) {
                                    viewModel.selectProvider(providerId)
                                }
                                viewModel.applyModel(modelId)
                            },
                            onPickEffort = { key ->
                                if (key == EFFORT_OFF) {
                                    if (viewModel.thinkingEnabled) viewModel.toggleThinking()
                                } else {
                                    // 选择具体档位本身即意味着「启用思考」：自「关」直接点「最高」应为启用 + 最高，
                                    // 而非保存档位但仍处于关闭
                                    if (!viewModel.thinkingEnabled) viewModel.toggleThinking()
                                    viewModel.chooseThinkingEffort(key)
                                }
                            }
                        )
                    )
                    }
}
