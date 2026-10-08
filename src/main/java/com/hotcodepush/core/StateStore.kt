package com.hotcodepush.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed class ChannelChoice {
    data class Id(val id: String) : ChannelChoice()
    data class Name(val name: String) : ChannelChoice()

    fun toJson(): JSONObject = when (this) {
        is Id -> JSONObject().put("id", id)
        is Name -> JSONObject().put("name", name)
    }

    companion object {
        fun fromJson(json: JSONObject): ChannelChoice = if (json.has("id")) Id(json.getString("id")) else Name(json.getString("name"))
    }
}

data class CachedIndex(val etag: String?, val fetchedAt: Long, val body: ChannelIndex) {
    fun toJson(): JSONObject = JSONObject().put("etag", etag ?: JSONObject.NULL).put("fetchedAt", Iso8601.format(fetchedAt)).put("body", body.toJson())

    companion object {
        fun fromJson(json: JSONObject) = CachedIndex(json.optNullableString("etag"), Iso8601.parse(json.getString("fetchedAt")), ChannelIndex.fromJson(json.getJSONObject("body")))
    }
}

data class LastRollback(val from: Release, val to: Release?, val reason: RollbackReason) {
    fun toJson(): JSONObject = JSONObject().put("from", from.toJson()).put("to", to?.toJson() ?: JSONObject.NULL).put("reason", reason.name)

    companion object {
        fun fromJson(json: JSONObject) = LastRollback(Release.fromJson(json.getJSONObject("from")), json.optJSONObject("to")?.let(Release::fromJson), RollbackReason.valueOf(json.getString("reason")))
    }
}

/**
 * The SDK's keys, `hotcodepush.<name>` each: three identity keys kept for the install's life,
 * the rest a cache under `stateVersion` that is dropped and rebuilt when unreadable.
 */
class StateStore(private val store: KeyValueStore) {
    init {
        if (store.getInt(PREFIX + "stateVersion") != STATE_VERSION) deleteCacheKeys()
    }

    val deviceId: String
        get() = getRaw("deviceId") ?: UUID.randomUUID().toString().lowercase().also { putRaw("deviceId", it) }

    var attributes: Map<String, String>
        get() = readObject("attributes") { it.toStringMap() } ?: emptyMap()
        set(value) = writeObject("attributes", JSONObject(value))

    var channel: ChannelChoice?
        get() = readObject("channel", ChannelChoice::fromJson)
        set(value) = writeObject("channel", value?.toJson())

    var currentRelease: Release?
        get() = readObject("currentRelease", Release::fromJson)
        set(value) = writeObject("currentRelease", value?.toJson())

    var nextRelease: Release?
        get() = readObject("nextRelease", Release::fromJson)
        set(value) = writeObject("nextRelease", value?.toJson())

    var fallbackRelease: Release?
        get() = readObject("fallbackRelease", Release::fromJson)
        set(value) = writeObject("fallbackRelease", value?.toJson())

    var failedBundleIds: List<String>
        get() = readArray("failedBundleIds") { it.toWireStringList() } ?: emptyList()
        set(value) = putRaw("failedBundleIds", JSONArray(value).toString())

    /** The floor of the binary that last started, to notice a new one. */
    var lastBuiltAt: Long?
        get() = readValue("lastBuiltAt", Iso8601::parse)
        set(value) = putRaw("lastBuiltAt", value?.let(Iso8601::format))

    var reportedAt: Long?
        get() = readValue("reportedAt", Iso8601::parse)
        set(value) = putRaw("reportedAt", value?.let(Iso8601::format))

    var acknowledgedReport: DeviceReport?
        get() = readObject("acknowledgedReport", DeviceReport::fromJson)
        set(value) = writeObject("acknowledgedReport", value?.toJson())

    var lastCheck: LastCheck?
        get() = readObject("lastCheck", LastCheck::fromJson)
        set(value) = writeObject("lastCheck", value?.toJson())

    var cachedIndex: CachedIndex?
        get() = readObject("cachedIndex", CachedIndex::fromJson)
        set(value) = writeObject("cachedIndex", value?.toJson())

    var unsentEvents: List<DeviceEvent>
        get() = readArray("unsentEvents") { array -> array.map(DeviceEvent::fromJson) } ?: emptyList()
        set(value) = putRaw("unsentEvents", JSONArray(value.map { it.toJson() }).toString())

    var checkedReleaseIds: List<String>
        get() = readArray("checkedReleaseIds") { it.toWireStringList() } ?: emptyList()
        set(value) = putRaw("checkedReleaseIds", JSONArray(value).toString())

    var lastRollback: LastRollback?
        get() = readObject("lastRollback", LastRollback::fromJson)
        set(value) = writeObject("lastRollback", value?.toJson())

    /** The `updateRolledBack` event the app has not come up after yet: announced at every start until it does. */
    var pendingRollbackEvent: UpdateRolledBackEvent?
        get() = readObject("pendingRollbackEvent", UpdateRolledBackEvent::fromJson)
        set(value) = writeObject("pendingRollbackEvent", value?.toJson())

    var lastSyncAt: Long?
        get() = readValue("lastSyncAt", Iso8601::parse)
        set(value) = putRaw("lastSyncAt", value?.let(Iso8601::format))

    /** Drops every cache key; the identity keys survive. Called at start on an unknown version, never by the app. */
    fun deleteCacheKeys() {
        CACHE_KEYS.forEach { putRaw(it, null) }
        store.putInt(PREFIX + "stateVersion", STATE_VERSION)
    }

    private fun <T> readObject(key: String, decode: (JSONObject) -> T): T? = readValue(key) { decode(JSONObject(it)) }

    private fun <T> readArray(key: String, decode: (JSONArray) -> T): T? = readValue(key) { decode(JSONArray(it)) }

    /**
     * The stored value, decoded: one that does not decode, whatever the reason — not JSON, a field missing, a value an older or
     * newer SDK wrote under the same `stateVersion` — drops the cache with it, or the identity key alone, and reads as absent.
     */
    private fun <T> readValue(key: String, decode: (String) -> T): T? {
        val raw = getRaw(key) ?: return null
        return try {
            decode(raw)
        } catch (exception: Exception) {
            if (key in CACHE_KEYS) deleteCacheKeys() else putRaw(key, null)
            null
        }
    }

    private fun writeObject(key: String, value: JSONObject?) = putRaw(key, value?.toString())

    private fun getRaw(key: String): String? = store.getString(PREFIX + key)

    private fun putRaw(key: String, value: String?) = store.putString(PREFIX + key, value)

    companion object {
        const val STATE_VERSION = 4
        const val PREFIX = "hotcodepush."
        private val CACHE_KEYS = listOf(
            "currentRelease", "nextRelease", "fallbackRelease", "failedBundleIds", "lastBuiltAt", "reportedAt", "acknowledgedReport",
            "lastCheck", "cachedIndex", "unsentEvents", "checkedReleaseIds", "lastRollback", "pendingRollbackEvent", "lastSyncAt",
        )
    }
}
