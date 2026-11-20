package top.qxwkstudio.travel.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 本地照片文件的读写（应用私有目录 `filesDir/photos`）。
 *
 * 放在 data 层：清扫（[PhotoJanitor]）与 UI 都要用，不能让 data 反向依赖 ui。
 * 文件名由调用方给：编辑同一条记录时直接覆盖，不攒垃圾文件。
 */
object PhotoFiles {

    /** 最长边上限 2560px；JPEG 质量 85，兼顾清晰度与体积。 */
    private const val MAX_PX = 2560

    private const val DIR = "photos"

    @Volatile private var rootPath: String? = null

    /** 本次进程里刚写入的文件：清扫时一律跳过（系统时间跳变也不会误删）。 */
    private val recentFiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    /** Application 启动时调用一次：记录照片目录，load 时校验路径不越界。 */
    fun init(context: Context) {
        rootPath = runCatching { dir(context).canonicalPath }.getOrNull()
    }

    /** 从相册 URI 拷一张到私有目录，返回绝对路径；失败返回 null。 */
    fun save(context: Context, uri: Uri, name: String): String? {
        val resolver = context.contentResolver
        // 两段式解码：先只读尺寸算出 inSampleSize，再按需解码。
        // 之前直接 decodeStream，高像素原图会先展开一两百 MB，低内存机直接 OOM 成「照片失败」。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probed = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.isSuccess && bounds.outWidth > 0
        if (!probed) return null
        var sample = 1
        // 目标：解码结果不超过 MAX_PX（旧条件 `>=` 在 4000px 时会解到 4000，峰值上百 MB）
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PX) sample *= 2
        val bitmap = runCatching {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull() ?: return null

        // 相册图可能带 EXIF 旋转（竖拍横躺）：按 EXIF 转正后再缩放
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull().let { orientation ->
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        // 旋转与缩放合成一次矩阵变换：旧写法先建旋转图再缩放，中间位图会让峰值翻倍
        // 旋转 90/270 不改变最长边，所以比例可以直接按原图算
        val longest = maxOf(bitmap.width, bitmap.height)
        val ratio = if (longest > MAX_PX) MAX_PX.toFloat() / longest else 1f
        val scaled = if (rotation == 0f && ratio >= 1f) {
            bitmap
        } else {
            val matrix = android.graphics.Matrix().apply {
                postRotate(rotation)
                if (ratio < 1f) postScale(ratio, ratio)
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }
        val folder = dir(context).apply { mkdirs() }
        val file = File(folder, "$name.jpg")
        runCatching { file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            .onFailure {
                // 失败路径也要回收：这是几十 MB 的活对象，不能等 GC
                scaled.recycle()
                return null
            }
        if (scaled === bitmap) bitmap.recycle()
        recentFiles += file.absolutePath
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
            // 目标：解码结果尽量贴近 maxPx，但**不超过**它。
            // 旧条件 `max/(sample*2) >= maxPx` 在 2560px / maxPx=2048 时算出 sample=1，
            // 单张就占满 16MB 缓存（2560×1920 ARGB ≈ 19MB），翻页必重解码。
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
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
        // 刚写入不久的文件先别动：选图是「先落文件、后写库」，清扫恰好插在中间时
        // 这张新照片还不在引用集合里，按孤儿删掉就是丢图
        val protectAfter = System.currentTimeMillis() - RECENT_FILE_GUARD_MS
        dir(context).listFiles()?.forEach { file ->
            if (!file.isFile || file.absolutePath in referenced) return@forEach
            // 进程内刚写的（选图→写库之间）一定不删
            if (file.absolutePath in recentFiles) return@forEach
            if (file.lastModified() > protectAfter) return@forEach
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                count++
                bytes += size
            }
        }
        return count to bytes
    }

    /** 新文件保护窗口：5 分钟，足够覆盖「存图 → 写库 → 通知刷新」的整条链路。 */
    private const val RECENT_FILE_GUARD_MS = 5 * 60 * 1000L
}
