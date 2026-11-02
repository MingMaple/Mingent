package com.zmf.agent.app.navigation

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.infra.backup.BackupOptions
import com.zmf.agent.core.prefs.ThemeMode
import kotlinx.coroutines.launch
import com.zmf.agent.feature.chat.ChatAppEffects
import com.zmf.agent.feature.chat.ChatBackHandlers
import com.zmf.agent.feature.chat.buildChatItems
import com.zmf.agent.feature.chat.pickerInitialDir
import com.zmf.agent.feature.chat.rememberPickerDir
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel


/**
 * 主页：消息流 + 底部输入区，以及抽屉与各二级全屏页的唯一宿主。
 *
 * 页面状态全在内部：会话与模型状态来自自建的 [ChatViewModel]，页面层级由 [AppPage] 表达，
 * 二次确认、查看器、长按小窗等瞬时状态使用局部 remember。父层仅传入主题、字号与启动会话 id。
 *
 * @param themeMode 当前主题模式；由外部持有并持久化
 * @param uiScale 全局界面字号倍率（仅放大 sp，不改变 dp）
 * @param initialSessionId 从通知进入时需切换到的会话；消费一次后自行清空
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    /** 全局界面字号（仅放大 sp，不改变 dp）；由 MainActivity 持有并持久化。 */
    uiScale: Float = 1f,
    onUiScaleChange: (Float) -> Unit = {},
    /** 从通知进入时需要切换到的会话 id；此处消费一次后清空。 */
    initialSessionId: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 所有瞬时界面状态集中于此：子组合通过 `var x by ui.x` 获取同一份状态
    val ui = remember { ChatUiState(context) }
    val viewModel = remember { ChatViewModel(context) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 子任务面板：Registry 为内存 object（子智能体在 IO 协程中写入）。
    // 此处采用订阅版本号的方式：没有子智能体运行时，该 Flow 不会发送任何事件，主线程不会被唤醒；
    // 相比 300ms 轮询，空闲时不会周期性分配快照。
    var subAgentVersion by remember { mutableStateOf(0) }
    val versionTick by SubAgentRegistry.version.collectAsState()
    subAgentVersion = versionTick
    // 仅在版本实际变化时重新获取快照（首帧同样经过该 remember，相当于打开页面时先获取一次）
    val subAgentEntries = remember(subAgentVersion) { SubAgentRegistry.snapshot() }

    // 两条选择文件入口：OpenDocument 为系统文稿页，GetContent 进入设备自带的文件管理器
    // （部分机型的文稿页侧边栏没有「文件管理」入口，因此保留第二个入口作为兜底）。
    // 文稿页路径不使用契约类：ActivityResultContracts.OpenDocument 会自行构造 Intent，
    // 无法传入 EXTRA_INITIAL_URI，而「上次停留的目录」只能通过自行发送 Intent 指定。
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            // 这是系统唯一允许获取持久读权限的时机：之后重试导入或工作区文件被清除后再次读取，仍可读取
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            rememberPickerDir(context, uri, viewModel.currentSessionId)
            viewModel.attachFile(uri, scope)
        }
    }

    // 导出：由用户在系统中选择保存位置，不申请任何存储权限
    var includeTerminal by ui.includeTerminal
    var includeLogs by ui.includeLogs
    var includeMemory by ui.includeMemory
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportData(
                uri = uri,
                options = BackupOptions(
                    includeTerminal = includeTerminal,
                    includeLogs = includeLogs,
                    includeMemory = includeMemory
                ),
                scope = scope
            )
        }
    }

    // 下载日志：同样使用 SAF，由用户选择位置；文件名包含时间戳，多次下载不会相互覆盖
    val logLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.downloadLogs(uri)
    }

    // 导入：先只读清单，在确认框中确认后再执行覆盖
    var importUri by ui.importUri
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri
            viewModel.loadBackupManifest(uri, scope)
        }
    }

    val lastMessage = viewModel.messages.lastOrNull()
    // 一个回合指一次提问到下一条提问之间的区间，含中间的工具结果消息。
    // 该结构仅在会话切换、消息条数或末条内容变化时重建；流式输出逐 token 触发时若重扫全量消息，
    // 会造成每次 O(n) 的扫描与一次列表分配。以末条消息对象作为 key 可精确判定变化，
    // 未变化时仅进行一次引用比较。
    val items = remember(viewModel.currentSessionId, viewModel.messages.size, lastMessage) {
        buildChatItems(viewModel.messages)
    }

    // 状态行的轮数与步数在 ViewModel 中为计算属性：轮数需遍历全部消息，步数需遍历会话表。
    // 组合期间每收到一个 token 均会读取一次，因此按同一组特征缓存上一次结果；
    // 步数还需随本轮 stats.steps 变化，同一轮内步数持续增长，缺少该项会保留旧值。
    val statusRounds = remember(viewModel.currentSessionId, viewModel.messages.size, lastMessage) {
        viewModel.statusRounds
    }
    val statusSteps = remember(
        viewModel.currentSessionId,
        viewModel.messages.size,
        lastMessage,
        viewModel.stats.steps
    ) {
        viewModel.statusSteps
    }

    // 图片路径均相对于工作区：气泡图片网格与输入框缩略图均从该根目录解码，
    // 规则与发送时一致，未分类会话使用默认工作区。按工作区与选中状态缓存，避免逐帧读取目录。
    val imageRoot = remember(viewModel.workspaceList, viewModel.currentWorkspaceId) {
        val space = viewModel.workspaceList.firstOrNull { it.id == viewModel.currentWorkspaceId }
            ?: WorkSpaceStore.defaultSpace(context)
        // 与 ChatViewModel.rootOf 规则一致：优先使用用户选择的自定义目录
        WorkSpaceStore.resolveRoot(context, space) ?: WorkSpaceStore.dirOf(context, space)
    }

    val density = LocalDensity.current
    // 输入法占用的高度：整屏唯一的 IME 占位由此产生，其他位置不得使用固定 IME 高度。
    // 窗口采用 adjustNothing 与 edgeToEdge，系统不会缩小窗口，也不会消耗 ime inset，
    // 因此此处读取的是键盘实际遮挡的高度。
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    // Android 11 起系统按动画逐帧上报 ime inset：直接跟随该值，输入框上移与键盘上滑属于同一段运动，
    // 不滞后也不提前。更早的系统仅在结束时提供一次终值，相当于瞬移，因此改用 tween 过渡；
    // 两条路径互斥，同时启用会先由 tween 驱动，随后被真实值修正。
    val imePad = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        imeBottomDp
    } else {
        animateDpAsState(
            targetValue = imeBottomDp,
            animationSpec = tween(durationMillis = 240),
            label = "imeBottomPadding"
        ).value
    }
    val imeVisible = WindowInsets.isImeVisible

    /**
     * 选择文件与图片使用同一入口：返回页面时输入法状态需与进入前一致。
     * 进入前已收起时仅依赖系统无法保持：输入框始终持有焦点，回到前台后系统会重新弹出键盘，
     * 因此先清除焦点并收起键盘；进入前已展开时不做处理，返回后仍附着于输入框。
     */
    fun launchPicker(pick: () -> Unit) {
        if (!imeVisible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        pick()
    }

    /**
     * 打开系统文稿页。
     *
     * 初始目录仅在「同一会话且距上次不超过 30 分钟」时指定；其余情况不传 EXTRA_INITIAL_URI（回到顶层），
     * 否则上次（甚至十天前、其他会话中）选择过的目录会持续将用户限制在深层目录。
     */
    fun openFilePicker() {
        launchPicker {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            pickerInitialDir(context, viewModel.currentSessionId)?.let { dir ->
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, dir)
            }
            filePicker.launch(intent)
        }
    }

    // 全部副作用：通知进入会话、前后台持久化、小窗桥接、抽屉收起键盘、计划跳转与链接路由
    ChatAppEffects(
        viewModel = viewModel,
        ui = ui,
        context = context,
        lifecycleOwner = lifecycleOwner,
        focusManager = focusManager,
        keyboard = keyboard,
        initialSessionId = initialSessionId,
        subAgentEntries = subAgentEntries
    )

    // 返回键：注册顺序即优先级
    ChatBackHandlers(viewModel = viewModel, ui = ui, context = context)

    // 骨架：抽屉、轨迹页、顶栏、消息流、输入区、页面层与浮层
    ChatShell(
        viewModel = viewModel,
        ui = ui,
        context = context,
        scope = scope,
        listState = listState,
        items = items,
        imageRoot = imageRoot,
        statusRounds = statusRounds,
        statusSteps = statusSteps,
        subAgentEntries = subAgentEntries,
        imePad = imePad,
        imeVisible = imeVisible,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        uiScale = uiScale,
        onUiScaleChange = onUiScaleChange,
        onPickFile = { openFilePicker() },
        onLogLaunch = { name -> logLauncher.launch(name) },
        onImportLaunch = {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
    )

    // 六个模态框：新建工作区 / 导出选项 / 导入确认 / 导入结果 / 清单全表 / 回滚确认
    ChatDialogs(
        viewModel = viewModel,
        ui = ui,
        context = context,
        scope = scope,
        onExportLaunch = { name -> exportLauncher.launch(name) }
    )
}
