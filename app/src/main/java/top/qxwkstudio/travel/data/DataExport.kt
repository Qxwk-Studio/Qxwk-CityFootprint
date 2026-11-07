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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出全部用户数据（不含成就状态），格式带版本号，为以后「导入数据」预留。
 *
 * 结构：ZIP
 *  - data.json：trips / checkins / photos 映射（不含设置与成就）
 *  - photos/<文件名>：被行程或打卡引用的原图
 *
 * 照片在 JSON 里保留原始绝对路径，同时给出 photos 映射；导入时按映射改写到新目录。
 */
object DataExport {

    data class Result(
        val displayPath: String,
        val trips: Int,
        val checkins: Int,
        val photos: Int,
    )

    private const val FORMAT = "city-footprint-export"
    private const val VERSION = 1

    suspend fun export(context: Context, db: AppDatabase): Result {
        val trips = db.trips().list()
        val checkins = db.checkins().list()

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

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "city-footprint-$stamp.zip"
        val temp = File(context.cacheDir, name)
        val written = mutableSetOf<String>()

        val photoMap = JSONObject()
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            existingPhotos.forEach { file ->
                val entryName = "photos/${file.name}"
                if (written.add(entryName)) {
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
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
                        put(JSONObject()
                            .put("uuid", trip.uuid)
                            .put("seqNo", trip.seqNo)
                            .put("title", trip.title)
                            .put("note", trip.note)
                            .put("timeline", trip.timeline)
                            .put("startTime", trip.startTime)
                            .put("endTime", trip.endTime)
                            .put("totalCostCents", trip.totalCostCents)
                            .put("tripState", trip.tripState)
                            .put("modifiedAt", trip.modifiedAt))
                    }
                })
                .put("checkins", JSONArray().apply {
                    checkins.forEach { checkin ->
                        put(JSONObject()
                            .put("uuid", checkin.uuid)
                            .put("cityName", checkin.cityName)
                            .put("cityCode", checkin.cityCode)
                            .put("province", checkin.province)
                            .put("source", checkin.source)
                            .put("tripUuid", checkin.tripUuid)
                            .put("checkinAt", checkin.checkinAt)
                            .put("datePrecision", checkin.datePrecision)
                            .put("note", checkin.note)
                            .put("companions", checkin.companions)
                            .put("rating", checkin.rating)
                            .put("photoPath", checkin.photoPath)
                            .put("segmentIndex", checkin.segmentIndex)
                            .put("amountCents", checkin.amountCents)
                            .put("stayDays", checkin.stayDays)
                            .put("seqNo", checkin.seqNo)
                            .put("modifiedAt", checkin.modifiedAt))
                    }
                })

            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val display = publishToDownloads(context, temp, name)
        temp.delete()
        return Result(display, trips.size, checkins.size, existingPhotos.size)
    }

    private fun publishToDownloads(context: Context, source: File, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
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
