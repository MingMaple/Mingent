package com.zmf.agent.capability.port

/** 图像生成结果；失败时 [code] 以 `E_` 开头。 */
sealed interface ImageResult {
    /** 成功；[bytes] 为解码后的图像字节，[mime] 为服务端返回的格式。 */
    data class Success(val bytes: ByteArray, val mime: String, val model: String) : ImageResult {
        // ByteArray 参与 data class 的相等判断需要显式实现，避免比较引用地址。
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && mime == other.mime &&
                model == other.model && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * (31 * mime.hashCode() + model.hashCode()) + bytes.contentHashCode()
    }

    /** 失败；[message] 面向用户，说明原因与下一步。 */
    data class Failure(val code: String, val message: String) : ImageResult
}

/**
 * 图像生成端口。
 *
 * 实现依据既有模型配置识别可用的图像模型；未识别到时返回 `E_IMAGE_PROVIDER_UNSET`。
 */
interface ImageGenerator {
    suspend fun generate(prompt: String, size: String, model: String?): ImageResult

    /** 是否已识别到可用的图像模型。 */
    fun available(): Boolean
}
