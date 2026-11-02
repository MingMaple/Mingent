package com.zmf.agent.core.model

/**
 * AI 正在执行当前轮次时用户再次发送消息，该消息的处理方式。
 *
 * 仅两种：排队等待本轮结束，或插入正在执行的轮次。名称定义在枚举上，
 * 界面、持久化与日志共用同一份文案，避免三处各自维护中文词。
 *
 * 置于 core.model 而非设置页：输入条、会话状态机与设置页均需使用，
 * 挂在设置页会使另外两处反向依赖该 feature。
 */
enum class BusySendMode(val label: String) {
    QUEUE("排队发送"),
    STEER("插话发送");

    companion object {
        /** 存储的是枚举名；XML 损坏或来自历史备份时回落 QUEUE。 */
        fun fromName(name: String?): BusySendMode = entries.firstOrNull { it.name == name } ?: QUEUE
    }
}
