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
            changes += "trip" to buildChange("trip", trip.uuid, trip.deletedAt != null, trip.modifiedAt, tripJson(trip))
        }
        checkins.forEach { checkin ->
            val syncedAt = Prefs.syncSyncedAt("checkin", checkin.uuid)
            if (!SyncRules.shouldPush(checkin.modifiedAt, syncedAt)) return@forEach
            if (("checkin:" + checkin.uuid) in pendingKeys) return@forEach
            changes += "checkin" to buildChange(
                "checkin", checkin.uuid, checkin.deletedAt != null, checkin.modifiedAt, checkinJson(checkin),
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

    private fun buildChange(type: String, uuid: String, deleted: Boolean, modified: Long, data: JsonObject): JsonObject =
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

    suspend fun resolveWithLocal(context: Context, db: AppDatabase, conflict: SyncConflict): Boolean {
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
        var count = 0
        repeat(20) {
            val response = SyncApi.pull(server, token, cursor).getOrThrow()
            response.changes.forEach { change ->
                change.data?.let {
                    applyRemote(context, db, change.entityType, it)
                    count++
                }
                cursor = SyncRules.nextCursor(cursor, change.seq)
            }
            cursor = SyncRules.nextCursor(cursor, response.nextSince)
            Prefs.saveSyncCursor(context, cursor)
            if (!response.hasMore || response.changes.isEmpty()) return count
        }
        return count
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
        if (!force && existing != null && existing.modifiedAt > remoteModified) return
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
