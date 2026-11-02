package com.zmf.agent.feature.chat

import android.app.Activity
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * 输入区之前那一摞「条」：进行中提示、错误条、待发附件、清单进度，最后是输入框本身。
 *
 * 都在 Column 里按顺序堆叠，誰也不吃 `weight`，所以是普通组合而不是作用域扩展。
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
                            // 提示条属于「状态文字」，不是可点强调项：跟图标/次要文字同一支中性灰
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

                    // 待发送附件：文件和图片合成**同一行**，按添加顺序从左往右排，超出屏幕左右滑。
                    // 之前是上下两行（文件一行、图片一行），图片那层把文件顶上去还互相压住。
                    // 两条队列在 ViewModel 里分开存（图片要单独压 JPEG），拿不到统一的入队顺序，
                    // 所以按各自落盘的时间戳还原真实先后（见 mergePending）。
                    val pendingFiles = viewModel.attachments.toList()
                    val pendingImagesNow = viewModel.pendingImages.toList()
                    val pendingEntries = remember(imageRoot, pendingFiles, pendingImagesNow) {
                        mergePending(imageRoot, pendingFiles, pendingImagesNow)
                    }
                    if (pendingEntries.isNotEmpty()) {
                        // 同一个 state 喂给拦截器和横向行：判断「到没到边」和真正滑动必须是同一份
                        val pendingScroll = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // blockHorizontalAtEdge 必须先挂（更外层），否则边界余量收不到
                                .blockHorizontalAtEdge(pendingScroll)
                                .horizontalScroll(pendingScroll)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pendingEntries.forEach { entry ->
                                // relPath 在这次入队里唯一：列表中途插删时，缩略图的滑动位置和
                                // 解码缓存不会被旁边的项顶掉
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

                    // 清单条：模型用 todo_write 写过清单之后显示一行进度，点开看全表（只读）
                    // 收起后清单内容一变（新一轮又写了表）就自动展开：否则收起一次就再也看不到进度
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
                            // 内层可点会先吃掉这一次点击，整行的「打开全表」不会被误触发
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
                        // 只挂了图片/文件、一个字没写也算能发：发送键原来只按文本判可发
                        hasAttachment = viewModel.attachments.isNotEmpty() ||
                            viewModel.pendingImages.isNotEmpty(),
                        planMode = viewModel.planMode,
                        // 目标激活时输入框右侧才长图标；流程结束（complete/pause）后 ViewModel 会把 goal 置成非 ACTIVE，图标自动消失
                        activeGoal = viewModel.goal?.phase == GoalPhase.ACTIVE,
                        skills = viewModel.skillNames,
                        // 技能不走命令枚举：面板把技能名递进来，这里只负责补成 /名字 交给用户继续写
                        onSkillPick = { name -> viewModel.updateInput(TextFieldValue("/" + name)) },
                        busyMode = viewModel.busyMode,
                        permissionLabel = viewModel.permissionLevel.displayName(),
                        permissionLevel = viewModel.permissionLevel,
                        queue = viewModel.pendingQueue.toList(),
                        // 「编辑」= 从队列取出回填输入框：不留在队列里，避免同一句话被发两次
                        onEditQueued = { queued ->
                            viewModel.updateInput(TextFieldValue(queued.text, TextRange(queued.text.length)))
                            viewModel.deleteQueued(queued.id)
                        },
                        onDeleteQueued = { queued -> viewModel.deleteQueued(queued.id) },
                        onSendQueued = { queued -> viewModel.sendQueuedNow(queued.id, scope) },
                        onPlus = {},
                        // 我们没有 @ 会话/文件 的补全列表，「@」先等同「选文件」
                        onMention = { onPickFile() },
                        onSend = {
                            // 用户主动发送 = 要回到对话底部；否则翻在上面发消息后会停在原地
                            wasAtBottom = true
                            val text = viewModel.inputText.text
                            if (text.isNotBlank() || viewModel.attachments.isNotEmpty() ||
                                viewModel.pendingImages.isNotEmpty()
                            ) {
                                // 空闲 = 直接发；流式中按「繁忙时发送行为」排队或插话（不再等同停止）
                                viewModel.sendWhileBusy(text, scope)
                                viewModel.updateInput(TextFieldValue(""))
                            }
                        },
                        onStop = { viewModel.stop() },
                        // 面板把手交给 Composer：它只用来让父层铺遮罩、不做别的事
                        panelHost = composerPanel,
                        onPermissionPick = { level -> viewModel.setPermission(level) },
                        onComposerCommand = { command ->
                            when (command) {
                                ComposerCommand.ADD_FILE -> onPickFile()
                                // 目标没有页面了：往输入框里补一条 /goal 模板，由用户接着写内容再发，
                                // 真正解析交给数据层的斜杠命令（和面板里「命令」组插的模板同一个口径，带尾空格）
                                ComposerCommand.ADD_GOAL -> viewModel.updateInput(
                                    TextFieldValue("/goal ", TextRange("/goal ".length))
                                )
                                ComposerCommand.ADD_PLAN -> viewModel.togglePlanMode()
                                ComposerCommand.COMPACT -> viewModel.summarizeNow()
                                ComposerCommand.DOWNLOAD_LOGS -> {
                                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                    onLogLaunch("mingent-logs-" + stamp + ".zip")
                                }
                                // 上下文只有「洞察」一个入口了：加号面板进来的也是主页，返回回主页
                                ComposerCommand.CONTEXT -> openContextHub(true)
                            }
                        },
                        status = ComposerStatus(
                            // 轮数 / 步数只认 ViewModel 里那份实时状态：stats 里那两个要等进统计页才刷，
                            // 用户看到的就是「发完下一句数字才动」
                            rounds = statusRounds,
                            steps = statusSteps,
                            // 速度、累计 token、缓存命中全部来自本会话的 timing 快照（会落盘，重启不丢）；
                            // 水位不在这行了：「上下文」按钮的浮窗里本来就有百分比
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
                        // 当前模型显示名：内置 DeepSeek 的两个别名之外（自定义 provider）就显示 id 本身
                        modelLabel = modelDisplayName(viewModel.model),
                        // 强度也一律显示中文：一级窗里给用户看的是「高／最高」，接口取值只在数据层出现
                        effortLabel = if (viewModel.thinkingEnabled) effortDisplayName(viewModel.thinkingEffort) else "Off",
                        // 二级窗就地展开：选完即生效并收起，不再离开对话去设置页
                        quick = QuickSheetState(
                            providers = viewModel.providers.map { QuickProvider(it.id, it.name, it.models) },
                            currentProviderId = viewModel.currentProviderId,
                            currentModelId = viewModel.model,
                            effort = viewModel.thinkingEffort,
                            thinkingEnabled = viewModel.thinkingEnabled,
                            onPickModel = { providerId, modelId ->
                                // 先切服务商再切模型：切服务商会把模型重置成它上次用的那个，
                                // 顺序反过来就会被这一步覆盖掉，用户点了像没生效
                                if (providerId.isNotBlank() && providerId != viewModel.currentProviderId) {
                                    viewModel.selectProvider(providerId)
                                }
                                viewModel.applyModel(modelId)
                            },
                            onPickEffort = { key ->
                                if (key == EFFORT_OFF) {
                                    if (viewModel.thinkingEnabled) viewModel.toggleThinking()
                                } else {
                                    // 选具体档位本身就意味着「要思考」：从「关」直接点「最高」应该是开+最高，
                                    // 而不是把档位存进去却仍然关着
                                    if (!viewModel.thinkingEnabled) viewModel.toggleThinking()
                                    viewModel.chooseThinkingEffort(key)
                                }
                            }
                        )
                    )
                    }
}
