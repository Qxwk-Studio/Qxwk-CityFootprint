package top.qxwkstudio.travel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 本地照片：打卡与足迹城市段各存一张，文件放在应用私有目录。
 *
 * 为什么落盘而不是存 URI：相册给的 content:// 是**临时授权**，进程重启或用户移动原图后就失效了；
 * 自己拷一份缩小后的 JPEG 才能长期显示，也顺手避免了「原图几千万像素」的内存问题。
 *
 * 文件名由调用方给（[save] 的 `name`）：编辑同一条记录时直接覆盖，不攒垃圾文件。
 */
internal object PhotoStore {

    /** 最长边上限：手机上展示的缩略图/大图都够用，文件也就百来 KB。 */
    private const val MAX_PX = 2560

    private const val DIR = "photos"

    /**
     * 从相册 URI 拷一张到私有目录，返回绝对路径；失败返回 null。
     *
     * 会先解码再缩放：直接扔原图的话，一张 4000×3000 的 JPEG 解码就是 48 MB 位图。
     */
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
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "$name.jpg")
        runCatching { file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            .onFailure { return null }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return file.absolutePath
    }

    /**
     * 读回图片；路径为空或文件没了返回 null。
     *
     * [maxPx] > 0 时按最长边限尺寸解码（列表缩略图用）：先读边界算 inSampleSize，
     * 避免为了 64dp 的缩略图把 1280px 的整张位图解出来。
     */
    fun load(path: String?, maxPx: Int = 0): Bitmap? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            if (maxPx <= 0) return@runCatching BitmapFactory.decodeFile(path)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /** 目录里所有照片文件。 */
    private fun dir(context: Context): File = File(context.filesDir, DIR)

    /**
     * 清理「没有任何打卡/行程引用」的照片，返回 (删除张数, 释放字节数)。
     *
     * [referenced] 由调用方收集（所有活着的打卡的 photo_path + 所有活着的行程时间线里的
     * segment.photoPath）。只删这个集合之外的文件：换图、删记录后留下的孤儿照片都能收掉。
     */
    fun cleanupUnreferenced(context: Context, referenced: Set<String>): Pair<Int, Long> {
        val folder = dir(context)
        if (!folder.isDirectory) return 0 to 0L
        var count = 0
        var bytes = 0L
        folder.listFiles()?.forEach { file ->
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

    /** 删掉一张旧图（换图时用），失败无所谓。 */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }
}
