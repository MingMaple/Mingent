package com.zmf.agent.core.model

/**
 * 消息正文的展示模式：纯文本或 Markdown 源码。
 *
 * 选择文本页与复制行为共用同一份值；名称定义在枚举上，界面文案只取 [label]，
 * 避免各页各写一套「TXT／MD」。
 */
enum class TextMode(val label: String) {
    TXT("TXT"),
    MARKDOWN("MD");

    companion object {
        /** 存储的是枚举名；旧版本没有该键或值损坏时回落 TXT。 */
        fun fromName(name: String?): TextMode = entries.firstOrNull { it.name == name } ?: TXT
    }
}
