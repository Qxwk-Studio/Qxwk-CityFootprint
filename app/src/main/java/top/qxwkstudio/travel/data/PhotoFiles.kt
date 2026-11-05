package top.qxwkstudio.travel.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 本地照片文件的读写（应用私有目录 `filesDir/photos`）。
 *
 * 放在 data 层：清扫（[PhotoJanitor]）与 UI 都要用，不能让 data 反向依赖 ui。
 * 文件名由调用方给：编辑同一条记录时直接覆盖，不攒垃圾文件。
 */
object PhotoFiles {

    /** 最长边上限：手机上展示够用，文件也就百来 KB～1MB。 */
    private const val MAX_PX = 2560

    private const val DIR = "photos"

    @Volatile private var rootPath: String? = null

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    /** Application 启动时调用一次：记录照片目录，load 时校验路径不越界。 */
    fun init(context: Context) {
        rootPath = runCatching { dir(context).canonicalPath }.getOrNull()
    }

    /** 从相册 URI 拷一张到私有目录，返回绝对路径；失败返回 null。 */
    fun save(context: Context, uri: Uri, name: String): String? {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_PX) {
            val ratio = MAX_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val folder = dir(context).apply { mkdirs() }
        val file = File(folder, "$name.jpg")
        runCatching { file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            .onFailure { return null }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return file.absolutePath
    }

    /** 读回图片；[maxPx] > 0 时按最长边限尺寸解码（列表缩略图用）。 */
    private val cache = object : android.util.LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun load(path: String?, maxPx: Int = 0): Bitmap? {
        // 打卡可能存「逗号分隔的多张」：缩略图/详情只取第一张
        val first = path?.split(",")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        // 只允许读应用自己的照片目录，避免 DB 里出现任意路径
        val root = rootPath
        if (root != null && !File(first).canonicalPath.startsWith(root + File.separator)) return null
        val key = first + "@" + maxPx
        cache.get(key)?.let { return it }
        return runCatching {
            if (maxPx <= 0) return@runCatching BitmapFactory.decodeFile(first)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(first, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(first, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { cache.put(key, it) }
    }


    /** 目录里所有照片文件的绝对路径。 */
    fun list(context: Context): List<String> =
        dir(context).listFiles()?.filter { it.isFile }?.map { it.absolutePath }.orEmpty()

    /** 删除不在 [referenced] 里的文件，返回 (张数, 释放字节)。 */
    fun cleanupUnreferenced(context: Context, referenced: Set<String>): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        dir(context).listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in referenced) {
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    count++
                    bytes += size
                }
            }
        }
        return count to bytes
    }
}
