package com.zmf.agent.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 列表最多展示这么多行（与 FileLog.readLines 的默认上限一致，读盘时就已经截好了）。 */
private const val MAX_LINES = 2000

/**
 * 顶部分类 chip：只按方括号里的 tag 做包含匹配（日志行形如「08-14 12:00:00.123 [session] …」）。
 *
 * tag 由 FileLog.log(tag, ...) 的第一参数决定，想被某个 chip 筛出来的模块就把 tag 写成这个名字。
 * 现状：session / mcp 直接对得上；chat、api、net、loop 这些只能靠「全部」+ 搜索框看，
 * tool / ui / prompt / crash 要等对应模块改用这些 tag 才会有内容。
 */
private val LOG_FILTERS = listOf("全部", "tool", "ui", "session", "mcp", "prompt", "crash")

/**
 * 「日志」页：真机没有 logcat 时唯一能自查的窗口。
 *
 * 数据源就是 agent.log + agent.log.1（FileLog.readLines），进来读一次：
 * 日志在 App 跑着的过程中一直在写，做成实时订阅要用 FileObserver 之类的额外机制，
 * 而查问题通常是「出事了再进来看一眼」，读一次 + 手动返回重进足够。
 *
 * 展示是倒序（最新的在第一屏），复制则按时间正序拼——粘到文件里从头读到尾更顺。
 */
@Composable
fun LogsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("全部") }
    var query by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf<List<String>>(emptyList()) }

    // 最多两文件、1MB，不能放主线程读；context 只用来定位目录
    LaunchedEffect(Unit) {
        // 日志改成异步写盘了：读之前先刷一次队列，否则刚发生的几行在这一页里永远看不到
        FileLog.flush()
        raw = withContext(Dispatchers.IO) { FileLog.readLines(context, MAX_LINES) }
    }

    // 筛选是纯内存过滤（≤2000 行），输入每个字重算一次没有性能问题
    val shown = remember(raw, filter, query) {
        val keyword = query.trim()
        raw.filter { line ->
            matchesFilter(line, filter) &&
                (keyword.isEmpty() || line.contains(keyword, ignoreCase = true))
        }
    }

    Surface(
        // 同其它二级页：铺满不透明、吞点击，避免穿透到下面的聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
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

            // 7 个 chip 一行放不下窄屏，横向滚动比换行更稳（换行会把搜索框挤下去）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
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
                // 不用 key：日志行会整体刷新（清空 / 重读），给了 key 反而可能撞重复行
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
                // 「复制全部」复制的是当前筛选结果，否则筛完再全拷回去对不上看到的
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
 * 只认方括号里的 tag，不看正文：正文里恰好出现 "tool"、"session" 这类英文词的概率不低，
 * 按整行匹配会把不相干的行一起捞进来。
 */
private fun matchesFilter(line: String, filter: String): Boolean {
    if (filter == "全部") return true
    val tag = line.substringAfter('[', "").substringBefore(']', "")
    return tag.contains(filter, ignoreCase = true)
}

/**
 * 复制到剪贴板。
 *
 * 按时间正序拼（展示是倒序）：粘进文件里从头读才顺。
 * Android 13 起系统自己弹「已复制」，不再叠加 Toast（同 ChatScreen 的处理）。
 */
private fun copyAll(context: Context, lines: List<String>) {
    if (lines.isEmpty()) {
        logsToast(context, "没有可复制的日志")
        return
    }
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("日志", lines.asReversed().joinToString("\n")))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        logsToast(context, "已复制 " + lines.size + " 行")
    }
}

/** 名字带 logs 前缀：同包 PackagePage.kt 里也有一个同签名的私有 toast，重名不报错但读起来歧义。 */
private fun logsToast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** chip：选中吃 Salt 绿半透明底，未选中是 6% 淡影（同 PackagePage 的胶囊）。 */
@Composable
private fun LogChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Palette.AccentGreen.copy(alpha = 0.16f) else onSurface.copy(alpha = 0.06f),
        modifier = Modifier.saltRowClickable(RoundedCornerShape(12.dp), onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Palette.AccentGreen else onSurface.copy(alpha = 0.6f)
        )
    }
}

/** 底部文字按钮：只有文字，没有边框和填充，跟提示词页的「恢复默认」一致。 */
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
