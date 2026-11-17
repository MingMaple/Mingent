package com.zmf.agent.capability.tools.workspace.support

import android.content.Context
import com.zmf.agent.core.store.Storage
import com.zmf.agent.core.store.StorageKind
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore

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
internal const val LIST_LIMIT = 20

/** 存储根形态的中文名：默认目录 / 内置目录 / 外部目录 / 系统授权目录。 */
internal fun rootFormOf(storage: Storage?, context: Context): String {
    if (storage == null) return "未知"
    return when (storage.kind) {
        StorageKind.DEFAULT -> "默认目录"
        StorageKind.PRIVATE -> "内置目录"
        StorageKind.EXTERNAL -> if (!storage.treeUri.isNullOrBlank()) "系统授权目录" else "外部目录"
    }
}

/** 工作区的一行摘要：名称、id、根形态、路径与默认工作区标记。 */
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
