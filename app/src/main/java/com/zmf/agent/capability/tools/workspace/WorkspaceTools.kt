package com.zmf.agent.capability.tools.workspace

import android.content.Context
import com.zmf.agent.capability.storage.StorageReclaimer
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.Storage
import com.zmf.agent.core.store.StorageKind
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 工作区工具集：列出、新建、改名、改映射、删除。
 *
 * 三条行为约定（与设计稿一致）：
 * 1. 先读后写：涉及 id 的操作先 `workspace_list`，不猜 id；
 * 2. 不静默造物：不自动创建目录、不自动新建工作区，目标不存在即返回错误；
 * 3. 破坏性操作先复述：删除工作区、更改储存路径要在对话里说明目标与后果后再调用。
 *
 * 所有工具只读写 `appdata` 里自己的工作区文件，最低权限档为只读档，
 * 但写操作仍标记 [mutates]，因此在只读档与计划模式下会被执行期拒绝。
 */
private const val LIST_LIMIT = 20

internal fun rootFormOf(storage: Storage?, context: Context): String {
    if (storage == null) return "未知"
    return when (storage.kind) {
        StorageKind.DEFAULT -> "默认目录"
        StorageKind.PRIVATE -> "内置目录"
        StorageKind.EXTERNAL -> if (!storage.treeUri.isNullOrBlank()) "系统授权目录" else "外部目录"
    }
}

internal fun workspaceLine(context: Context, space: WorkSpace): String {
    val storage = WorkSpaceStore.storageOf(context, space)
    val root = storage?.let { StorageStore.rootFile(context, it)?.absolutePath } ?: "（目录不可用）"
    return buildString {
        append("- ").append(space.name)
        append("（id=").append(space.id).append("）")
        append("；根形态：").append(rootFormOf(storage, context))
        append("；路径：").append(root)
        if (space.isDefault) append("；默认工作区，不可删除、不可改路径")
    }
}

/** 列出工作区：id、名称、根形态、是否默认。 */
class WorkspaceListTool : Tool {

    override val name = "workspace_list"

    override val description =
        "列出全部工作区：id、名称、根形态与路径、是否默认工作区。" +
            "涉及工作区 id 的操作先调用本工具，不要凭猜测填 id。默认最多返回 20 条。"

    override val parametersJson = """
        {"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val spaces = WorkSpaceStore.load(ctx.context)
        if (spaces.isEmpty()) return@withContext ToolResult.Ok("当前没有工作区")
        val shown = spaces.take(LIST_LIMIT)
        val text = buildString {
            append("工作区 ").append(spaces.size).append(" 个")
            if (spaces.size > shown.size) append("（仅列前 ").append(shown.size).append(" 个）")
            append("：\n")
            shown.forEach { append(workspaceLine(ctx.context, it)).append("\n") }
        }
        ToolResult.Ok(text.trimEnd())
    }
}

/** 新建工作区；不传 path 即建内置目录。目录已被映射时拒绝并返回占用者。 */
class WorkspaceCreateTool : Tool {

    override val name = "workspace_create"

    override val description =
        "新建工作区。不传 path 时创建一个应用内置目录；传 path 时映射到该目录，不移动任何已有文件。" +
            "该目录已被其他工作区使用时会被拒绝并返回占用者；目录不存在、不是目录或不可写时同样拒绝。" +
            "本工具不会替你创建目录。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "name": {"type": "string", "description": "工作区名称，显示在侧边栏"},
            "path": {"type": "string", "description": "可选：要映射的绝对路径；省略则使用应用内置目录"}
          },
          "required": ["name"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) return@withContext ToolResult.Error("name 不能为空")
        val path = args.optString("path", "").trim().takeIf { it.isNotEmpty() }
        if (path != null) {
            val dir = File(path)
            if (!dir.isDirectory) {
                return@withContext ToolResult.Error("目录不存在或不是目录：" + path + "（本工具不会创建目录）")
            }
            if (!dir.canWrite()) return@withContext ToolResult.Error("目录不可写：" + path)
            val storage = StorageStore.findByPath(ctx.context, dir)
            if (storage != null) {
                val owner = WorkSpaceStore.load(ctx.context).firstOrNull { it.storageId == storage.id }
                if (owner != null) {
                    return@withContext ToolResult.Error("该目录已由工作区「" + owner.name + "」使用")
                }
            }
        }
        val existing = WorkSpaceStore.load(ctx.context).map { it.name }.toSet()
        if (name in existing) return@withContext ToolResult.Error("已有同名工作区：「" + name + "」")

        val storage = if (path == null) {
            StorageStore.createPrivate(ctx.context, name)
        } else {
            StorageStore.createExternal(ctx.context, path, null)
                ?: return@withContext ToolResult.Error("目录不可用：" + path)
        }
        val space = WorkSpaceStore.newRecord(name, storage.id)
        val storages = StorageStore.loadStorages(ctx.context)
        val next = if (storages.any { it.id == storage.id }) storages else storages + storage
        StorageStore.saveRegistry(
            ctx.context,
            StorageStore.Registry(WorkSpaceStore.load(ctx.context) + space, next)
        )
        ToolResult.Ok("已新建工作区「" + space.name + "」（id=" + space.id + "）")
    }
}

/** 只改显示名，不动目录。 */
class WorkspaceRenameTool : Tool {

    override val name = "workspace_rename"

    override val description =
        "修改工作区的显示名称。只改名称，不动目录，也不影响会话的归属与运行根。" +
            "默认工作区的名称固定，不可修改。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "工作区 id，先用 workspace_list 取得"},
            "name": {"type": "string", "description": "新的显示名称，不能为空白"}
          },
          "required": ["id", "name"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) return@withContext ToolResult.Error("name 不能为空")
        val spaces = WorkSpaceStore.load(ctx.context)
        val target = spaces.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("没有这个工作区：" + id)
        if (target.isDefault) return@withContext ToolResult.Error("默认工作区的名称固定，不可修改")
        val renamed = WorkSpaceStore.rename(ctx.context, id, name)
            ?: return@withContext ToolResult.Error("改名失败：" + id)
        ToolResult.Ok("已改名为「" + renamed.name + "」")
    }
}

/** 改映射：不移动文件，只把工作区与其余下会话指向新的存储单元。 */
class WorkspaceSetRootTool : Tool {

    override val name = "workspace_set_root"

    override val description =
        "更改工作区映射的目录：只改应用内的映射，不移动、不删除任何文件。工作区与其下全部会话的运行根一起改变。" +
            "原目录是内置目录且引用归零时，按规则直接删除空目录或移入回收站。" +
            "调用前必须在对话里说明后果。没有会话正在运行时才允许执行。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "工作区 id"},
            "path": {"type": "string", "description": "新的绝对路径；目录必须已存在且可写"},
            "treeUri": {"type": "string", "description": "可选：SAF 授权目录的 tree URI，存在时优先于 path"}
          },
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val path = args.optString("path", "").trim().takeIf { it.isNotEmpty() }
        val tree = args.optString("treeUri", "").trim().takeIf { it.isNotEmpty() }
        if (path == null && tree == null) return@withContext ToolResult.Error("path 与 treeUri 至少要给一个")
        val spaces = WorkSpaceStore.load(ctx.context)
        val space = spaces.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("没有这个工作区：" + id)
        if (space.isDefault) return@withContext ToolResult.Error("默认工作区不可更改储存路径")
        val old = space.storageId
        val storage = StorageStore.createExternal(ctx.context, path, tree)
            ?: return@withContext ToolResult.Error("目录不可用：需要已存在、是目录、可写；SAF 目录需持有持久授权")
        if (storage.id == old) return@withContext ToolResult.Ok("目录未变化")

        val storages = StorageStore.loadStorages(ctx.context)
        val next = if (storages.any { it.id == storage.id }) storages else storages + storage
        val nextSpaces = spaces.map { if (it.id == id) it.copy(storageId = storage.id) else it }
        StorageStore.saveRegistry(ctx.context, StorageStore.Registry(nextSpaces, next))

        // 会话的运行根随分组一起改，否则工具的相对路径会解析到旧目录
        val sessions = SessionStore.load(ctx.context)
        val moved = sessions.map { if (it.workspaceId == id) it.copy(storageId = storage.id) else it }
        SessionStore.save(ctx.context, moved)

        val outcome = StorageReclaimer.reclaim(ctx.context, old, space.name, nextSpaces, moved)
        val suffix = when (outcome.result) {
            StorageReclaimer.Result.TRASHED -> "；原内置目录已移入回收站"
            StorageReclaimer.Result.DELETED_EMPTY -> "；原内置目录为空，已直接移除"
            else -> ""
        }
        ToolResult.Ok("已更改「" + space.name + "」的储存路径" + suffix)
    }
}

/** 删除映射：其下会话转未分类，内置目录按引用归零回收。 */
class WorkspaceDeleteTool : Tool {

    override val name = "workspace_delete"

    override val description =
        "删除工作区。**只删除映射**，不删除任何用户文件：其下会话转为「未分类」，仍指向原目录。" +
            "内置目录在引用归零时直接删除（空）或移入回收站保留 30 天；外部目录与默认目录永不回收。" +
            "调用前必须在对话里说明这一点。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {"id": {"type": "string", "description": "工作区 id"}},
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val spaces = WorkSpaceStore.load(ctx.context)
        val space = spaces.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("没有这个工作区：" + id)
        if (space.isDefault) return@withContext ToolResult.Error("默认工作区不可删除")

        val remaining = spaces.filter { it.id != id }
        WorkSpaceStore.save(ctx.context, remaining)
        // 其下空白会话直接删除：它从未转正，留在未分类只会成为不可见的残留
        val sessions = SessionStore.load(ctx.context)
        val keep = sessions.filterNot { it.workspaceId == id && it.blank }
        val released = keep.map { if (it.workspaceId == id) it.copy(workspaceId = null) else it }
        // 有变化才写盘：无变化时避免整表重写会话文件
        if (released != sessions) SessionStore.save(ctx.context, released)
        val outcome = StorageReclaimer.reclaim(ctx.context, space.storageId, space.name, remaining, released)
        val suffix = when (outcome.result) {
            StorageReclaimer.Result.TRASHED -> "；内置目录已移入回收站，保留 30 天"
            StorageReclaimer.Result.DELETED_EMPTY -> "；内置目录为空，已直接移除"
            else -> ""
        }
        ToolResult.Ok("已删除工作区「" + space.name + "」的映射" + suffix)
    }
}
