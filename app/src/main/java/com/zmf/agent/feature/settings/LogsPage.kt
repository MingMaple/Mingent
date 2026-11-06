package com.zmf.agent.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.gesture.blockHorizontalAtEdge
import com.zmf.agent.uikit.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zmf.agent.uikit.common.saltFieldColors
import com.zmf.agent.uikit.common.NoticeCenter

/** 列表最多展示的行数，与 FileLog.readLines 的默认上限一致。 */
private const val MAX_LINES = 2000

/**
 * 顶部分类 chip：仅按方括号内的 tag 做包含匹配，日志行形如「08-14 12:00:00.123 [session] …」。
 * tag 取自 FileLog.log(tag, ...) 的第一个参数，模块需使用同名 tag 才能被筛出。
 */
private val LOG_FILTERS = listOf("全部", "tool", "ui", "session", "mcp", "prompt", "crash")

/**
 * 「日志」页：无 logcat 时的自查入口，数据源为 agent.log 与 agent.log.1，进入时读取一次。
 * 日志持续写入，实时订阅需引入 FileObserver 等机制；排查多为事后查看，读取一次已足够。
 * 列表最新在上，复制时按时间正序拼接。
 */
@Composable
fun LogsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("全部") }
    var query by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf<List<String>>(emptyList()) }

    // 读取最多两个文件、1MB，不能在主线程执行；context 仅用于定位目录
    LaunchedEffect(Unit) {
        // 日志异步写盘，读取与 fsync 都放到 IO 线程，避免日志页首帧等待落盘
        raw = withContext(Dispatchers.IO) {
            FileLog.flush()
            FileLog.readLines(context, MAX_LINES)
        }
    }

    // 筛选为纯内存过滤（≤2000 行），逐字符重算没有性能开销
    val shown = remember(raw, filter, query) {
        val keyword = query.trim()
        raw.filter { line ->
            matchesFilter(line, filter) &&
                (keyword.isEmpty() || line.contains(keyword, ignoreCase = true))
        }
    }

    Surface(
        // 同其它二级页：铺满不透明、拦截点击，避免穿透到下层聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .stableStatusBarPadding()
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "日志",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 窄屏放不下一行 chip，横向滚动可避免换行将搜索框挤下移。
            // 到左边界后继续向右推必须就地截住：否则这段位移会交给抽屉，表现为「滑到最左再滑就唤出抽屉」
            val filterScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 拦截器必须先于 horizontalScroll 挂载（位于外层），同一节点上仅外层能收到边界余量
                    .blockHorizontalAtEdge(filterScroll)
                    .horizontalScroll(filterScroll)
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LOG_FILTERS.forEach { name ->
                    LogChip(
                        text = name,
                        selected = filter == name,
                        onClick = { filter = name }
                    )
                }
            }

            OutlinedTextField(
                colors = saltFieldColors(),
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("搜索日志内容…", fontSize = 12.sp) },
                singleLine = true
            )

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (shown.isEmpty()) {
                    item {
                        Text(
                            text = if (raw.isEmpty()) "还没有日志" else "没有匹配的日志",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                }
                // 不设 key：日志整体刷新（清空或重读），设 key 可能因重复行冲突
                items(shown.size) { index ->
                    Text(
                        text = shown[index],
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.dp),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 「复制全部」复制当前筛选结果，与屏幕显示保持一致
                ActionText(text = "复制全部", color = Palette.Neutral) {
                    copyAll(context, shown)
                }
                Spacer(Modifier.width(24.dp))
                ActionText(
                    text = "清空",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                ) {
                    FileLog.clear(context)
                    raw = emptyList()
                    logsToast(context, "已清空")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "共 " + shown.size + " 行",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        }
    }
}

/**
 * 单行日志是否命中所选 chip。
 *
 * 仅匹配方括号内的 tag：正文中可能恰好出现这些英文词，按整行匹配会误匹配无关行。
 */
private fun matchesFilter(line: String, filter: String): Boolean {
    if (filter == "全部") return true
    val tag = line.substringAfter('[', "").substringBefore(']', "")
    return tag.contains(filter, ignoreCase = true)
}

/**
 * 复制到剪贴板。
 *
 * 按时间正序拼接（列表为倒序），便于粘贴后顺序阅读。
 * Android 13 及以上系统自带复制提示，无需叠加 Toast。
 */
private fun copyAll(context: Context, lines: List<String>) {
    if (lines.isEmpty()) {
        logsToast(context, "没有可复制的日志")
        return
    }
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("日志", lines.asReversed().joinToString("\n"))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 日志里可能含文件路径、命令与请求摘要：按敏感内容标记，交由系统决定是否展示预览
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    manager.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        logsToast(context, "已复制 " + lines.size + " 行")
    }
}

/** 加 logs 前缀，避免与 PackagePage 中同签名的 toast 混淆。 */
private fun logsToast(context: Context, text: String) {
    // 统一走应用内提示条：系统 Toast 是独立窗口，不受深浅色主题控制
    NoticeCenter.info(text)
}

@Composable
private fun LogChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) onSurface else onSurface.copy(alpha = 0.06f),
        modifier = Modifier.saltCardRow(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.surface else onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ActionText(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .saltRowClickable(RoundedCornerShape(8.dp), onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        fontSize = 13.sp,
        color = color
    )
}
