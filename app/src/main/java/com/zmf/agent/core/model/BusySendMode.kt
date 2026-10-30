package com.zmf.agent.core.model

/**
 * 繁忙时（AI 正在跑这一轮）用户又发了消息，这条消息怎么处理。
 *
 * 只留两种：排队等本轮结束，或者插进正在跑的那一轮。名字写在枚举上，
 * 界面、落盘、日志共用同一份文案，避免三处各写一个中文词。
 *
 * 放在 core.model 而不是设置页里：输入条、会话状态机、设置页三处都要用它，
 * 挂在设置页会让另外两处反向依赖那个 feature。
 */
enum class BusySendMode(val label: String) {
    QUEUE("排队发送"),
    STEER("插话发送");

    companion object {
        /** 存的是枚举名；xml 被改坏或来自旧备份时回落 QUEUE。 */
        fun fromName(name: String?): BusySendMode = entries.firstOrNull { it.name == name } ?: QUEUE
    }
}
