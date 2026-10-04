package com.hotcodepush.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

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
        .put("releases", JSONArray(releases.map { it.toJson() }))

    companion object {
        const val SCHEMA = 1
        val PLATFORMS = listOf("android", "ios")

        /** Every field of the format is present, a nullable one as `null`; another schema major or platform fails the whole index. */
        fun fromJson(json: JSONObject): ChannelIndex {
            val schema = json.getWireInt("schema", minimum = 0)
            if (schema != SCHEMA) throw JSONException("The index has schema $schema, this reader reads $SCHEMA")
            val platform = json.getWireString("platform")
            if (platform !in PLATFORMS) throw JSONException("Not a platform: $platform")
            return ChannelIndex(
                schema = schema,
                sequence = json.getWireInt("sequence", minimum = 0),
                appId = json.getWireString("appId", WireRule.NON_EMPTY),
                channelId = json.getWireString("channelId", WireRule.NON_EMPTY),
                platform = platform,
                isPaused = json.getWireBoolean("isPaused"),
                cappedAt = json.getNullableTimestamp("cappedAt"),
                revokedReleaseIds = json.getJSONArray("revokedReleaseIds").toWireStringList(WireRule.IDENTIFIER),
                releases = json.getJSONArray("releases").map { IndexRelease.fromJson(it) },
            )
        }
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
            id = json.getWireString("id", WireRule.IDENTIFIER),
            number = json.getWireInt("number", minimum = 1),
            createdAt = json.getTimestamp("createdAt"),
            isMandatory = json.getWireBoolean("isMandatory"),
            notes = json.getNullableWireString("notes"),
            rollout = json.getWireInt("rollout", minimum = 0, maximum = 100),
            conditions = json.getJSONArray("conditions").map { Condition.fromJson(it) },
            bundleId = json.getWireString("bundleId", WireRule.IDENTIFIER),
            bundleVersion = json.getWireString("bundleVersion"),
            manifestUrl = json.getWireString("manifestUrl", WireRule.URL),
            manifestSha256 = json.getWireString("manifestSha256", WireRule.SHA256),
            sizeBytes = json.getWireLong("sizeBytes", minimum = 0),
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
        fun fromJson(json: JSONObject): Condition = when (val type = json.getWireString("type")) {
            "binary" -> Binary(json.getWireString("range", WireRule.NON_EMPTY))
            "runtime" -> Runtime(json.getWireString("version", WireRule.NON_EMPTY))
            "fingerprint" -> Fingerprint(json.getWireString("hash", WireRule.NON_EMPTY))
            "os" -> Os(json.getWireString("range", WireRule.NON_EMPTY))
            "device" -> Device(json.getJSONArray("hashedIds").toWireStringList())
            "attribute" -> Attribute(json.getWireString("key", WireRule.NON_EMPTY), json.getWireString("valueSha256", WireRule.SHA256))
            else -> Unknown(type)
        }
    }
}

/** The discoverable channels of the app, for switching by name at runtime. */
data class ChannelsIndex(val schema: Int, val channels: List<Entry>) {
    data class Entry(val id: String, val name: String)

    companion object {
        fun fromJson(json: JSONObject) = ChannelsIndex(
            schema = json.getWireInt("schema", minimum = 0),
            channels = json.getJSONArray("channels").map { Entry(it.getWireString("id"), it.getWireString("name")) },
        )
    }
}

/** A signature as the envelope carries it: the signing key's fingerprint and the self-describing `<scheme>:<base64>` value. */
data class Signature(val keyId: String, val value: String) {
    fun toJson(): JSONObject = JSONObject().put("keyId", keyId).put("value", value)

    companion object {
        fun fromJson(json: JSONObject) = Signature(json.getWireString("keyId", WireRule.NON_EMPTY), json.getWireString("value", WireRule.SIGNATURE_VALUE))
    }
}

/**
 * The document at `/apps/{appId}/bundles/{bundleId}/manifest.json`: the manifest as the signed string, its signature, the
 * reserved encryption slot and, unsigned beside them, the server's facts — the bundle's id and creation time, and the pack,
 * the deltas and the patches as stored.
 */
data class ManifestEnvelope(
    val bundleId: String,
    val createdAt: Long,
    val manifest: String,
    val signature: Signature?,
    val pack: Pack,
    val deltas: List<Delta>,
    val patches: List<Patch>,
) {
    data class Pack(val url: String, val sizeBytes: Long) {
        fun toJson(): JSONObject = JSONObject().put("url", url).put("sizeBytes", sizeBytes)

        companion object {
            fun fromJson(json: JSONObject) = Pack(json.getWireString("url", WireRule.URL), json.getWireLong("sizeBytes", minimum = 0))
        }
    }

    data class Delta(val baseBundleId: String, val url: String, val sizeBytes: Long) {
        fun toJson(): JSONObject = JSONObject().put("baseBundleId", baseBundleId).put("url", url).put("sizeBytes", sizeBytes)

        companion object {
            fun fromJson(json: JSONObject) = Delta(json.getWireString("baseBundleId", WireRule.IDENTIFIER), json.getWireString("url", WireRule.URL), json.getWireLong("sizeBytes", minimum = 0))
        }
    }

    /** A patch as stored: the manifest's entry with where its bytes are and how many. */
    data class Patch(val path: String, val fromSha256: String, val toSha256: String, val format: String, val url: String, val sizeBytes: Long) {
        fun toJson(): JSONObject = JSONObject()
            .put("path", path)
            .put("fromSha256", fromSha256)
            .put("toSha256", toSha256)
            .put("format", format)
            .put("url", url)
            .put("sizeBytes", sizeBytes)

        companion object {
            fun fromJson(json: JSONObject) = Patch(
                path = json.getWireString("path", WireRule.RELATIVE_PATH),
                fromSha256 = json.getWireString("fromSha256", WireRule.SHA256),
                toSha256 = json.getWireString("toSha256", WireRule.SHA256),
                format = json.getWireString("format", WireRule.NON_EMPTY),
                url = json.getWireString("url", WireRule.URL),
                sizeBytes = json.getWireLong("sizeBytes", minimum = 0),
            )
        }
    }

    fun decodeManifest(): BundleManifest = BundleManifest.fromJson(JSONObject(manifest))

    fun toJson(): JSONObject = JSONObject()
        .put("bundleId", bundleId)
        .put("createdAt", Iso8601.format(createdAt))
        .put("manifest", manifest)
        .put("signature", signature?.toJson() ?: JSONObject.NULL)
        .put("encryption", JSONObject.NULL)
        .put("pack", pack.toJson())
        .put("deltas", JSONArray(deltas.map { it.toJson() }))
        .put("patches", JSONArray(patches.map { it.toJson() }))

    companion object {
        fun fromJson(json: JSONObject): ManifestEnvelope {
            if (!json.has("encryption") || !json.isNull("encryption")) throw JSONException("The encryption slot is reserved and null")
            return ManifestEnvelope(
                bundleId = json.getWireString("bundleId", WireRule.IDENTIFIER),
                createdAt = json.getTimestamp("createdAt"),
                manifest = json.getWireString("manifest", WireRule.NON_EMPTY),
                signature = json.getNullableObject("signature")?.let(Signature::fromJson),
                pack = Pack.fromJson(json.getJSONObject("pack")),
                deltas = json.getJSONArray("deltas").map(Delta::fromJson),
                patches = json.getJSONArray("patches").map(Patch::fromJson),
            )
        }
    }
}

/**
 * The bundle manifest, the content the CLI knows before the upload and signs as canonical JSON: the files with their hashes
 * and sizes, the patches it computed, the platforms, the bundle version, the fingerprint and the signing key's id.
 */
data class BundleManifest(
    val appId: String,
    val bundleVersion: String,
    val files: List<File>,
    val fingerprint: String? = null,
    /** The fingerprint of the key that signed the manifest, `null` when unsigned. */
    val keyId: String? = null,
    val patches: List<Patch> = emptyList(),
    val platforms: List<String>,
) {
    data class File(val path: String, val sha256: String, val sizeBytes: Long) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("sha256", sha256).put("sizeBytes", sizeBytes)

        companion object {
            fun fromJson(json: JSONObject) = File(json.getWireString("path", WireRule.RELATIVE_PATH), json.getWireString("sha256", WireRule.SHA256), json.getWireLong("sizeBytes", minimum = 0))
        }
    }

    /** A patch the bundle offers: the file at `path` from the bytes of `fromSha256` to those of `toSha256`; a format the reader does not know means the full file. */
    data class Patch(val path: String, val fromSha256: String, val toSha256: String, val format: String) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("fromSha256", fromSha256).put("toSha256", toSha256).put("format", format)

        companion object {
            fun fromJson(json: JSONObject) = Patch(
                path = json.getWireString("path", WireRule.RELATIVE_PATH),
                fromSha256 = json.getWireString("fromSha256", WireRule.SHA256),
                toSha256 = json.getWireString("toSha256", WireRule.SHA256),
                format = json.getWireString("format", WireRule.NON_EMPTY),
            )
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("appId", appId)
        .put("bundleVersion", bundleVersion)
        .put("files", JSONArray(files.map { it.toJson() }))
        .put("fingerprint", fingerprint ?: JSONObject.NULL)
        .put("keyId", keyId ?: JSONObject.NULL)
        .put("patches", JSONArray(patches.map { it.toJson() }))
        .put("platforms", JSONArray(platforms))

    companion object {
        fun fromJson(json: JSONObject) = BundleManifest(
            appId = json.getWireString("appId", WireRule.NON_EMPTY),
            bundleVersion = json.getWireString("bundleVersion"),
            files = json.getJSONArray("files").map(File::fromJson),
            fingerprint = json.getNullableWireString("fingerprint", WireRule.NON_EMPTY),
            keyId = json.getNullableWireString("keyId", WireRule.NON_EMPTY),
            patches = json.getJSONArray("patches").map(Patch::fromJson),
            platforms = json.getJSONArray("platforms").toWireStringList(WireRule.NON_EMPTY),
        )
    }
}

/** The embedded bundle's manifest in the resource file: the bundle manifest without patches, since nothing is ever patched into the embedded bundle. */
data class EmbeddedBundleManifest(
    val appId: String,
    val bundleVersion: String,
    val files: List<BundleManifest.File>,
    val fingerprint: String? = null,
    val keyId: String? = null,
    val platforms: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("appId", appId)
        .put("bundleVersion", bundleVersion)
        .put("files", JSONArray(files.map { it.toJson() }))
        .put("fingerprint", fingerprint ?: JSONObject.NULL)
        .put("keyId", keyId ?: JSONObject.NULL)
        .put("platforms", JSONArray(platforms))

    companion object {
        fun fromJson(json: JSONObject) = EmbeddedBundleManifest(
            appId = json.getWireString("appId", WireRule.NON_EMPTY),
            bundleVersion = json.getWireString("bundleVersion"),
            files = json.getJSONArray("files").map(BundleManifest.File::fromJson),
            fingerprint = json.getNullableWireString("fingerprint", WireRule.NON_EMPTY),
            keyId = json.getNullableWireString("keyId", WireRule.NON_EMPTY),
            platforms = json.getJSONArray("platforms").toWireStringList(WireRule.NON_EMPTY),
        )
    }
}

/** What a value may hold before it names a file, a directory or a host: nothing that climbs out of its directory, nothing off the wire's format. */
internal enum class WireRule {
    /** Letters, digits, `_` and `-`, at most 64: a bundle id names a directory. */
    IDENTIFIER,

    /** 64 lowercase hexadecimal characters: a file hash names a file. */
    SHA256,

    /** Relative and `/`-separated, with no empty, `.` or `..` segment, no backslash and no NUL; split on code units, so `..` cannot hide behind a combining mark. */
    RELATIVE_PATH,

    /** At least one character. */
    NON_EMPTY,

    /** An absolute URL with a scheme and a host. */
    URL,

    /** The scheme, a colon and the base64 of the signature. */
    SIGNATURE_VALUE,

    /** Base64 in its one canonical spelling: padded, no unused bits set. */
    BASE64;

    fun accepts(value: String): Boolean = when (this) {
        IDENTIFIER -> identifierPattern.matches(value)
        SHA256 -> sha256Pattern.matches(value)
        RELATIVE_PATH -> '\\' !in value && '\u0000' !in value && value.split('/').none { it.isEmpty() || it == "." || it == ".." }
        NON_EMPTY -> value.isNotEmpty()
        URL -> runCatching { URI(value) }.getOrNull()?.let { !it.scheme.isNullOrEmpty() && !it.host.isNullOrEmpty() } ?: false
        SIGNATURE_VALUE -> signatureValuePattern.matches(value)
        BASE64 -> base64Pattern.matches(value)
    }
}

private val identifierPattern = Regex("[A-Za-z0-9_-]{1,64}")
private val sha256Pattern = Regex("[0-9a-f]{64}")
private val signatureValuePattern = Regex("[a-z0-9_-]+:[A-Za-z0-9+/]+=*")
private val base64Pattern = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{4}|[A-Za-z0-9+/][AQgw]==|[A-Za-z0-9+/]{2}[AEIMQUYcgkosw048]=)")

/** A string, never a number or a boolean read as one, that the rule accepts; anything else fails the decode before it can name a file, a directory or a host. */
internal fun JSONObject.getWireString(key: String, rule: WireRule? = null): String {
    val value = get(key) as? String ?: throw JSONException("$key is not a string")
    if (rule != null && !rule.accepts(value)) throw JSONException("Not a valid $key: $value")
    return value
}

/** A key that must be present, `null` when it holds nothing: the wire carries every field, so an absent one is a broken document, never an empty value. */
internal fun JSONObject.getNullableWireString(key: String, rule: WireRule? = null): String? {
    if (!has(key)) throw JSONException("$key is absent; the wire carries it as null when there is none")
    return if (isNull(key)) null else getWireString(key, rule)
}

internal fun JSONObject.getNullableObject(key: String): JSONObject? {
    if (!has(key)) throw JSONException("$key is absent; the wire carries it as null when there is none")
    return if (isNull(key)) null else getJSONObject(key)
}

internal fun JSONObject.getWireBoolean(key: String): Boolean = get(key) as? Boolean ?: throw JSONException("$key is not a boolean")

/** A whole number within its bounds; a fraction, a string or a number outside them fails the decode. */
internal fun JSONObject.getWireLong(key: String, minimum: Long, maximum: Long = Long.MAX_VALUE): Long {
    val value = when (val number = get(key)) {
        is Int -> number.toLong()
        is Long -> number
        else -> throw JSONException("$key is not a whole number")
    }
    if (value < minimum || value > maximum) throw JSONException("$key is out of bounds: $value")
    return value
}

internal fun JSONObject.getWireInt(key: String, minimum: Int, maximum: Int = Int.MAX_VALUE): Int = getWireLong(key, minimum.toLong(), maximum.toLong()).toInt()

internal fun JSONObject.getTimestamp(key: String): Long = parseTimestamp(getWireString(key))

internal fun JSONObject.getNullableTimestamp(key: String): Long? = getNullableWireString(key)?.let(::parseTimestamp)

private fun parseTimestamp(value: String): Long = runCatching { Iso8601.parse(value) }.getOrElse { throw JSONException("Not an ISO 8601 timestamp in UTC: $value") }

internal fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)

internal fun JSONArray?.toStringList(): List<String> = this?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()

/** The strings of the array, each one the rule accepts; a number or a boolean among them fails the decode. */
internal fun JSONArray.toWireStringList(rule: WireRule? = null): List<String> = List(length()) { index ->
    val value = get(index) as? String ?: throw JSONException("An entry is not a string")
    if (rule != null && !rule.accepts(value)) throw JSONException("Not a valid entry: $value")
    value
}

internal fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> = this?.let { array -> List(array.length()) { transform(array.getJSONObject(it)) } } ?: emptyList()
