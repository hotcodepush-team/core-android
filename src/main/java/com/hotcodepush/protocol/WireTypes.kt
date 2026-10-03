package com.hotcodepush.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The channel's index for a platform: `/apps/{appId}/channels/{channelId}/{platform}/v1/index.json`. */
data class ChannelIndex(
    val schema: Int,
    val sequence: Int,
    val appId: String,
    val channelId: String,
    val platform: String,
    val isPaused: Boolean,
    val cappedAt: Long?,
    val revokedReleaseIds: List<String>,
    val rollBackToEmbedded: RollBackToEmbedded?,
    val releases: List<IndexRelease>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", schema)
        .put("sequence", sequence)
        .put("appId", appId)
        .put("channelId", channelId)
        .put("platform", platform)
        .put("isPaused", isPaused)
        .put("cappedAt", cappedAt?.let(Iso8601::format) ?: JSONObject.NULL)
        .put("revokedReleaseIds", JSONArray(revokedReleaseIds))
        .put("rollBackToEmbedded", rollBackToEmbedded?.toJson() ?: JSONObject.NULL)
        .put("releases", JSONArray(releases.map { it.toJson() }))

    companion object {
        const val SCHEMA = 1

        fun fromJson(json: JSONObject): ChannelIndex = ChannelIndex(
            schema = json.getInt("schema"),
            sequence = json.getInt("sequence"),
            appId = json.getString("appId"),
            channelId = json.getString("channelId"),
            platform = json.getString("platform"),
            isPaused = json.optBoolean("isPaused", false),
            cappedAt = json.optNullableString("cappedAt")?.let(Iso8601::parse),
            revokedReleaseIds = json.optJSONArray("revokedReleaseIds").toStringList(),
            rollBackToEmbedded = json.optJSONObject("rollBackToEmbedded")?.let(RollBackToEmbedded::fromJson),
            releases = json.getJSONArray("releases").map { IndexRelease.fromJson(it) },
        )
    }
}

data class RollBackToEmbedded(val aboveNumber: Int, val signature: Signature?) {
    fun toJson(): JSONObject = JSONObject().put("aboveNumber", aboveNumber).put("signature", signature?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = RollBackToEmbedded(json.getInt("aboveNumber"), json.optJSONObject("signature")?.let(Signature::fromJson))
    }
}

data class IndexRelease(
    val id: String,
    val number: Int,
    val createdAt: Long,
    val isMandatory: Boolean,
    val notes: String?,
    val rollout: Int,
    val conditions: List<Condition>,
    val bundleId: String,
    val bundleVersion: String,
    val manifestUrl: String,
    val manifestSha256: String,
    val sizeBytes: Long,
) {
    val release: Release get() = Release(id, number, bundleId, bundleVersion, isMandatory)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("number", number)
        .put("createdAt", Iso8601.format(createdAt))
        .put("isMandatory", isMandatory)
        .put("notes", notes ?: JSONObject.NULL)
        .put("rollout", rollout)
        .put("conditions", JSONArray(conditions.map { it.toJson() }))
        .put("bundleId", bundleId)
        .put("bundleVersion", bundleVersion)
        .put("manifestUrl", manifestUrl)
        .put("manifestSha256", manifestSha256)
        .put("sizeBytes", sizeBytes)

    companion object {
        fun fromJson(json: JSONObject) = IndexRelease(
            id = json.getString("id", WireRule.IDENTIFIER),
            number = json.getInt("number"),
            createdAt = Iso8601.parse(json.getString("createdAt")),
            isMandatory = json.getBoolean("isMandatory"),
            notes = json.optNullableString("notes"),
            rollout = json.getInt("rollout"),
            conditions = json.getJSONArray("conditions").map { Condition.fromJson(it) },
            bundleId = json.getString("bundleId", WireRule.IDENTIFIER),
            bundleVersion = json.getString("bundleVersion"),
            manifestUrl = json.getString("manifestUrl"),
            manifestSha256 = json.getString("manifestSha256", WireRule.SHA256),
            sizeBytes = json.getLong("sizeBytes"),
        )
    }
}

enum class ConditionType(val wire: String) {
    BINARY("binary"), RUNTIME("runtime"), FINGERPRINT("fingerprint"), OS("os"), ATTRIBUTE("attribute"), DEVICE("device")
}

/** A condition of an index entry; a type this SDK does not know is kept and fails closed. */
sealed class Condition {
    data class Binary(val range: String) : Condition()
    data class Runtime(val version: String) : Condition()
    data class Fingerprint(val hash: String) : Condition()
    data class Os(val range: String) : Condition()
    data class Device(val hashedIds: List<String>) : Condition()
    data class Attribute(val key: String, val valueSha256: String) : Condition()
    data class Unknown(val wireType: String) : Condition()

    val type: ConditionType?
        get() = when (this) {
            is Binary -> ConditionType.BINARY
            is Runtime -> ConditionType.RUNTIME
            is Fingerprint -> ConditionType.FINGERPRINT
            is Os -> ConditionType.OS
            is Device -> ConditionType.DEVICE
            is Attribute -> ConditionType.ATTRIBUTE
            is Unknown -> null
        }

    fun toJson(): JSONObject = when (this) {
        is Binary -> JSONObject().put("type", "binary").put("range", range)
        is Runtime -> JSONObject().put("type", "runtime").put("version", version)
        is Fingerprint -> JSONObject().put("type", "fingerprint").put("hash", hash)
        is Os -> JSONObject().put("type", "os").put("range", range)
        is Device -> JSONObject().put("type", "device").put("hashedIds", JSONArray(hashedIds))
        is Attribute -> JSONObject().put("type", "attribute").put("key", key).put("valueSha256", valueSha256)
        is Unknown -> JSONObject().put("type", wireType)
    }

    companion object {
        fun fromJson(json: JSONObject): Condition = when (val type = json.getString("type")) {
            "binary" -> Binary(json.getString("range"))
            "runtime" -> Runtime(json.getString("version"))
            "fingerprint" -> Fingerprint(json.getString("hash"))
            "os" -> Os(json.getString("range"))
            "device" -> Device(json.getJSONArray("hashedIds").toStringList())
            "attribute" -> Attribute(json.getString("key"), json.getString("valueSha256"))
            else -> Unknown(type)
        }
    }
}

/** The discoverable channels of the app, for switching by name at runtime. */
data class ChannelsIndex(val schema: Int, val channels: List<Entry>) {
    data class Entry(val id: String, val name: String)

    companion object {
        fun fromJson(json: JSONObject) = ChannelsIndex(
            schema = json.getInt("schema"),
            channels = json.getJSONArray("channels").map { Entry(it.getString("id"), it.getString("name")) },
        )
    }
}

data class Signature(val keyId: String, val value: String) {
    fun toJson(): JSONObject = JSONObject().put("keyId", keyId).put("value", value)

    companion object {
        fun fromJson(json: JSONObject) = Signature(json.getString("keyId"), json.getString("value"))
    }
}

/** The envelope at `/apps/{appId}/bundles/{bundleId}/manifest.json`; the signature covers the `manifest` bytes. */
data class ManifestEnvelope(val manifest: String, val signature: Signature?) {
    fun decodeManifest(): BundleManifest = BundleManifest.fromJson(JSONObject(manifest))

    fun toJson(): JSONObject = JSONObject().put("manifest", manifest).put("signature", signature?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = ManifestEnvelope(json.getString("manifest"), json.optJSONObject("signature")?.let(Signature::fromJson))
    }
}

data class BundleManifest(
    val bundleId: String,
    val appId: String,
    val version: String,
    val createdAt: Long,
    val files: List<File>,
    val pack: Pack?,
    val deltas: List<Delta>,
) {
    data class File(val path: String, val sha256: String, val sizeBytes: Long) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("sha256", sha256).put("sizeBytes", sizeBytes)
    }

    data class Pack(val url: String, val sizeBytes: Long) {
        fun toJson(): JSONObject = JSONObject().put("url", url).put("sizeBytes", sizeBytes)
    }

    data class Delta(val baseBundleId: String, val url: String, val sizeBytes: Long) {
        fun toJson(): JSONObject = JSONObject().put("baseBundleId", baseBundleId).put("url", url).put("sizeBytes", sizeBytes)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("bundleId", bundleId)
        .put("appId", appId)
        .put("version", version)
        .put("createdAt", Iso8601.format(createdAt))
        .put("files", JSONArray(files.map { it.toJson() }))
        .put("pack", pack?.toJson() ?: JSONObject.NULL)
        .put("deltas", JSONArray(deltas.map { it.toJson() }))

    companion object {
        fun fromJson(json: JSONObject) = BundleManifest(
            bundleId = json.getString("bundleId", WireRule.IDENTIFIER),
            appId = json.getString("appId"),
            version = json.getString("version"),
            createdAt = Iso8601.parse(json.getString("createdAt")),
            files = json.getJSONArray("files").map { File(it.getString("path", WireRule.RELATIVE_PATH), it.getString("sha256", WireRule.SHA256), it.optLong("sizeBytes", 0)) },
            pack = json.optJSONObject("pack")?.let { Pack(it.getString("url"), it.optLong("sizeBytes", 0)) },
            deltas = json.optJSONArray("deltas").map { Delta(it.getString("baseBundleId"), it.getString("url"), it.optLong("sizeBytes", 0)) },
        )
    }
}

/** What a value may hold before it names a file or a directory: nothing that climbs out of its directory. */
internal enum class WireRule {
    /** Letters, digits, `_` and `-`, at most 64: a bundle id names a directory. */
    IDENTIFIER,

    /** 64 lowercase hexadecimal characters: a file hash names a file. */
    SHA256,

    /** Relative and `/`-separated, with no empty, `.` or `..` segment, no backslash and no NUL. */
    RELATIVE_PATH;

    fun accepts(value: String): Boolean = when (this) {
        IDENTIFIER -> identifierPattern.matches(value)
        SHA256 -> sha256Pattern.matches(value)
        RELATIVE_PATH -> '\\' !in value && '\u0000' !in value && value.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

private val identifierPattern = Regex("[A-Za-z0-9_-]{1,64}")
private val sha256Pattern = Regex("[0-9a-f]{64}")

/** A string the rule accepts; anything else fails the decode before it can name a file or a directory. */
internal fun JSONObject.getString(key: String, rule: WireRule): String = getString(key).also { if (!rule.accepts(it)) throw JSONException("Not a valid $key: $it") }

internal fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)

internal fun JSONArray?.toStringList(): List<String> = this?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()

internal fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> = this?.let { array -> List(array.length()) { transform(array.getJSONObject(it)) } } ?: emptyList()
