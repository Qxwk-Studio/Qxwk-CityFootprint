package top.qxwkstudio.travel.data

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `.bak` 的加密外壳（AES-256-GCM），密钥随客户端走：任何安装都能导入，用户也拆不开。
 *
 * 文件结构（头是明文，且作为 GCM 的 AAD，改头即解密失败）：
 * ```
 * "CFBK" | version(1B) | flags(1B) | iv(12B) | GCM 密文
 * ```
 * `flags` bit0 = 明文经过 deflate（见 [FLAG_DEFLATE]）。
 *
 * 安全边界：这是「防用户拆包/改包」，不是防逆向。密钥常量在 APK 里，逆向可取；
 * 真要做到密码级保密必须让用户输入密码，而产品要求是「导出即可导入」，所以取前者。
 */
internal object BackupCrypto {

    const val FLAG_DEFLATE = 0x01

    private const val MAGIC = "CFBK"
    private const val VERSION = 1
    private const val IV_LEN = 12
    private const val HEADER_LEN = 4 + 1 + 1 + IV_LEN

    /** 应用内固定密钥：由两段常量拼接后做 SHA-256 派生，避免 APK 里直接出现可用密钥。 */
    private val key: SecretKey by lazy {
        val part1 = "CityFootprint/backup/2026"
        val part2 = "/v1/container/aes256"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((part1 + part2).toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    /** 加密写入 [target]；[write] 里写明文（调用方可自行包 deflate，并把对应 bit 放进 [flags]）。 */
    fun encrypt(target: File, flags: Int, write: (OutputStream) -> Unit) {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val header = header(flags, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(header)
        }
        FileOutputStream(target).buffered().use { raw ->
            raw.write(header)
            CipherOutputStream(raw, cipher).use(write)
        }
    }

    /** 打开备份：返回 (flags, 解密后的流)。调用方负责关闭，并自行按 flags 解压。 */
    fun open(source: File): Pair<Int, InputStream> {
        val raw = source.inputStream().buffered()
        val header = ByteArray(HEADER_LEN)
        var offset = 0
        while (offset < HEADER_LEN) {
            val read = raw.read(header, offset, HEADER_LEN - offset)
            require(read > 0) { "备份文件不完整" }
            offset += read
        }
        require(String(header, 0, 4, Charsets.US_ASCII) == MAGIC) { "不是本应用导出的备份" }
        val version = header[4].toInt()
        require(version == VERSION) { "备份版本不支持：$version" }
        val flags = header[5].toInt()
        val iv = header.copyOfRange(6, 6 + IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(header)
        }
        return flags to CipherInputStream(raw, cipher)
    }

    private fun header(flags: Int, iv: ByteArray): ByteArray {
        val header = ByteArray(HEADER_LEN)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[4] = VERSION.toByte()
        header[5] = flags.toByte()
        iv.copyInto(header, 6)
        return header
    }
}
