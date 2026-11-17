package com.zmf.agent.feature.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.zmf.agent.core.model.ChatMessage

/**
 * 会话消息表：**每个会话各持一份消息列表**。
 *
 * 为什么不是一份列表：生成回复期间允许切换会话（用户 2026-11-15 要求）。只有一份列表时，
 * 切会话必然要 `clear + addAll`，正在跑的那一轮就没了写入目标——界面看到的会是新会话的内容，
 * 而回合仍在往同一份列表里追加，两边的消息互相污染。
 *
 * 分工：
 * - 界面读的是**当前显示会话**那一份（[com.zmf.agent.feature.chat.ChatViewModel.messages]）；
 * - 一轮对话在开局时钉住自己的会话，此后整个回合（含流式追加、落盘检查点、用量写回）
 *   都只写那一份，与用户此刻在看哪条会话无关；
 * - 列表是 Compose 快照状态，切回某会话时界面直接读它，不需要再从记录里重建。
 *
 * 内存里的这一份是「会话正文」的唯一真相：落盘只是把它写回会话记录（见
 * [SessionController.persistCurrent]），因此**不要**在打开会话时用记录覆盖已有的列表。
 */
internal class SessionMessageStore {

    private val lists = HashMap<String, SnapshotStateList<ChatMessage>>()

    /** 该会话的消息列表；第一次访问时就地建一份空的。 */
    fun of(sessionId: String): SnapshotStateList<ChatMessage> =
        lists.getOrPut(sessionId) { mutableStateListOf() }

    /** 已经载入内存的会话及其列表；落盘时要把这些覆盖回各自的会话记录。 */
    fun loaded(): Map<String, SnapshotStateList<ChatMessage>> = lists

    /** 会话被真删时一并丢弃内存里那一份，避免 id 复用时读到旧内容。 */
    fun forget(sessionId: String) {
        lists.remove(sessionId)
    }
}
