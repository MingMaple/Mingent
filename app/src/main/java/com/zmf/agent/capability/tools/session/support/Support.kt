package com.zmf.agent.capability.tools.session.support

import android.content.Context
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore

/**
 * 会话工具集：列出、新建、改名、移动、归档、恢复、删除、改偏好。
 *
 * 三条行为约定（与设计稿一致）：
 * 1. 先读后写：涉及 id 的操作先 `session_list`，不猜 id；
 * 2. 破坏性操作先复述：删除会话、移动会话要先说明目标与后果；
 * 3. 删除只对已归档会话开放，未归档的先归档——与界面入口保持同一套秩序。
 *
 * 每次写操作之后都通过 [com.zmf.agent.capability.runtime.ChatBridge] 通知界面重读，避免工具改完而列表仍是旧快照。
 */
internal const val LIST_LIMIT = 20

/** 会话的一行摘要：标题、id、状态。 */
internal fun sessionLine(session: ChatSession): String = buildString {
    append("- ").append(session.title.ifBlank { "新会话" })
    append("（id=").append(session.id).append("）")
    if (session.archived) append("；已归档")
    if (session.blank) append("；空白会话")
    if (!session.listed) append("；不在列表显示")
    append("；更新于 ").append(SessionStore.relativeTime(session.updatedAt))
}

/** 按 id 查工作区；id 为 null 或不存在时返回 null。 */
internal fun workspaceOf(context: Context, id: String?): WorkSpace? =
    WorkSpaceStore.load(context).firstOrNull { it.id == id }
