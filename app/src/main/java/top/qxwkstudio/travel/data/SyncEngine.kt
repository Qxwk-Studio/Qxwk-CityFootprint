package top.qxwkstudio.travel.data

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import top.qxwkstudio.travel.BuildConfig
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.CheckinEntity
import top.qxwkstudio.travel.data.db.CheckinSource
import top.qxwkstudio.travel.data.db.SyncState
import top.qxwkstudio.travel.data.db.TripEntity
import top.qxwkstudio.travel.logic.DatePrecision

/** 可选同步：只处理行程与手动打卡，成就/照片不参与。 */
internal object SyncEngine {

    data class Report(val pushed: Int, val pulled: Int, val conflicts: Int)

    private const val CHUNK = 80

    suspend fun sync(context: Context, db: AppDatabase, token: String): Report {
        if (Prefs.syncToken.isBlank()) error("未登录")
        val device = SettingsRepo.uuid(db)
        val server = Prefs.syncServer
        if (server.isBlank()) error("服务器地址为空")
        SyncApi.register(server, token, device, BuildConfig.VERSION_NAME).getOrThrow()

        var pulled = 0
        var pushed = 0
        var conflicts = 0
        val pending = Prefs.syncConflicts().toMutableList()
        val pendingKeys = pending.map { it.entityType + ":" + it.uuid }.toSet()

        // 先拉取远端变化，拿到服务端 revision 后再推本地增量
        pulled += pull(context, db, token, server)

        val trips = db.trips().allForSync()
        val checkins = db.checkins().manualAllForSync()
        val changes = mutableListOf<Pair<String, JsonObject>>()
        trips.forEach { trip ->
            val syncedAt = Prefs.syncSyncedAt("trip", trip.uuid)
            if (!SyncRules.shouldPush(trip.modifiedAt, syncedAt)) return@forEach
            if (("trip:" + trip.uuid) in pendingKeys) return@forEach
            changes += "trip" to buildChange("trip", trip.uuid, trip.deletedAt != null, tripJson(trip))
        }
        checkins.forEach { checkin ->
            val syncedAt = Prefs.syncSyncedAt("checkin", checkin.uuid)
            if (!SyncRules.shouldPush(checkin.modifiedAt, syncedAt)) return@forEach
            if (("checkin:" + checkin.uuid) in pendingKeys) return@forEach
            changes += "checkin" to buildChange(
                "checkin", checkin.uuid, checkin.deletedAt != null, checkinJson(checkin),
            )
        }

        changes.chunked(CHUNK).forEach { chunk ->
            val response = SyncApi.push(server, token, device, chunk.map { it.second }).getOrThrow()
            response.results.forEach { result ->
                val entity = chunk.firstOrNull { it.first == result.entityType && it.second["uuid"]?.jsonPrimitive?.contentOrNull == result.uuid }
                val modified = if (result.entityType == "trip") {
                    trips.firstOrNull { it.uuid == result.uuid }?.modifiedAt ?: 0L
                } else {
                    checkins.firstOrNull { it.uuid == result.uuid }?.modifiedAt ?: 0L
                }
                when {
                    SyncRules.isConflict(result.status) -> {
                        conflicts++
                        val local = entity?.second?.get("data")?.toString()
                        val remote = result.data?.toString()
                        if (remote != null) {
                            pending.removeAll { it.entityType == result.entityType && it.uuid == result.uuid }
                            pending += SyncConflict(
                                entityType = result.entityType,
                                uuid = result.uuid,
                                serverRevision = result.serverRevision,
                                localJson = local,
                                remoteJson = remote,
                            )
                        }
                    }
                    result.status == "applied" || result.status == "ignored" -> {
                        Prefs.saveSyncEntity(result.entityType, result.uuid, result.serverRevision, modified)
                        if (result.status == "applied") pushed++
                    }
                }
            }
        }

        Prefs.saveSyncConflicts(pending)

        // 推送后把自己产生的变更游标也推进，避免下次重复拉取
        pulled += pull(context, db, token, server)
        Prefs.saveSyncCursor(context, Prefs.syncCursor)
        return Report(pushed, pulled, conflicts)
    }

    private fun buildChange(type: String, uuid: String, deleted: Boolean, data: JsonObject): JsonObject =
        buildJsonObject {
            put("entity_type", type)
            put("uuid", uuid)
            put("op", if (deleted) "delete" else "upsert")
            put("revision", Prefs.syncRevision(type, uuid))
            if (!deleted) put("data", data)
        }

    private fun tripJson(trip: TripEntity): JsonObject = buildJsonObject {
        put("uuid", trip.uuid)
        put("title", trip.title)
        put("note", trip.note)
        put("timeline", trip.timeline)
        put("start_time", trip.startTime)
        put("end_time", trip.endTime)
        put("total_cost_cents", trip.totalCostCents)
        put("trip_state", trip.tripState)
        put("client_modified_at", trip.modifiedAt)
    }

    private fun checkinJson(checkin: CheckinEntity): JsonObject = buildJsonObject {
        put("uuid", checkin.uuid)
        put("city_name", checkin.cityName)
        put("city_code", checkin.cityCode)
        put("province", checkin.province)
        put("checkin_at", checkin.checkinAt)
        put("created_at", checkin.createdAt)
        put("date_precision", checkin.datePrecision)
        put("note", checkin.note)
        put("companions", checkin.companions)
        put("rating", checkin.rating)
        put("amount_cents", checkin.amountCents)
        put("stay_days", checkin.stayDays)
        put("client_modified_at", checkin.modifiedAt)
    }

    suspend fun resolveWithRemote(context: Context, db: AppDatabase, conflict: SyncConflict) {
        val remote = json.parseToJsonElement(conflict.remoteJson).jsonObject
        applyRemote(context, db, conflict.entityType, remote, force = true)
        Prefs.saveSyncConflicts(Prefs.syncConflicts().filterNot {
            it.entityType == conflict.entityType && it.uuid == conflict.uuid
        })
    }

    suspend fun resolveWithLocal(db: AppDatabase, conflict: SyncConflict): Boolean {
        val token = Prefs.syncToken
        val server = Prefs.syncServer
        if (token.isBlank() || server.isBlank()) return false
        val device = SettingsRepo.uuid(db)
        SyncApi.register(server, token, device, BuildConfig.VERSION_NAME).getOrThrow()
        val local = conflict.localJson?.let { json.parseToJsonElement(it).jsonObject }
        val change = buildJsonObject {
            put("entity_type", conflict.entityType)
            put("uuid", conflict.uuid)
            put("op", if (local == null) "delete" else "upsert")
            put("revision", conflict.serverRevision)
            if (local != null) put("data", local)
        }
        val response = SyncApi.push(server, token, device, listOf(change)).getOrThrow()
        val result = response.results.firstOrNull() ?: return false
        if (result.status == "conflict") {
            val remote = result.data ?: return false
            val updated = SyncConflict(
                entityType = conflict.entityType,
                uuid = conflict.uuid,
                serverRevision = result.serverRevision,
                localJson = conflict.localJson,
                remoteJson = remote.toString(),
            )
            Prefs.saveSyncConflicts(Prefs.syncConflicts().map {
                if (it.entityType == conflict.entityType && it.uuid == conflict.uuid) updated else it
            })
            return false
        }
        val modified = conflict.localJson?.let { json.parseToJsonElement(it).jsonObject["client_modified_at"]?.jsonPrimitive?.longOrNull } ?: 0L
        Prefs.saveSyncEntity(conflict.entityType, conflict.uuid, result.serverRevision, modified)
        Prefs.saveSyncConflicts(Prefs.syncConflicts().filterNot {
            it.entityType == conflict.entityType && it.uuid == conflict.uuid
        })
        return true
    }

    private suspend fun pull(context: Context, db: AppDatabase, token: String, server: String): Int {
        var cursor = Prefs.syncCursor
        // 上次有冲突跳过的条目：从最小 seq 之前重拉一遍（已解决的会补上，未解决的会再次记录）
        val skipped = Prefs.syncSkippedSeqs()
        if (skipped.isNotEmpty()) cursor = minOf(cursor, skipped.min() - 1).coerceAtLeast(0L)
        // 本轮仍被跳过的 seq：结束后只保留这些，已解决的不要再补拉
        val stillSkipped = mutableSetOf<Long>()
        var count = 0
        repeat(20) {
            val response = SyncApi.pull(server, token, cursor).getOrThrow()
            response.changes.forEach { change ->
                val data = change.data
                if (data != null) {
                    val uuid = data["uuid"]?.jsonPrimitive?.contentOrNull
                    val blocked = uuid != null && Prefs.syncConflicts().any {
                        it.entityType == change.entityType && it.uuid == uuid
                    }
                    if (blocked) {
                        // 冲突未决：游标可以继续推进，但要记住 seq，解决后补拉，否则这条的新版本永远收不到
                        stillSkipped += change.seq
                        Prefs.addSyncSkipped(context, change.seq)
                    } else {
                        applyRemote(context, db, change.entityType, data)
                        count++
                    }
                }
                cursor = SyncRules.nextCursor(cursor, change.seq)
            }
            cursor = SyncRules.nextCursor(cursor, response.nextSince)
            Prefs.saveSyncCursor(context, cursor)
            if (!response.hasMore || response.changes.isEmpty()) {
                replaceSkipped(context, stillSkipped)
                return count
            }
        }
        replaceSkipped(context, stillSkipped)
        return count
    }

    /** 用本轮实际仍被跳过的 seq 覆盖持久化集合。 */
    private fun replaceSkipped(context: Context, seqs: Set<Long>) {
        Prefs.clearSyncSkipped(context)
        seqs.forEach { Prefs.addSyncSkipped(context, it) }
    }

    /**
     * 导入后的 revision 对账：拉一遍远端元数据，把「内容与服务端一致」的本地记录
     * 标记成已同步（带上服务端 revision），避免导入后第一次同步逐条 revision 冲突。
     *
     * 不一致的记录不动：让后续 push 走冲突流程，由用户决定谁覆盖谁。
     */
    suspend fun reconcile(context: Context, db: AppDatabase): Int {
        val server = Prefs.syncServer
        val token = Prefs.syncToken
        if (server.isBlank() || token.isBlank()) return 0
        val remote = HashMap<String, Pair<Int, Long>>()
        var since = 0L
        var rounds = 0
        while (rounds++ < 50) {
            val response = SyncApi.pull(server, token, since).getOrThrow()
            response.changes.forEach { change ->
                val data = change.data ?: return@forEach
                val uuid = data["uuid"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val revision = data["revision"]?.jsonPrimitive?.intOrNull ?: 0
                val modified = data["client_modified_at"]?.jsonPrimitive?.longOrNull ?: 0L
                remote["${change.entityType}:$uuid"] = revision to modified
                since = SyncRules.nextCursor(since, change.seq)
            }
            since = SyncRules.nextCursor(since, response.nextSince)
            if (!response.hasMore || response.changes.isEmpty()) break
        }
        var matched = 0
        db.trips().allForSync().forEach { trip ->
            val entry = remote["trip:${trip.uuid}"] ?: return@forEach
            if (trip.modifiedAt == entry.second) {
                Prefs.saveSyncEntity("trip", trip.uuid, entry.first, entry.second)
                matched++
            }
        }
        db.checkins().allForSync().forEach { checkin ->
            val entry = remote["checkin:${checkin.uuid}"] ?: return@forEach
            if (checkin.modifiedAt == entry.second) {
                Prefs.saveSyncEntity("checkin", checkin.uuid, entry.first, entry.second)
                matched++
            }
        }
        return matched
    }

    private suspend fun applyRemote(context: Context, db: AppDatabase, type: String, data: JsonObject, force: Boolean = false) {
        if (!force && Prefs.syncConflicts().any {
                it.entityType == type && it.uuid == data["uuid"]?.jsonPrimitive?.contentOrNull
            }) return
        if (type == "trip") applyTrip(context, db, data, force) else if (type == "checkin") applyCheckin(db, data, force)
    }

    private suspend fun applyTrip(context: Context, db: AppDatabase, data: JsonObject, force: Boolean) {
        val uuid = data["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val existing = db.trips().byUuidAny(uuid)
        val remoteModified = data["client_modified_at"]?.jsonPrimitive?.longOrNull ?: 0L
        // 先拉后推时，不能用服务端旧快照覆盖本地尚未推送的修改。
        // 除了比较修改时间，还要看本地是否仍有未同步修改，避免两端时钟差导致误覆盖。
        if (!force && existing != null &&
            SyncRules.shouldPush(existing.modifiedAt, Prefs.syncSyncedAt("trip", uuid))) return
        if (!SyncRules.shouldApplyRemote(existing?.modifiedAt ?: 0L, remoteModified, force)) return
        val deletedAt = data["deleted_at"]?.jsonPrimitive?.longOrNull
        if (deletedAt != null) {
            existing?.let {
                db.trips().update(it.copy(deletedAt = deletedAt, modifiedAt = deletedAt, syncState = SyncState.SYNCED))
                db.checkins().softDeleteDerivedForTrip(uuid, deletedAt)
            }
            Prefs.saveSyncEntity("trip", uuid, data["revision"]?.jsonPrimitive?.intOrNull ?: 0, deletedAt)
            return
        }
        val timeline = data["timeline"]?.jsonPrimitive?.contentOrNull ?: return
        val modified = data["client_modified_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        val entity = existing?.copy(
            title = data["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            note = data["note"]?.jsonPrimitive?.contentOrNull,
            timeline = timeline,
            startTime = data["start_time"]?.jsonPrimitive?.longOrNull ?: 0L,
            endTime = data["end_time"]?.jsonPrimitive?.longOrNull ?: 0L,
            totalCostCents = data["total_cost_cents"]?.jsonPrimitive?.intOrNull ?: 0,
            tripState = data["trip_state"]?.jsonPrimitive?.intOrNull ?: 2,
            modifiedAt = modified,
            deletedAt = null,
            syncState = SyncState.SYNCED,
        ) ?: TripEntity(
            uuid = uuid,
            seqNo = db.trips().nextSeqNo(),
            title = data["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            note = data["note"]?.jsonPrimitive?.contentOrNull,
            timeline = timeline,
            startTime = data["start_time"]?.jsonPrimitive?.longOrNull ?: 0L,
            endTime = data["end_time"]?.jsonPrimitive?.longOrNull ?: 0L,
            totalCostCents = data["total_cost_cents"]?.jsonPrimitive?.intOrNull ?: 0,
            tripState = data["trip_state"]?.jsonPrimitive?.intOrNull ?: 2,
            modifiedAt = modified,
            syncState = SyncState.SYNCED,
        )
        if (existing == null) db.trips().insert(entity) else db.trips().update(entity)
        TripRepo.recomputeDerivedForSync(db, entity, { name ->
            CityStore.all(context).firstOrNull { it.name == name }
        })
        Prefs.saveSyncEntity("trip", uuid, data["revision"]?.jsonPrimitive?.intOrNull ?: Prefs.syncRevision("trip", uuid), modified)
    }

    private suspend fun applyCheckin(db: AppDatabase, data: JsonObject, force: Boolean) {
        val uuid = data["uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val existing = db.checkins().byUuidAny(uuid)
        val remoteModified = data["client_modified_at"]?.jsonPrimitive?.longOrNull ?: 0L
        // 与行程一致：本地还有没推上去的修改时，远端快照不能直接覆盖（否则多设备下静默丢编辑）
        if (!force && existing != null &&
            SyncRules.shouldPush(existing.modifiedAt, Prefs.syncSyncedAt("checkin", uuid))) return
        // 时间相等时也不要当「远端更新」
        if (!SyncRules.shouldApplyRemote(existing?.modifiedAt ?: 0L, remoteModified, force)) return
        val deletedAt = data["deleted_at"]?.jsonPrimitive?.longOrNull
        if (deletedAt != null) {
            existing?.let { db.checkins().update(it.copy(deletedAt = deletedAt, modifiedAt = deletedAt)) }
            Prefs.saveSyncEntity("checkin", uuid, data["revision"]?.jsonPrimitive?.intOrNull ?: 0, deletedAt)
            return
        }
        val modified = data["client_modified_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        val createdAt = data["created_at"]?.jsonPrimitive?.longOrNull ?: modified
        val entity = existing?.copy(
            cityName = data["city_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            cityCode = data["city_code"]?.jsonPrimitive?.intOrNull,
            province = data["province"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            checkinAt = data["checkin_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            createdAt = createdAt,
            datePrecision = data["date_precision"]?.jsonPrimitive?.intOrNull ?: DatePrecision.DAY,
            note = data["note"]?.jsonPrimitive?.contentOrNull,
            companions = data["companions"]?.jsonPrimitive?.contentOrNull,
            rating = data["rating"]?.jsonPrimitive?.intOrNull,
            amountCents = data["amount_cents"]?.jsonPrimitive?.intOrNull,
            stayDays = data["stay_days"]?.jsonPrimitive?.intOrNull,
            modifiedAt = modified,
            deletedAt = null,
            syncState = SyncState.SYNCED,
        ) ?: CheckinEntity(
            uuid = uuid,
            cityName = data["city_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            cityCode = data["city_code"]?.jsonPrimitive?.intOrNull,
            province = data["province"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            source = CheckinSource.MANUAL,
            checkinAt = data["checkin_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            createdAt = createdAt,
            datePrecision = data["date_precision"]?.jsonPrimitive?.intOrNull ?: DatePrecision.DAY,
            note = data["note"]?.jsonPrimitive?.contentOrNull,
            companions = data["companions"]?.jsonPrimitive?.contentOrNull,
            rating = data["rating"]?.jsonPrimitive?.intOrNull,
            amountCents = data["amount_cents"]?.jsonPrimitive?.intOrNull,
            stayDays = data["stay_days"]?.jsonPrimitive?.intOrNull,
            seqNo = db.checkins().nextManualSeqNo(),
            modifiedAt = modified,
            syncState = SyncState.SYNCED,
        )
        if (existing == null) db.checkins().insert(entity) else db.checkins().update(entity)
        Prefs.saveSyncEntity("checkin", uuid, data["revision"]?.jsonPrimitive?.intOrNull ?: Prefs.syncRevision("checkin", uuid), modified)
    }
}
