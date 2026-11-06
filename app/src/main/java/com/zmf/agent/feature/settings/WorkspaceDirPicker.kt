package com.zmf.agent.feature.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.icons.FolderIcon
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import com.zmf.agent.uikit.common.NoticeCenter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 「自定义目录」展开区的内容：绝对路径输入 → 校验 → 列出子文件夹 → 进入子目录，另有系统目录选择器。
 *
 * 设计约束（以下几条刻意收窄，不额外增加功能）：
 * - **不弹新窗口**：进入子文件夹仅就地刷新列表，并将子目录名追加到输入框路径末尾；
 * - **没有「返回上一级」**：回退由用户修改输入框中的路径完成；
 * - **没有「新建文件夹」、没有「显示隐藏文件」**：不增加多余按钮（隐藏目录不列出）；
 * - 右侧的 📁 是**纯图标按钮**（文字字形，非矢量资源）：打开系统目录选择器，
 *   选择后将目录路径回填到输入框，其余逻辑与上一条相同（视为一个根目录）。
 *
 * 状态由调用方持有（[path] / [treeUri] 受控）：手动输入或进入子目录时回调 [onPathEdited]，
 * 系统选择器选择后回调 [onTreePicked]。宿主据此决定「路径变更后清除 treeUri」等规则。
 */
@Composable
fun WorkspaceDirPicker(
    path: String,
    treeUri: String?,
    onPathEdited: (String) -> Unit,
    onTreePicked: (treeUri: String, path: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    var probe by remember { mutableStateOf<DirProbe>(DirProbe.Blank) }

    // 路径变更后重新查询：250ms 防抖，避免每次按键都列举一次目录
    LaunchedEffect(path) {
        delay(250)
        val snapshot = path
        probe = withContext(Dispatchers.IO) { probeDir(snapshot) }
    }

    // 系统目录选择器：ACTION_OPEN_DOCUMENT_TREE 是获取持久目录授权的唯一入口
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            // 持久授权仅能在此回调时机取得：错过后，App 重启将无法读取该目录
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val both = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, read or write)
            }.isSuccess
            if (!both) {
                // 部分 provider 仅授予读权限：此时再单独申请读权限
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, read)
                }
            }
            onTreePicked(uri.toString(), treeUriToPath(uri))
        }
    }

    fun openSystemPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        val ok = runCatching { treePicker.launch(intent) }.isSuccess
        if (!ok) NoticeCenter.error("系统无目录选择器，可改填路径")
    }

    val ready = probe as? DirProbe.Ready
    val normalized = normalizePath(path)

    Column(modifier = modifier.fillMaxWidth()) {

        // ---- 第一行：路径输入 + 纯图标按钮（系统目录选择器）----
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = onSurface.copy(alpha = 0.04f),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = path,
                    onValueChange = onPathEdited,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 13.dp),
                    textStyle = TextStyle(fontSize = 13.sp, color = onSurface),
                    cursorBrush = SolidColor(UiColors.accentFg),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    decorationBox = { inner ->
                        Box {
                            if (path.isEmpty()) {
                                Text(
                                    text = "请输入绝对路径，例如 /storage/emulated/0/Documents",
                                    fontSize = 13.sp,
                                    color = onSurface.copy(alpha = 0.35f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            inner()
                        }
                    }
                )
                // 纯图标按钮：打开系统目录选择器。图标复用抽屉中「工作区」的线稿文件夹，
                // 不使用 emoji：emoji 自带颜色，tint 对其无效
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .saltRowClickable(RoundedCornerShape(50)) { openSystemPicker() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FolderIcon,
                        contentDescription = "选择目录",
                        modifier = Modifier.size(18.dp),
                        tint = Palette.Neutral
                    )
                }
            }
        }

        // ---- 第二行：校验结果说明（路径无效时在此说明，不弹新窗口）----
        Spacer(Modifier.height(8.dp))
        val hint = when (val current = probe) {
            DirProbe.Blank -> "留空将使用应用私有目录（默认）"
            DirProbe.Missing -> "该路径不存在"
            DirProbe.NotDirectory -> "该路径是文件，不是目录"
            DirProbe.Denied -> "该目录不可读，请先在「权限」页授予存储权限"
            is DirProbe.Ready -> "该目录下有 " + current.subDirs.size + " 个子文件夹"
        }
        val hintColor = when (probe) {
            is DirProbe.Ready -> onSurface.copy(alpha = 0.6f)
            DirProbe.Blank -> onSurface.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.error
        }
        Text(text = hint, fontSize = 11.sp, color = hintColor)

        // ---- 第三行：当前根目录（确定后在该根目录下创建工作区）----
        Spacer(Modifier.height(4.dp))
        Text(
            text = "当前根目录：" + rootLabel(normalized, treeUri),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.55f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // ---- 第四行：子文件夹列表（点击即进入，在同一区域内刷新）----
        if (ready != null && ready.subDirs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = onSurface.copy(alpha = 0.04f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val shown = ready.subDirs.take(MAX_LISTED_DIRS)
                    shown.forEach { child ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 行在卡片内为矩形，形状由外层卡片裁剪（与设置页其它列表一致）
                                .saltCardRow() {
                                    onPathEdited(joinPath(normalized, child.name))
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = FolderIcon,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = Palette.Neutral
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = child.name,
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "›",
                                fontSize = 14.sp,
                                color = onSurface.copy(alpha = 0.35f)
                            )
                        }
                    }
                    if (ready.subDirs.size > shown.size) {
                        Text(
                            text = "另有 " + (ready.subDirs.size - shown.size) + " 个目录未显示，可直接修改路径",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            fontSize = 11.sp,
                            color = onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一次路径校验的结果。
 *
 * 子类型均嵌套于此（不在文件中分散定义）：该类型仅本文件使用，
 * 嵌套声明随 file-private 可见性，不会暴露到公开 API。
 */
private sealed interface DirProbe {
    /** 还没输入 / 留空。 */
    object Blank : DirProbe

    /** 路径不存在。 */
    object Missing : DirProbe

    /** 路径存在，但是个文件。 */
    object NotDirectory : DirProbe

    /** 是目录，但无法读取（通常为未授予存储权限）。 */
    object Denied : DirProbe

    /** 可以用的目录：[subDirs] 是它下面能看见的子文件夹（不含隐藏目录）。 */
    class Ready(val dir: File, val subDirs: List<File>) : DirProbe
}

/** 单个目录最多列出的子文件夹数量：超出时引导用户直接修改路径，避免弹窗过长。 */
private const val MAX_LISTED_DIRS = 200

/** 校验 + 列出子文件夹。放在 IO 线程跑，调用方用 withContext 包住。 */
private fun probeDir(rawPath: String): DirProbe {
    val path = normalizePath(rawPath)
    if (path.isEmpty()) return DirProbe.Blank
    val dir = File(path)
    if (!dir.exists()) return DirProbe.Missing
    if (!dir.isDirectory) return DirProbe.NotDirectory
    if (!dir.canRead()) return DirProbe.Denied
    val subDirs = try {
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    } catch (e: Exception) {
        // SecurityException 等情况：列举失败时按空列表处理，避免弹窗崩溃
        emptyList()
    }
    return DirProbe.Ready(dir, subDirs)
}

/** 「当前根」那一行显示什么。 */
private fun rootLabel(normalizedPath: String, treeUri: String?): String {
    if (normalizedPath.isNotEmpty()) return normalizedPath
    if (treeUri != null) return "已授权的系统目录（无法转换为本地路径）"
    return "未选择（使用应用私有目录）"
}

/**
 * 将用户输入规范化为标准绝对路径：去除 file:// 前缀、反斜杠转为正斜杠、合并重复斜杠、去除末尾斜杠。
 *
 * 从别处粘贴的路径常出现这几种形态，统一规范化后再比对，避免出现「路径存在却判定为不存在」。
 */
internal fun normalizePath(raw: String): String {
    var text = raw.trim()
    if (text.startsWith("file://")) text = text.removePrefix("file://")
    text = text.replace('\\', '/')
    while (text.contains("//")) text = text.replace("//", "/")
    if (text.length > 1 && text.endsWith("/")) text = text.dropLast(1)
    return text
}

/** 拼接子目录：去除两侧斜杠，避免出现双斜杠。 */
internal fun joinPath(parent: String, child: String): String {
    val base = normalizePath(parent)
    val name = child.trim().trim('/')
    return when {
        base.isEmpty() -> "/" + name
        name.isEmpty() -> base
        base == "/" -> "/" + name
        else -> base + "/" + name
    }
}

/**
 * SAF 的 tree URI → 本地绝对路径；无法推导时返回 null（**不做猜测**）。
 *
 * 系统 provider 的 documentId 形如 "primary:Documents/项目"：
 * - "primary:" 是内置存储根（/storage/emulated/0）；
 * - "home:" 是「文档」集合，AOSP 上就是 /storage/emulated/0/Documents；
 * - 其它卷（SD 卡为 UUID 形态）一律不猜测：推断错误会把工作区指向不存在或他人的目录。
 *
 * 反推得到的路径还需**实际存在**才有效；否则返回 null，界面据此只使用 treeUri（走 URI 分支）。
 */
@Suppress("DEPRECATION")
internal fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val colon = docId.indexOf(':')
    if (colon < 0) return null
    val volume = docId.substring(0, colon)
    val relative = docId.substring(colon + 1).trim('/')
    val storage = try {
        Environment.getExternalStorageDirectory().absolutePath
    } catch (e: Exception) {
        return null
    }
    val base = when {
        volume.equals("primary", ignoreCase = true) -> storage
        volume.equals("home", ignoreCase = true) -> joinPath(storage, "Documents")
        else -> return null
    }
    val candidate = joinPath(base, relative)
    return candidate.takeIf { File(it).isDirectory }
}
