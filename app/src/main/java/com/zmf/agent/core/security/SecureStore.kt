package com.zmf.agent.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.zmf.agent.core.log.FileLog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 应用配置的本地加密存储。
 *
 * 密钥由 AndroidKeyStore 生成并保管，不导出、不备份；密文文件离开本机后无法解密，
 * 因此换机或系统备份恢复都会表现为「配置不存在」，需要用户重新填写。
 */
object SecureStore {

    private const val TAG = "secure"
    private const val ALIAS = "mingent_appdata_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val MAGIC = "MGK1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 加密写入；返回 false 表示密钥不可用或磁盘写入失败，调用方应放弃本次落盘。 */
    fun write(file: File, plain: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val bytes = ByteArray(MAGIC.length + iv.size + body.size)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        iv.copyInto(bytes, MAGIC.length)
        body.copyInto(bytes, MAGIC.length + iv.size)

        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
        true
    }.getOrElse { e ->
        FileLog.log(TAG, "加密写入失败：" + e)
        false
    }

    /**
     * 解密读取；返回 null 表示文件不存在、是旧版明文或当前密钥无法解密。
     * 与「真正的空内容」区分：后者同样返回 null，调用方按缺失处理即可。
     */
    fun read(file: File): String? = runCatching {
        if (!file.exists() || file.length() < MAGIC.length + IV_BYTES) return null
        val bytes = file.readBytes()
        val head = MAGIC.toByteArray(Charsets.US_ASCII)
        for (i in head.indices) {
            if (bytes[i] != head[i]) return null
        }
        val iv = bytes.copyOfRange(MAGIC.length, MAGIC.length + IV_BYTES)
        val body = bytes.copyOfRange(MAGIC.length + IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrElse { e ->
        FileLog.log(TAG, "解密读取失败：" + e)
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
