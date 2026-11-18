package top.qxwkstudio.travel.ui.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * 分享图的落盘与分享。
 *
 * 保存走 MediaStore 进相册（Pictures/CityFootprint，与查看器里的「保存」同一套口径），
 * 分享走 FileProvider + ACTION_SEND：临时 PNG 只写 cacheDir/share/，用完由系统清理。
 */
internal object ShareExporter {

    private const val ALBUM = "CityFootprint"

    fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.insertImage(context.contentResolver, bitmap, name, null) != null
        }
    }.getOrDefault(false)

    /** 把 PNG 写进 cacheDir/share/ 并返回可授予读权限的 content:// URI（IO 线程调用）。 */
    fun writeShare(context: Context, bitmap: Bitmap, name: String): Uri? = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull()

    /** 调起系统分享面板（必须在主线程）。 */
    fun launchShare(context: Context, uri: Uri, subject: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, subject))
        true
    }.getOrDefault(false)
}
