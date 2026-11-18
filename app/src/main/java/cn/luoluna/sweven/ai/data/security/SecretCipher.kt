package cn.luoluna.sweven.ai.data.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 版本化、带认证的字段密文；解密失败绝不回落为明文。 */
internal class SecretCipher(private val key: () -> SecretKey) {
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(stored: String): String {
        require(isEncrypted(stored)) { "密钥存储格式无效" }
        val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        require(bytes.size >= IV_BYTES + 16) { "密钥密文不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        private const val PREFIX = "flux-secret:v1:"
        private const val IV_BYTES = 12
        private val AAD = "flux-api-key-v1".toByteArray(Charsets.UTF_8)
        fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
    }
}
