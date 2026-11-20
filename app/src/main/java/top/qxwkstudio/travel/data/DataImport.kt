package top.qxwkstudio.travel.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.data.db.SyncState
import top.qxwkstudio.travel.data.db.TripEntity
import top.qxwkstudio.travel.logic.Timeline
import androidx.room.withTransaction
import java.io.File
import java.util.zip.InflaterInputStream

/**
 * 导入 `.bak`：解密 → 解压 → 解析容器 → **按 UUID 合并、按 modifiedAt 覆盖**。
 *
 * 合并规则：
 *  - UUID 不存在 → 新增；
 *  - UUID 已存在且备份里的 `modifiedAt` 更新 → 覆盖该条，否则保留本地；
 *  - 只导入手动打卡，派生打卡由行程重算（避免同一条城市段出现两份）；
 *  - 照片拷进 `filesDir/photos`，并改写时间线 / 打卡里的路径。
 */
object DataImport {

    data class Report(
        val tripsAdded: Int,
        val tripsUpdated: Int,
        val checkinsAdded: Int,
        val checkinsUpdated: Int,
        val photos: Int,
    )

    suspend fun import(
        context: Context,
        db: AppDatabase,
        source: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Report {
        val tempDir = File(context.cacheDir, "import-${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            val (flags, decrypted) = BackupCrypto.open(source)
            val content = (if (flags and BackupCrypto.FLAG_DEFLATE != 0) InflaterInputStream(decrypted) else decrypted)
                .use { stream -> BackupContainer.read(stream, tempDir, onProgress) }

            // 照片落盘并建立「包内条目 → 新绝对路径」映射
            val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
            val entryToPath = HashMap<String, String>()
            content.photos.forEachIndexed { index, (entry, file) ->
                val dest = File(photoDir, "import_${System.currentTimeMillis()}_${index}_${entry.substringAfterLast('/')}")
                file.copyTo(dest, overwrite = true)
                entryToPath[entry] = dest.absolutePath
            }
            val photoMap = content.manifest.optJSONObject("photos") ?: JSONObject()
            fun rewrite(raw: String?): String? = raw
                ?.split(",")
                ?.mapNotNull { original ->
                    val entry = photoMap.optString(original).takeIf { it.isNotBlank() }
                    entry?.let { entryToPath[it] }
                }
                ?.joinToString(",")
                ?.ifBlank { null }

            var tripsAdded = 0
            var tripsUpdated = 0
            var checkinsAdded = 0
            var checkinsUpdated = 0
            db.withTransaction {
                val cityLookup = CityStore.all(context).associateBy { it.name }

                val tripsJson = content.manifest.optJSONArray("trips") ?: JSONArray()
                for (i in 0 until tripsJson.length()) {
                    val json = tripsJson.optJSONObject(i) ?: continue
                    val uuid = json.optString("uuid").takeIf { it.isNotBlank() } ?: continue
                    val importedModified = json.optLong("modifiedAt", 0L)
                    val decoded = Timeline.decode(json.optString("timeline")) ?: continue
                    val timeline = Timeline(decoded.items.map { it.copy(photoPath = rewrite(it.photoPath)) }).encode()
                    val existing = db.trips().byUuidAny(uuid)
                    if (existing == null) {
                        val entity = TripEntity(
                            uuid = uuid,
                            seqNo = db.trips().nextSeqNo(),
                            title = json.optString("title"),
                            note = json.optString("note").ifBlank { null },
                            timeline = timeline,
                            startTime = json.optLong("startTime"),
                            endTime = json.optLong("endTime"),
                            totalCostCents = json.optInt("totalCostCents"),
                            tripState = json.optInt("tripState", 2),
                            modifiedAt = importedModified,
                            syncState = SyncState.PENDING,
                        )
                        db.trips().insert(entity)
                        TripRepo.recomputeDerivedForSync(db, entity) { cityLookup[it] }
                        tripsAdded++
                    } else if (importedModified > existing.modifiedAt) {
                        val entity = existing.copy(
                            title = json.optString("title"),
                            note = json.optString("note").ifBlank { null },
                            timeline = timeline,
                            startTime = json.optLong("startTime"),
                            endTime = json.optLong("endTime"),
                            totalCostCents = json.optInt("totalCostCents"),
                            tripState = json.optInt("tripState", 2),
                            modifiedAt = importedModified,
                            syncState = SyncState.PENDING,
                        )
                        db.trips().update(entity)
                        TripRepo.recomputeDerivedForSync(db, entity) { cityLookup[it] }
                        tripsUpdated++
                    }
                }

                val checkinsJson = content.manifest.optJSONArray("checkins") ?: JSONArray()
                for (i in 0 until checkinsJson.length()) {
                    val json = checkinsJson.optJSONObject(i) ?: continue
                    // 派生打卡由行程重算，不导入
                    if (json.optInt("source", CheckinSource.MANUAL) != CheckinSource.MANUAL) continue
                    val uuid = json.optString("uuid").takeIf { it.isNotBlank() } ?: continue
                    val importedModified = json.optLong("modifiedAt", 0L)
                    val existing = db.checkins().byUuidAny(uuid)
                    val photoPath = rewrite(json.optString("photoPath"))
                    if (existing == null) {
                        db.checkins().insert(
                            CheckinEntity(
                                uuid = uuid,
                                cityName = json.optString("cityName"),
                                cityCode = json.optInt("cityCode", -1).takeIf { it > 0 },
                                province = json.optString("province"),
                                source = CheckinSource.MANUAL,
                                checkinAt = json.optLong("checkinAt"),
                                createdAt = json.optLong("createdAt"),
                                datePrecision = json.optInt("datePrecision", 2),
                                note = json.optString("note").ifBlank { null },
                                companions = json.optString("companions").ifBlank { null },
                                rating = json.optInt("rating", -1).takeIf { it > 0 },
                                photoPath = photoPath,
                                amountCents = json.optInt("amountCents", -1).takeIf { it >= 0 },
                                stayDays = json.optInt("stayDays", -1).takeIf { it > 0 },
                                seqNo = db.checkins().nextManualSeqNo(),
                                modifiedAt = importedModified,
                                syncState = SyncState.PENDING,
                            ),
                        )
                        checkinsAdded++
                    } else if (importedModified > existing.modifiedAt) {
                        db.checkins().update(
                            existing.copy(
                                cityName = json.optString("cityName"),
                                cityCode = json.optInt("cityCode", -1).takeIf { it > 0 },
                                province = json.optString("province"),
                                checkinAt = json.optLong("checkinAt"),
                                createdAt = json.optLong("createdAt", existing.createdAt),
                                datePrecision = json.optInt("datePrecision", existing.datePrecision),
                                note = json.optString("note").ifBlank { null },
                                companions = json.optString("companions").ifBlank { null },
                                rating = json.optInt("rating", -1).takeIf { it > 0 },
                                photoPath = photoPath,
                                amountCents = json.optInt("amountCents", -1).takeIf { it >= 0 },
                                stayDays = json.optInt("stayDays", -1).takeIf { it > 0 },
                                modifiedAt = importedModified,
                                syncState = SyncState.PENDING,
                            ),
                        )
                        checkinsUpdated++
                    }
                }
            }

            DataVersion.bump()
            SyncEvents.notifyChanged()
            Report(tripsAdded, tripsUpdated, checkinsAdded, checkinsUpdated, content.photos.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
