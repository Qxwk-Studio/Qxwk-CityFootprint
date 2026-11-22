package top.qxwkstudio.travel.data

import java.io.IOException

/**
 * 备份大小上限。
 *
 * 导入比导出低：GCM 解密会把整份明文驻留内存，再叠加 inflate 输出和 JSON 字符串，
 * 512MB 的包在常见 Android heap（256–512MB）上几乎必然 OOM。
 */
object BackupLimits {
    private const val MB = 1024L * 1024

    /**
     * 导出上限与导入一致：否则会导出自己都导不回来的包。
     * 等 .bak v2 分块 AEAD（真流式）落地后，导出可以放宽到 512MB。
     */
    const val EXPORT_BYTES = 256L * MB

    /** 导入上限：GCM 整块解密，按常见 Android heap 取 256MB。 */
    const val IMPORT_BYTES = 256L * MB

    fun mb(bytes: Long): Long = bytes / MB
}

/** 备份超过上限；用类型而不是 message 字符串跨层传递。 */
class BackupTooLargeException : IOException("备份文件超过大小上限")
