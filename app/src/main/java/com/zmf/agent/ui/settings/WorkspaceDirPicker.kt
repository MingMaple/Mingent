package com.zmf.agent.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
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
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.chat.FolderIcon
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Palette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 「自定义目录」展开区的内容：绝对路径输入 → 校验 → 列子文件夹 → 点进去，外加系统目录选择器。
 *
 * 设计口径（这几条是刻意收窄的，别自作主张加东西）：
 * - **不弹新窗口**：进入子文件夹只是就地刷新列表 + 把子目录名追加到输入框路径末尾；
 * - **没有「返回上一级」**：回退靠用户自己改输入框里的路径；
 * - **没有「新建文件夹」、没有「显示隐藏文件」**：多余按钮一律不加（隐藏目录不列出来）；
 * - 右侧那个 📁 是**纯图标按钮**（文字字形，不是矢量资源）：打开系统目录选择器，
 *   选完把该目录的路径回填进输入框，其余逻辑跟上一条完全一样（当作一个根目录）。
 *
 * 状态归调用方（[path] / [treeUri] 受控）：手输或点进子目录走 [onPathEdited]，
 * 系统选择器选完走 [onTreePicked]。宿主据此决定「路径改了就清掉 treeUri」这类口径。
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

    // 路径一改就重查：250ms 防抖，手输时不会每敲一个字符就去 list 一次目录
    LaunchedEffect(path) {
        delay(250)
        val snapshot = path
        probe = withContext(Dispatchers.IO) { probeDir(snapshot) }
    }

    // 系统目录选择器：ACTION_OPEN_DOCUMENT_TREE 是唯一能「拿持久目录授权」的入口
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            // 持久授权只在这一个时机能拿：错过它，App 重启后这个目录就读不到了
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val both = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, read or write)
            }.isSuccess
            if (!both) {
                // 有的 provider 只给读权限：退一步再单独要读授权
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
        if (!ok) Toast.makeText(context, "这台机器没有目录选择器", Toast.LENGTH_SHORT).show()
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
                    cursorBrush = SolidColor(Palette.AccentGreen),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    decorationBox = { inner ->
                        Box {
                            if (path.isEmpty()) {
                                Text(
                                    text = "输入绝对路径，如 /storage/emulated/0/Documents",
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
                // 纯图标按钮：打开系统目录选择器。图标复用抽屉里「工作区」那枚线稿文件夹，
                // 不用 emoji：emoji 自带颜色，tint 对它无效，全局唯一一处彩色图标就是它
                Box(
                    modifier = Modifier
                        .size(40.dp)
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

        // ---- 第二行：校验结果一句话（路径不对就在这儿说清，不弹新窗口）----
        Spacer(Modifier.height(8.dp))
        val hint = when (val current = probe) {
            DirProbe.Blank -> "留空就用应用私有目录（默认行为）"
            DirProbe.Missing -> "这个路径不存在"
            DirProbe.NotDirectory -> "这个路径是文件，不是目录"
            DirProbe.Denied -> "这个目录读不了：先去「权限」页开启存储权限"
            is DirProbe.Ready -> "这个目录下有 " + current.subDirs.size + " 个子文件夹"
        }
        val hintColor = when (probe) {
            is DirProbe.Ready -> Palette.AccentGreen
            DirProbe.Blank -> onSurface.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.error
        }
        Text(text = hint, fontSize = 11.sp, color = hintColor)

        // ---- 第三行：当前根（点确定就在这个根目录下建工作区）----
        Spacer(Modifier.height(4.dp))
        Text(
            text = "当前根：" + rootLabel(normalized, treeUri),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.55f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // ---- 第四行：子文件夹列表（点一下就进去，同一区域内刷新）----
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
                                // 行在卡片内是矩形，形状交给外层卡片裁剪（跟设置页其它列表同一个口径）
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
                            text = "还有 " + (ready.subDirs.size - shown.size) + " 个没列出来，直接改路径更快",
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
 * 子类型全嵌在里面（而不是散在文件里）：这个类型只在本文件用，
 * 嵌套声明天然跟着 file-private 走，不会往公开 API 上漏。
 */
private sealed interface DirProbe {
    /** 还没输入 / 留空。 */
    object Blank : DirProbe

    /** 路径不存在。 */
    object Missing : DirProbe

    /** 路径存在，但是个文件。 */
    object NotDirectory : DirProbe

    /** 是目录，但读不了（多半是没给存储权限）。 */
    object Denied : DirProbe

    /** 可以用的目录：[subDirs] 是它下面能看见的子文件夹（不含隐藏目录）。 */
    class Ready(val dir: File, val subDirs: List<File>) : DirProbe
}

/** 一个目录最多列出多少个子文件夹：再多就让用户直接改路径，别把弹窗撑成一本电话簿。 */
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
        // SecurityException 之类：列不动就当空列表，别让弹窗崩在这儿
        emptyList()
    }
    return DirProbe.Ready(dir, subDirs)
}

/** 「当前根」那一行显示什么。 */
private fun rootLabel(normalizedPath: String, treeUri: String?): String {
    if (normalizedPath.isNotEmpty()) return normalizedPath
    if (treeUri != null) return "已授权的系统目录（无法转成本地路径）"
    return "未选择（用应用私有目录）"
}

/**
 * 把用户输入收敛成标准绝对路径：去掉 file:// 前缀、反斜杠转正斜杠、合并重复斜杠、去末尾斜杠。
 *
 * 用户从别处粘贴路径时这几种形态都会出现，收一遍再比对，少一次「明明存在却说不存在」。
 */
internal fun normalizePath(raw: String): String {
    var text = raw.trim()
    if (text.startsWith("file://")) text = text.removePrefix("file://")
    text = text.replace('\\', '/')
    while (text.contains("//")) text = text.replace("//", "/")
    if (text.length > 1 && text.endsWith("/")) text = text.dropLast(1)
    return text
}

/** 拼子目录：两侧斜杠都吃掉，绝不出现双斜杠。 */
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
 * SAF 的 tree URI → 本地绝对路径；推不出来就返回 null（**不猜**）。
 *
 * 系统 provider 的 documentId 形如 "primary:Documents/项目"：
 * - "primary:" 是内置存储根（/storage/emulated/0）；
 * - "home:" 是「文档」集合，AOSP 上就是 /storage/emulated/0/Documents；
 * - 其它卷（SD 卡是 UUID 形态）一律不猜：猜错会把工作区指到一个不存在、或别人家的目录上。
 *
 * 反推出来的路径还要**真的存在**才算数；否则返回 null，界面据此只认 treeUri（走 URI 分支）。
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
