package com.zmf.agent.core.model

import android.net.Uri

/**
 * 一条从系统分享进入的内容（只承载数据，不含解析与落盘逻辑）。
 *
 * ## 为什么放在 core
 *
 * 它是「外部内容」的规范化结构：来源应用、标题、正文、图片地址、接收时间与去重签名。
 * 解析 Intent 的部分属于 feature（需要 Android 组件语义与工作区落盘），但结构本身会被
 * 界面状态、发送链路与日志多处引用，若放在 feature 就会迫使 app 层跨 feature 取类型；
 * 它又不读写文件、不弹界面，因此也不属于 infra 或 capability。按 `_Docs/模块化.md` §3
 * 的落位决策树第 3 条，纯数据落 core。
 *
 * ## 日志纪律
 *
 * 分享内容常含私人对话与代码片段。本类型不提供任何日志格式化方法：调用方写日志时
 * **只允许记录 MIME、字符数 / 字节数、图片张数与来源包名**，不得输出 [text] 正文。
 * 发送链路同样以「不记录正文」的方式写 `[chat]` 日志（见 `SendController` 的分享入口）。
 *
 * @param sourcePackage 发起分享的应用包名；系统未提供时为 null
 * @param title 发送方给出的标题或主题；仅用于日志与兜底，不写入消息正文
 * @param text 分享正文；发送方未提供时为 null
 * @param imageUris 图片地址（按发送顺序去重）；可来自 `ClipData` 与 `EXTRA_STREAM` 两处
 * @param fileUris 非图片的文件地址（按发送顺序去重）；与 [imageUris] 分开存放，图片仍走图片链路
 * @param mimeTypes 解析过程中出现过的 MIME（宿主类型与每个条目的类型），用于去重与日志
 * @param receivedAt 接收时刻的墙钟毫秒，用于去重窗口与面板展示
 * @param signature 去重签名，由 [signatureOf] 生成，不含正文原文
 */
data class SharePayload(
    val sourcePackage: String?,
    val title: String?,
    val text: String?,
    val imageUris: List<Uri>,
    val fileUris: List<Uri>,
    val mimeTypes: List<String>,
    val receivedAt: Long,
    val signature: String
) {

    /** 正文字符数（Kotlin `String.length`，即用户可见字符数，不按 UTF-8 字节计）。 */
    val textLength: Int get() = text?.length ?: 0

    /** 是否确实带来了可发送的内容（正文、图片或文件至少一项）。 */
    val hasContent: Boolean get() = textLength > 0 || imageUris.isNotEmpty() || fileUris.isNotEmpty()

    companion object {

        /**
         * 生成去重签名：来源包名 + MIME 集合 + 正文长度与哈希 + 图片与文件地址。
         *
         * 不含正文原文：签名会随日志与调试信息出现，写入正文即破坏隐私约定。
         * 正文用「长度 + `hashCode`」参与比对，同一段文字在进程内哈希稳定，足以识别
         * 「同一份内容被连点两次分享」这一唯一需要拦截的情形；真正的重复投递还会被
         * 2 秒窗口再次收窄（见 `ShareIntake.accept`）。
         */
        fun signatureOf(
            sourcePackage: String?,
            mimeTypes: List<String>,
            text: String?,
            imageUris: List<Uri>,
            fileUris: List<Uri>
        ): String = buildString {
            append(sourcePackage ?: "-").append('|')
            append(mimeTypes.distinct().sorted().joinToString(",")).append('|')
            append(text?.length ?: 0).append(':').append(text?.hashCode() ?: 0).append('|')
            append(imageUris.joinToString(",") { it.toString() }).append('|')
            append(fileUris.joinToString(",") { it.toString() })
        }
    }
}
