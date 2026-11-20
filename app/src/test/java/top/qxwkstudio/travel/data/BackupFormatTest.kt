package top.qxwkstudio.travel.data

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * `.bak` 格式的纯 JVM 回归：容器 round-trip、篡改必失败、非本应用文件被拒。
 * 这两个类不依赖 Android API，所以能直接跑在 CI 的 JVM 单测里。
 */
class BackupFormatTest {

    private fun photo(name: String, bytes: ByteArray, dir: File): File =
        File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun containerRoundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bak-test-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val manifest = JSONObject()
                .put("type", "city-footprint-export")
                .put("version", 1)
                .put("trips", 2)
            val bytes = ByteArray(2048) { (it % 251).toByte() }
            val source = photo("a.jpg", bytes, dir)
            val archive = File(dir, "out.bak")

            BackupCrypto.encrypt(archive, BackupCrypto.FLAG_DEFLATE) { cipherOut ->
                DeflaterOutputStream(cipherOut).use { deflated ->
                    BackupContainer.write(deflated, manifest, listOf("photos/a.jpg" to source))
                }
            }

            // 文件头就是约定的 magic，且不是可解压的 ZIP
            val head = archive.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            assertEquals("CFBK", String(head, Charsets.US_ASCII))

            val outDir = File(dir, "extract").apply { mkdirs() }
            val (flags, decrypted) = BackupCrypto.open(archive)
            assertEquals(BackupCrypto.FLAG_DEFLATE, flags and BackupCrypto.FLAG_DEFLATE)
            val content = InflaterInputStream(decrypted).use { stream ->
                BackupContainer.read(stream, outDir) { _, _ -> }
            }
            assertEquals(2, content.manifest.getInt("trips"))
            assertEquals(1, content.photos.size)
            assertEquals("photos/a.jpg", content.photos[0].first)
            assertArrayEquals(bytes, content.photos[0].second.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tamperedFileIsRejected() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bak-tamper-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val archive = File(dir, "out.bak")
            BackupCrypto.encrypt(archive, 0) { out ->
                out.write("payload".toByteArray())
            }
            // 改密文最后一个字节（GCM tag）：解密/读完必须失败
            val bytes = archive.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            archive.writeBytes(bytes)

            val failed = runCatching {
                val (_, decrypted) = BackupCrypto.open(archive)
                decrypted.use { it.readBytes() }
            }.isFailure
            assertTrue("篡改后的备份必须解密失败", failed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun foreignFileIsRejected() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bak-foreign-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val file = File(dir, "foreign.bak").apply { writeBytes(ByteArray(64) { 7 }) }
            val failed = runCatching { BackupCrypto.open(file) }.isFailure
            assertTrue("非本应用文件必须被拒绝", failed)
        } finally {
            dir.deleteRecursively()
        }
    }
}
