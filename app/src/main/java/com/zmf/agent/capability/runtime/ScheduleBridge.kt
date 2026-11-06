package com.zmf.agent.capability.runtime

import com.zmf.agent.core.log.FileLog

/**
 * 助手创建定时任务时提交的原始规则（尚未落盘，也不含 id / nextAt）。
 *
 * 字段语义与定时任务域的 ScheduleTask 对齐，但不暴露该域的类型：
 * capability 层只认这份窄口数据，避免工具反向依赖 feature。
 */
data class ScheduleSpec(
    val title: String,
    val prompt: String,
    /** once / daily / every。 */
    val kind: String,
    val everyMinutes: Int = 0,
    /** once / daily 的 "HH:mm"；其他类型留空。 */
    val timeOfDay: String = "",
    /** once 的绝对触发时刻；in_minutes 换算后填入，其他类型为 0。 */
    val atMillis: Long = 0L,
    /** 结果追加到哪条会话；null 表示首次触发时新建一条。 */
    val targetSessionId: String? = null,
    /** 归属工作区；null 表示默认工作区。 */
    val workspaceId: String? = null
)

/** 定时任务的只读快照，供 schedule_list 与创建后的回执使用。 */
data class ScheduleInfo(
    val id: String,
    val title: String,
    val prompt: String,
    val kind: String,
    val everyMinutes: Int,
    val timeOfDay: String,
    val atMillis: Long,
    val targetSessionId: String?,
    val workspaceId: String?,
    val enabled: Boolean,
    val nextAt: Long,
    val lastRunAt: Long
)

/**
 * 定时任务 ↔ 会话状态的窄口。
 *
 * 为什么需要这一层：归档会话发生在聊天域，绑定关系存放在定时任务域，
 * 而助手工具（capability）同样要能归档会话。若由聊天域直接引用定时任务域，
 * 既违反「feature 之间不得互相引用」，也让工具无法复用同一套语义。
 *
 * 定时任务域在启动时注册实现；未注册时所有调用安全地降级为「没有绑定」，
 * 不抛异常：缺少注册只会少停用一个任务，不会让归档本身失败。
 */
object ScheduleBridge {

    /** 定时任务域需要提供的能力。 */
    interface Port {
        /** 是否有任务以该会话为目标（含已停用的任务）。 */
        fun affectsSession(sessionId: String): Boolean

        /** 停用并解绑以该会话为目标的任务，返回被处理的任务数。 */
        fun detachSession(sessionId: String): Int

        /** 按规则创建并排期；返回落盘后的快照。 */
        fun create(spec: ScheduleSpec): ScheduleInfo

        /** 列出全部任务（含已停用）。 */
        fun list(): List<ScheduleInfo>

        /** 删除一条并取消闹钟；不存在时返回 false。 */
        fun delete(id: String): Boolean
    }

    @Volatile
    private var bound: Port? = null

    fun install(port: Port?) {
        bound = port
        FileLog.log("schedule", if (port == null) "定时任务桥已注销" else "定时任务桥已绑定")
    }

    /** 是否有任务以该会话为目标；用于归档前判断是否需要提示。 */
    fun affectsSession(sessionId: String): Boolean {
        val port = bound ?: return false
        return runCatching { port.affectsSession(sessionId) }.getOrDefault(false)
    }

    /** 停用并解绑；返回被处理的任务数，没有注册时返回 0。 */
    fun detachSession(sessionId: String): Int {
        val port = bound ?: return 0
        return runCatching { port.detachSession(sessionId) }.getOrDefault(0)
    }

    /** 创建定时任务；未注册时返回 null。 */
    fun create(spec: ScheduleSpec): ScheduleInfo? {
        val port = bound ?: return null
        return runCatching { port.create(spec) }.getOrNull()
    }

    /** 列出定时任务；未注册时返回空表。 */
    fun list(): List<ScheduleInfo> {
        val port = bound ?: return emptyList()
        return runCatching { port.list() }.getOrDefault(emptyList())
    }

    /** 删除定时任务；未注册或不存在时返回 false。 */
    fun delete(id: String): Boolean {
        val port = bound ?: return false
        return runCatching { port.delete(id) }.getOrDefault(false)
    }
}
