package top.qxwkstudio.travel.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import top.qxwkstudio.travel.data.PhotoFiles

/**
 * UI 侧的照片入口：只是 [PhotoFiles] 的薄封装（真正的文件读写与清扫在 data 层）。
 *
 * 保留这一层是为了让各个页面继续写 `PhotoStore.save/load/delete`，不直接碰 data 的文件细节。
 */
internal object PhotoStore {

    fun save(context: Context, uri: Uri, name: String): String? = PhotoFiles.save(context, uri, name)

    fun load(path: String?, maxPx: Int = 0): Bitmap? = PhotoFiles.load(path, maxPx)

    fun cleanupUnreferenced(context: Context, referenced: Set<String>): Pair<Int, Long> =
        PhotoFiles.cleanupUnreferenced(context, referenced)
}
