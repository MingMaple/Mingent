package com.zmf.agent.feature.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.SharePayload
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.UploadStore
import java.io.File
import java.util.Locale

/**
 * 分享正文进入消息正文的字符上限：**4000 字符**，按 `String.length`（用户可见字符数）计，
 * 不按 UTF-8 字节。
 *
 * 取值理由（四条，均为本项目的实现约束与工业对照，不是实验结论）：
 *
 * 1. Compose 的 `TextField` 是受控组件，每次编辑都重建字符串并触发测量、布局与语义刷新，
 *    实践中两三千字符起跟手性明显下降；Compose 文本引擎维护方亦明确表示不为大文本设计。
 * 2. ChatGPT 网页版在约 5000 字符起把粘贴内容转为附件、后统一到约 10000，但那是桌面端
 *    配独立编辑器的场景；本应用是移动端，消息正文还要与附件卡片、图片网格、待办条共存，
 *    取 4000 更保守。
 * 3. 4000 字符足以容纳一段完整代码块、一篇短资讯或一封长邮件；超过它的内容通常是文章或
 *    文件，作为附件交给模型读取更合适，也便于用户在工作区里继续处理。
 * 4. 不按字节：一个汉字约 3 字节，若按 4000 字节计只剩约 1330 汉字，明显偏短。
 *
 * 正文形态只有两段：**≤ 阈值直接贴成消息正文，> 阈值落成工作区文件并作为附件随消息带走**。
 * 不做「折叠显示」这一中间档——预览区的行数截断只是视觉收敛，不参与发送形态的判定。
 */
internal const val SHARE_INLINE_TEXT_LIMIT = 4000

/** 正文转为附件、用户又没写补充说明时的最小正文：空正文会被 `send` 的空内容闸门拦下。 */
internal const val SHARE_ATTACHMENT_PLACEHOLDER = "分享内容已作为附件"

/** 同一份分享在此时长内被再次投递即视为重复（系统「分享」选单偶发连续投递两次）。 */
private const val SHARE_DEDUPE_WINDOW_MS = 2_000L

/** 长文附件在暂存目录里的文件名前缀；发送时按普通附件同一套命名规则落进目标工作区。 */
private const val SHARE_FILE_PREFIX = "share-"

/** 分享内容的中转目录名：位于应用私有数据目录下，与任何工作区无关。 */
private const val SHARE_STAGING_DIR = "share-staging"

/**
 * 单张分享图片的源文件上限：50 MB，与输入区选图的闸门同值（对齐 DSH）。
 *
 * 分享方的图片可能极大，解码前先按长度拦一道；无法取得长度（-1）时不拦截，
 * 交由导入时的归一化兜住（`UploadStore.importImage`）。
 */
private const val SHARE_IMAGE_MAX_BYTES = 50L * 1024 * 1024

/**
 * 本次分享的目标会话。
 *
 * 「新建会话」需要携带工作区，因为面板允许在发送前切换工作区；「已有会话」由会话自身决定工作区
 * （切换会话时选中工作区随之同步），不再接受另外指定的工作区。两项都只是**面板上的选择动作**：
 * 选定即真的切换会话，发送时一律发往当时所在的会话。
 */
internal sealed interface ShareTarget {

    /** 在指定工作区（null = 未分类）新建一条会话；实现复用 `SessionController.newSession()`。 */
    data class NewSession(val workspaceId: String?) : ShareTarget

    /** 发送到指定的已有会话。 */
    data class Session(val id: String) : ShareTarget
}

/**
 * 分享内容在受理时落成的中转文件。
 *
 * 图片与超阈值长文在**收到时**就复制进应用私有的 `appdata/share-staging/<批次>/`：URI 读权限随
 * 接收方任务栈结束而失效，等到用户点发送再读，面板停留稍久或系统回收任务栈就会失败。
 * 中转位置与任何工作区无关：用户选定目标并点发送时，才用普通主页输入框同一套
 * [UploadStore.import] 把它们落进目标会话的工作区 `uploads/`。
 *
 * @param inlineText 进入消息正文的分享正文；已转附件时为空串
 * @param longTextRelPath 超阈值长文在中转目录里的相对路径；未超阈值时为 null
 * @param longTextChars 长文字符数（面板提示「已作为附件（N 字）」用）
 * @param imageRelPaths 已复制进中转目录的图片相对路径，顺序同发送方给出的顺序
 * @param failedImages 复制失败的图片张数：预览区据此提示，仍允许只发文字
 * @param fileRelPaths 已复制进中转目录的普通文件（pdf、docx、zip 等）相对路径；与图片分开
 * @param failedFiles 复制失败的文件个数（读取失败或超过普通附件上限）
 * @param sourceRootPath 中转批次的绝对路径；发送时据此复制，放弃时整批删除
 * @param titleHint 新会话标题的候选（正文首行、长文名或文件名），避免标题退化成占位句
 */
internal data class ShareDraft(
    val payload: SharePayload,
    val inlineText: String,
    val longTextRelPath: String?,
    val longTextChars: Int,
    val imageRelPaths: List<String>,
    val failedImages: Int,
    val fileRelPaths: List<String>,
    val failedFiles: Int,
    val sourceRootPath: String,
    val titleHint: String
) {

    /** 是否带有需要随消息带走的文件（长文附件、普通文件或图片）。 */
    val hasFiles: Boolean
        get() = longTextRelPath != null || fileRelPaths.isNotEmpty() || imageRelPaths.isNotEmpty()

    /** 完全没有可发送内容：正文为空且无附件、无图片、无文件。 */
    val isEmpty: Boolean get() = inlineText.isEmpty() && !hasFiles
}

/** 发送前整理出的附件与图片队列。 */
internal data class ShareAttachments(
    val files: List<PendingFile>,
    val images: List<PendingFile>
)

/**
 * 分享入口的解析、去重与落盘。
 *
 * 归属 `feature/chat`：它把系统 Intent 翻译成本功能的待发内容，并使用聊天会话的工作区目录；
 * `Intent` 只在 app 层交接，面板本身不读 Intent（见 `_Docs/模块化.md` §1「feature 一个用户可见
 * 的功能面」）。
 */
internal object ShareIntake {

    /** 上一个被受理的签名与时刻：仅用于 2 秒窗口内的重复投递拦截。 */
    private var lastSignature: String? = null
    private var lastAcceptedAt = 0L

    /**
     * 从 Intent 解析载荷。
     *
     * - `EXTRA_TEXT` 按 `CharSequence` 取再 `toString()`：部分发送方给的是 `Spanned`，
     *   用 `getStringExtra` 会丢掉样式（本应用不渲染富文本，但取全文本才是对的内容）。
     * - 附件地址同时读 `ClipData` 与 `EXTRA_STREAM`：`createChooser()` 只搬运 `ClipData`，
     *   部分发送方只给其中一个，两处都读才不会漏。
     * - 图片与文件**分开收集**：以 `image/` 判定图片，其余地址全部按普通文件收下
     *   （pdf、docx、zip，以及以文件形式分享的 md/csv 等）。二者不混，图片仍走图片链路。
     * - 任何异常都降级为 null（无内容），绝不把解析失败带成崩溃。
     */
    fun from(context: Context, intent: Intent?): SharePayload? = runCatching {
        if (intent == null) return@runCatching null
        // 相册与文件多选分享走 SEND_MULTIPLE，与单条分享的内容来源完全相同（EXTRA_STREAM/ClipData）
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return@runCatching null
        val hostMime = intent.type?.lowercase(Locale.US).orEmpty()
        val mimeTypes = ArrayList<String>()
        if (hostMime.isNotEmpty()) mimeTypes.add(hostMime)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        val title = (
            intent.getCharSequenceExtra(Intent.EXTRA_TITLE)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
            )?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val images = LinkedHashSet<Uri>()
        val files = LinkedHashSet<Uri>()
        // 来源一：ClipData（createChooser 只搬运这里）
        val clip = intent.clipData
        if (clip != null) {
            val description = clip.description
            for (index in 0 until clip.itemCount) {
                val uri = clip.getItemAt(index).uri ?: continue
                // 条目自身的 MIME 在 ClipDescription 上按序号读取，ClipData.Item 不携带类型
                val itemMime = runCatching { description.getMimeType(index) }
                    .getOrNull()?.lowercase(Locale.US).orEmpty()
                if (itemMime.isNotEmpty()) mimeTypes.add(itemMime)
                if (acceptsImage(hostMime, itemMime)) images.add(uri) else files.add(uri)
            }
        }
        // 来源二：EXTRA_STREAM，兼容单个与列表两种写法
        for (uri in streamUris(intent)) {
            // ClipData 与 EXTRA_STREAM 常同时给出同一地址（宿主类型为 */* 时无法靠 MIME 判重）
            if (images.contains(uri) || files.contains(uri)) continue
            if (acceptsImage(hostMime, "")) images.add(uri) else files.add(uri)
        }
        if (text == null && images.isEmpty() && files.isEmpty()) return@runCatching null
        SharePayload(
            sourcePackage = intent.`package`,
            title = title,
            text = text,
            imageUris = images.toList(),
            fileUris = files.toList(),
            mimeTypes = mimeTypes.distinct(),
            receivedAt = System.currentTimeMillis(),
            signature = SharePayload.signatureOf(
                intent.`package`,
                mimeTypes,
                text,
                images.toList(),
                files.toList()
            )
        )
    }.getOrNull()

    /**
     * 受理一条载荷：同一签名在 [SHARE_DEDUPE_WINDOW_MS] 内重复到达时返回 false。
     *
     * 签名相同不代表内容完全一致（正文只参与长度与哈希），因此窗口取 2 秒：足够拦住系统
     * 分享选单的连续投递，又不会把「用户过一会儿又分享同一段文字」误判成重复。
     */
    @Synchronized
    fun accept(payload: SharePayload): Boolean {
        val now = System.currentTimeMillis()
        val duplicate = payload.signature == lastSignature && now - lastAcceptedAt < SHARE_DEDUPE_WINDOW_MS
        if (!duplicate) {
            lastSignature = payload.signature
            lastAcceptedAt = now
        }
        return !duplicate
    }

    /**
     * 把载荷落进应用私有的中转目录并生成待发草稿；无任何内容时返回 null。
     *
     * 中转位置与任何工作区无关：分享进来时还没有「落到哪个工作区」的决定，提前写进某个
     * 工作区会让用户在面板里改目标后留下无人引用的文件。发送时才用普通输入框同一套
     * [UploadStore.import] 复制进目标工作区（见 [stageForSend]）。
     *
     * 必须在 IO 线程调用：图片要解码压缩，文件与长文要写盘。
     */
    fun stage(context: Context, payload: SharePayload): ShareDraft? {
        val root = stagingBatchDir(context) ?: return null
        val text = payload.text.orEmpty()
        // 超过阈值才转附件；写盘失败时退回正文（宁可正文长一点，也不能丢内容）
        val longRel = if (text.length > SHARE_INLINE_TEXT_LIMIT) writeLongText(root, text) else null
        val inline = longRel == null
        val files = ArrayList<String>()
        var failedFiles = 0
        for (uri in payload.fileUris) {
            // 先按长度拦一道（与普通附件同一上限）；导入过程还会再按字节数兜一次
            if (exceedsFileLimit(context, uri)) {
                failedFiles++
                continue
            }
            UploadStore.import(context, uri, root).fold(
                onSuccess = { files.add(it.relPath) },
                onFailure = { failedFiles++ }
            )
        }
        val images = ArrayList<String>()
        var failed = 0
        for (uri in payload.imageUris) {
            if (exceedsImageLimit(context, uri)) {
                failed++
                continue
            }
            UploadStore.importImage(context, uri, root).fold(
                onSuccess = { images.add(it.relPath) },
                onFailure = { failed++ }
            )
        }
        val draft = ShareDraft(
            payload = payload,
            inlineText = if (inline) text else "",
            longTextRelPath = longRel,
            longTextChars = if (longRel == null) 0 else text.length,
            imageRelPaths = images,
            failedImages = failed,
            fileRelPaths = files,
            failedFiles = failedFiles,
            sourceRootPath = root.absolutePath,
            titleHint = titleHint(text, longRel, files)
        )
        if (draft.isEmpty) {
            discard(draft)
            return null
        }
        FileLog.log(
            "share",
            "分享已落盘：正文 " + payload.textLength + " 字（" +
                (if (longRel != null) "转附件 " + longRel else "进正文") + "），图片 " +
                images.size + " 张（失败 " + failed + " 张），文件 " +
                files.size + " 个（失败 " + failedFiles + " 个）"
        )
        return draft
    }

    /**
     * 发送前把中转文件落进目标工作区的 `uploads/`。
     *
     * 复用普通主页输入框的落盘实现 [UploadStore.import]：同一套文件名清洗、重名追加序号、
     * `.part` 先写后改名与 50MB 上限，因此分享附件与手动选择的附件在磁盘上没有区别。
     * 中转文件是本地文件，用 `file://` 地址交给同一个入口，不依赖已失效的 content URI。
     * 必须在 IO 线程调用。
     */
    fun stageForSend(context: Context, draft: ShareDraft, targetRoot: File): ShareAttachments {
        val stagingRoot = File(draft.sourceRootPath)
        // 长文附件、普通文件、图片一起按同一套导入落到目标工作区；顺序决定消息里的附件顺序
        val fileSources = listOfNotNull(draft.longTextRelPath) + draft.fileRelPaths
        val sources = fileSources + draft.imageRelPaths
        val mapped = LinkedHashMap<String, String>()
        var failed = 0
        for (rel in sources) {
            val source = File(stagingRoot, rel)
            val imported = if (source.isFile) {
                UploadStore.import(context, Uri.fromFile(source), targetRoot).getOrNull()
            } else {
                null
            }
            if (imported == null) failed++ else mapped[rel] = imported.relPath
        }
        val files: List<PendingFile> = fileSources.mapNotNull { rel ->
            mapped[rel]?.let { PendingFile(it.substringAfterLast('/'), it) }
        }
        val images = draft.imageRelPaths.mapNotNull { rel ->
            mapped[rel]?.let { PendingFile(it.substringAfterLast('/'), it) }
        }
        if (failed > 0) {
            FileLog.log("share", "分享附件复制到目标工作区失败 " + failed + " 个，仅发送成功部分")
        }
        return ShareAttachments(files = files, images = images)
    }

    /** 删除本次中转的单个附件：用户在面板里移除附件时同步清掉，不留无人引用的中转文件。 */
    fun discardFile(draft: ShareDraft, relPath: String) {
        runCatching { File(draft.sourceRootPath, relPath).delete() }
    }

    /** 删除本次分享的整个中转批次：放弃分享、发送完成或内容为空时调用。 */
    fun discard(draft: ShareDraft) {
        runCatching { File(draft.sourceRootPath).deleteRecursively() }
    }

    /**
     * 消息正文的拼装规则（唯一出处，界面与文档都照它）。
     *
     * 面板只有一个文本框：未超阈值的正文在受理时就**预填进该框**，用户改什么就发什么；
     * 超阈值或非文本内容则只带着附件，文本框留给用户补要求。因此这里只处理一种情况——
     * 有附件而用户一个字都没写时补一句 [SHARE_ATTACHMENT_PLACEHOLDER]，
     * 否则空正文会被发送链路的「空内容」闸门拦下。
     */
    fun composeBody(draft: ShareDraft, editorText: String): String {
        val trimmed = editorText.trim()
        return if (trimmed.isEmpty() && draft.hasFiles) SHARE_ATTACHMENT_PLACEHOLDER else trimmed
    }

    /** 图片条目是否属于本入口受理的图片：宿主类型或条目类型任一以 `image/` 开头即可。 */
    private fun acceptsImage(hostMime: String, itemMime: String): Boolean =
        hostMime.startsWith("image/") || itemMime.startsWith("image/")

    /**
     * 读 `EXTRA_STREAM`：可能是单个 `Uri`，也可能是 `ArrayList<Uri>`（多图发送方偶有给出）。
     * 平台 API 在 T 之后改为类型化重载，此处显式压制弃用告警，minSdk 26 仍走旧重载。
     */
    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> {
        val single = runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        if (single != null) return listOf(single)
        val many = runCatching {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }.getOrNull()
        return many?.filterNotNull().orEmpty()
    }

    /** 图片源文件是否超过 [SHARE_IMAGE_MAX_BYTES]；无法取得长度（-1）时不拦截。 */
    private fun exceedsImageLimit(context: Context, uri: Uri): Boolean {
        val bytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        return bytes > SHARE_IMAGE_MAX_BYTES
    }

    /**
     * 普通文件是否超过 [UploadStore.MAX_BYTES]（50MB）：与主页输入框选择附件同一口径。
     *
     * 与图片一样先按长度拦一道，避免把超大文件整个读进来；导入过程仍会边写边计字节兜底。
     * 无法取得长度（-1）时不拦截。
     */
    private fun exceedsFileLimit(context: Context, uri: Uri): Boolean {
        val bytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        return bytes > UploadStore.MAX_BYTES
    }

    /**
     * 本次分享的中转批次目录：`filesDir/appdata/share-staging/<时间戳>/`。
     *
     * 每批次独立子目录，放弃时整批删除不会误删另一份正在进行的分享。
     */
    private fun stagingBatchDir(context: Context): File? = runCatching {
        val parent = File(AppPaths.dataDir(context), SHARE_STAGING_DIR)
        if (!parent.exists()) parent.mkdirs()
        val stamp = System.currentTimeMillis()
        var name = stamp.toString()
        var index = 0
        while (File(parent, name).exists()) {
            index++
            name = stamp.toString() + "-" + index
        }
        val dir = File(parent, name)
        if (!dir.mkdirs()) null else dir
    }.getOrNull()

    /**
     * 长文写成中转目录下的 `uploads/share-<时间戳>.md`。
     *
     * 先写 `.part` 再改名，与 `UploadStore` 的落盘纪律一致：中途失败或进程终止都不会留下
     * 看似可用却内容不全的附件。写入使用 UTF-8 与 LF，保持原文的段落结构。
     */
    private fun writeLongText(root: File, text: String): String? = runCatching {
        val dir = File(root, UploadStore.DIR)
        if (!dir.exists()) dir.mkdirs()
        val name = uniqueShareName(dir, System.currentTimeMillis())
        val target = File(dir, name)
        val temp = File(dir, name + ".part")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.delete()
            return@runCatching null
        }
        UploadStore.DIR + "/" + name
    }.getOrNull()

    /** 分享附件重名时追加序号，避免覆盖上一次分享落成的同名文件。 */
    private fun uniqueShareName(dir: File, stamp: Long): String {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "-" + index
            val name = SHARE_FILE_PREFIX + stamp + suffix + ".md"
            if (!File(dir, name).exists()) return name
            index++
        }
    }

    /**
     * 新会话标题候选：按与普通会话同一套兜底口径取正文，没有正文时依次用长文附件名、
     * 普通文件名。
     */
    private fun titleHint(text: String, longRel: String?, files: List<String>): String {
        val fromText = SessionStore.fallbackTitle(text)
        if (fromText.isNotEmpty()) return fromText
        for (rel in listOfNotNull(longRel) + files) {
            val fromName = SessionStore.fallbackTitle(rel.substringAfterLast('/'))
            if (fromName.isNotEmpty()) return fromName
        }
        return ""
    }
}
