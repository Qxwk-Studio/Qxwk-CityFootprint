package top.qxwkstudio.travel.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.logic.Timeline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出用户数据（行程 / 打卡 / 照片，不含成就与设置）。
 *
 * 两种格式：
 *  - [Format.ZIP]：`data.json` + `photos/`，可直接解压查看；
 *  - [Format.BAK]：自定义容器 [BackupContainer] 经 deflate 后由 [BackupCrypto] 加密，
 *    没有 ZIP 结构，任何客户端都能导入、用户无法拆包。
 */
object DataExport {

    /** 导出格式：ZIP 是可解压包；BAK 是加密备份，供导入使用。 */
    enum class Format { ZIP, BAK }

    data class Result(
        val displayPath: String,
        val trips: Int,
        val checkins: Int,
        val photos: Int,
    )

    private const val FORMAT = "city-footprint-export"
    private const val VERSION = 1

    /**
     * 导出数据；[onlyTripUuids] / [onlyCheckinIds] 非空时只导出这些（行程页多选导出用）。
     *
     * 过滤后照片也从「被导出的内容」里取，所以不会把没选中的照片一起打进包里。
     */
    suspend fun export(
        context: Context,
        db: AppDatabase,
        onlyTripUuids: Set<String>? = null,
        onlyCheckinIds: Set<Long>? = null,
        format: Format = Format.ZIP,
    ): Result {
        val trips = db.trips().list().filter { onlyTripUuids == null || it.uuid in onlyTripUuids }
        val checkins = db.checkins().list().filter { onlyCheckinIds == null || it.id in onlyCheckinIds }

        val photoPaths = LinkedHashSet<String>()
        trips.forEach { trip ->
            Timeline.decode(trip.timeline)?.items.orEmpty().forEach { item ->
                item.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(photoPaths::add)
            }
        }
        checkins.forEach { checkin ->
            checkin.photoPath?.split(",")?.filter { it.isNotBlank() }?.forEach(photoPaths::add)
        }
        val existingPhotos = photoPaths.mapNotNull { path -> File(path).takeIf { it.isFile } }
        // 先按照片实际大小预检，别把 cache 写满再失败
        if (existingPhotos.sumOf { it.length() } > BackupLimits.EXPORT_BYTES) {
            throw BackupTooLargeException()
        }

        val written = mutableSetOf<String>()
        val photoEntries = ArrayList<Pair<String, File>>()
        val photoMap = JSONObject()
        existingPhotos.forEach { file ->
            val entryName = "photos/${file.name}"
            if (written.add(entryName)) photoEntries += entryName to file
            photoMap.put(file.absolutePath, entryName)
        }

        val root = JSONObject()
            .put("type", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("photos", photoMap)
            .put("trips", JSONArray().apply {
                trips.forEach { trip ->
                    put(
                        JSONObject()
                            .put("uuid", trip.uuid)
                            .put("title", trip.title)
                            .put("note", trip.note)
                            .put("timeline", trip.timeline)
                            .put("startTime", trip.startTime)
                            .put("endTime", trip.endTime)
                            .put("totalCostCents", trip.totalCostCents)
                            .put("tripState", trip.tripState)
                            .put("modifiedAt", trip.modifiedAt),
                    )
                }
            })
            .put("checkins", JSONArray().apply {
                checkins.forEach { checkin ->
                    put(
                        JSONObject()
                            .put("uuid", checkin.uuid)
                            .put("cityName", checkin.cityName)
                            .put("cityCode", checkin.cityCode)
                            .put("province", checkin.province)
                            .put("source", checkin.source)
                            .put("tripUuid", checkin.tripUuid)
                            .put("checkinAt", checkin.checkinAt)
                            .put("createdAt", checkin.createdAt)
                            .put("datePrecision", checkin.datePrecision)
                            .put("note", checkin.note)
                            .put("companions", checkin.companions)
                            .put("rating", checkin.rating)
                            .put("photoPath", checkin.photoPath)
                            .put("segmentIndex", checkin.segmentIndex)
                            .put("amountCents", checkin.amountCents)
                            .put("stayDays", checkin.stayDays)
                            .put("modifiedAt", checkin.modifiedAt),
                    )
                }
            })

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val extension = if (format == Format.BAK) "bak" else "zip"
        val name = "city-footprint-$stamp.$extension"
        val temp = File(context.cacheDir, name)

        if (format == Format.BAK) {
            BackupCrypto.encrypt(temp, BackupCrypto.FLAG_DEFLATE) { cipherOut ->
                DeflaterOutputStream(cipherOut).use { deflated ->
                    BackupContainer.write(deflated, root, photoEntries)
                }
            }
        } else {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                photoEntries.forEach { (entryName, file) ->
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        // 导出同样限大小：超过上限的包客户端自己也导不回来
        if (temp.length() > BackupLimits.EXPORT_BYTES) {
            temp.delete()
            throw BackupTooLargeException()
        }

        val mime = if (format == Format.BAK) "application/octet-stream" else "application/zip"
        return try {
            val display = publishToDownloads(context, temp, name, mime)
            Result(display, trips.size, checkins.size, existingPhotos.size)
        } finally {
            temp.delete()
        }
    }

    private fun publishToDownloads(context: Context, source: File, name: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("无法写入下载文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "下载/$name"
        }

        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val target = File(dir, name)
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.absolutePath
    }
}
