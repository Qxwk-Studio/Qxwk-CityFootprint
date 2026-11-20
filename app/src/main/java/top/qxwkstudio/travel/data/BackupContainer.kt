package top.qxwkstudio.travel.data

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * `.bak` 的明文容器（自定义二进制，不是 ZIP）。
 *
 * ```
 * "CFP1" | payloadVersion(2B) | manifestLen(4B) | manifest(JSON)
 *        | photoCount(4B) | 逐张: nameLen(2B) + name + dataLen(4B) + 图片字节
 * ```
 * 外层再由 [BackupCrypto] 加密；因为没有 ZIP 目录结构，解压工具无法直接拆开。
 */
internal object BackupContainer {

    private const val MAGIC = "CFP1"
    private const val VERSION = 1

    fun write(out: OutputStream, manifest: JSONObject, photos: List<Pair<String, File>>) {
        val data = DataOutputStream(out)
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        data.write(MAGIC.toByteArray(Charsets.US_ASCII))
        data.writeShort(VERSION)
        data.writeInt(manifestBytes.size)
        data.write(manifestBytes)
        data.writeInt(photos.size)
        photos.forEach { (name, file) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            data.writeShort(nameBytes.size)
            data.write(nameBytes)
            data.writeInt(file.length().toInt())
            file.inputStream().use { it.copyTo(data, 64 * 1024) }
        }
        data.flush()
    }

    data class Content(val manifest: JSONObject, val photos: List<Pair<String, File>>)

    /** 读容器，照片落到 [dir]（photo-<i>.jpg），每张读完回调一次 [onProgress]。 */
    fun read(input: InputStream, dir: File, onProgress: (done: Int, total: Int) -> Unit): Content {
        val data = DataInputStream(input)
        val magic = ByteArray(4).also { data.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "备份内容格式不正确" }
        require(data.readUnsignedShort() == VERSION) { "备份内容版本不支持" }
        val manifestLen = data.readInt()
        require(manifestLen in 1..(16 * 1024 * 1024)) { "备份内容已损坏" }
        val manifestBytes = ByteArray(manifestLen).also { data.readFully(it) }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val count = data.readInt()
        require(count in 0..10_000) { "备份内容已损坏" }
        dir.mkdirs()
        val photos = ArrayList<Pair<String, File>>(count)
        for (index in 0 until count) {
            val nameLen = data.readUnsignedShort()
            val name = ByteArray(nameLen).also { data.readFully(it) }.toString(Charsets.UTF_8)
            val size = data.readInt()
            require(size in 0..(512 * 1024 * 1024)) { "备份内容已损坏" }
            val target = File(dir, "photo-$index.jpg")
            target.outputStream().use { out ->
                var remain = size.toLong()
                val buffer = ByteArray(64 * 1024)
                while (remain > 0) {
                    val read = data.read(buffer, 0, minOf(buffer.size.toLong(), remain).toInt())
                    require(read > 0) { "备份文件不完整" }
                    out.write(buffer, 0, read)
                    remain -= read
                }
            }
            photos += name to target
            onProgress(index + 1, count)
        }
        return Content(manifest, photos)
    }
}
