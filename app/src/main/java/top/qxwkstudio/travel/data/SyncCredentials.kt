package top.qxwkstudio.travel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 可选同步凭证：账号 + 密码用 Android Keystore 的 AES/GCM 加密后保存。
 * 只用于 token 过期后的一键重新登录；退出登录会清除。
 */
internal object SyncCredentials {

    private const val SP_NAME = "sync_credentials"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_ALIAS = "city_footprint_sync_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun save(context: Context, account: String, password: String) {
        if (account.isBlank() || password.isBlank()) return
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal("$account\n$password".toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PAYLOAD, payload).commit()
        }
    }

    fun load(context: Context): Pair<String, String>? = runCatching {
        val payload = context.applicationContext
            .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAYLOAD, null) ?: return null
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        val plain = String(cipher.doFinal(encrypted), Charsets.UTF_8)
        val pair = plain.split("\n", limit = 2)
        if (pair.size != 2 || pair[0].isBlank() || pair[1].isBlank()) null else pair[0] to pair[1]
    }.getOrNull()

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PAYLOAD).commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
