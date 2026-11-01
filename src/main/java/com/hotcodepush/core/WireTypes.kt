package com.hotcodepush.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/** The channel's index for a platform: `/apps/{appId}/channels/{channelId}/{platform}/v1/index.json`. */
data class ChannelIndex(
    val schema: Int,
    /** The rebuild time in milliseconds, which the never-backwards rule compares. It exceeds a 32-bit integer. */
    val sequence: Long,
    val appId: String,
    val channelId: String,
    val platform: String,
    val pausedAt: Long?,
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
        .put("pausedAt", pausedAt?.let(Iso8601::format) ?: JSONObject.NULL)
        .put("cappedAt", cappedAt?.let(Iso8601::format) ?: JSONObject.NULL)
        .put("revokedReleaseIds", JSONArray(revokedReleaseIds))
        .put("releases", JSONArray(releases.map { it.toJson() }))

    companion object {
        const val SCHEMA = 1
        val PLATFORMS = listOf("android", "ios")

        /** Parses an index that carries every field, a nullable one as `null`. Another schema major or platform fails it. */
        fun fromJson(json: JSONObject): ChannelIndex {
            val schema = json.getWireInt("schema", minimum = 0)
            if (schema != SCHEMA) throw JSONException("The index has schema $schema, this reader reads $SCHEMA")
            val platform = json.getWireString("platform")
            if (platform !in PLATFORMS) throw JSONException("Not a platform: $platform")
            return ChannelIndex(
                schema = schema,
                sequence = json.getWireLong("sequence", minimum = 0),
                appId = json.getWireString("appId", WireRule.NON_EMPTY),
                channelId = json.getWireString("channelId", WireRule.NON_EMPTY),
                platform = platform,
                pausedAt = json.getNullableTimestamp("pausedAt"),
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
    val rolloutPercent: Int,
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
        .put("rolloutPercent", rolloutPercent)
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
            rolloutPercent = json.getWireInt("rolloutPercent", minimum = 0, maximum = 100),
            conditions = json.getJSONArray("conditions").map { Condition.fromJson(it) },
            bundleId = json.getWireString("bundleId", WireRule.IDENTIFIER),
            bundleVersion = json.getWireString("bundleVersion"),
            manifestUrl = json.getWireString("manifestUrl", WireRule.URL),
            manifestSha256 = json.getWireString("manifestSha256", WireRule.SHA256),
            sizeBytes = json.getWireLong("sizeBytes", minimum = 0),
        )
    }
}

enum class ConditionType(override val wire: String) : WireEnum {
    BINARY("binary"), FINGERPRINT("fingerprint"), OS("os"), ATTRIBUTE("attribute"), DEVICE("device");

    companion object : WireEnumCompanion<ConditionType>({ entries })
}

/** A condition of an index entry; a type this SDK does not know is kept and fails closed. */
sealed class Condition {
    data class Binary(val range: String) : Condition()
    data class Fingerprint(val fingerprint: String) : Condition()
    data class Os(val range: String) : Condition()
    data class Device(val hashedIds: List<String>) : Condition()
    data class Attribute(val key: String, val valueSha256: String) : Condition()
    data class Unknown(val wireType: String) : Condition()

    val type: ConditionType?
        get() = when (this) {
            is Binary -> ConditionType.BINARY
            is Fingerprint -> ConditionType.FINGERPRINT
            is Os -> ConditionType.OS
            is Device -> ConditionType.DEVICE
            is Attribute -> ConditionType.ATTRIBUTE
            is Unknown -> null
        }

    fun toJson(): JSONObject = when (this) {
        is Binary -> JSONObject().put("type", "binary").put("range", range)
        is Fingerprint -> JSONObject().put("type", "fingerprint").put("fingerprint", fingerprint)
        is Os -> JSONObject().put("type", "os").put("range", range)
        is Device -> JSONObject().put("type", "device").put("hashedIds", JSONArray(hashedIds))
        is Attribute -> JSONObject().put("type", "attribute").put("key", key).put("valueSha256", valueSha256)
        is Unknown -> JSONObject().put("type", wireType)
    }

    companion object {
        fun fromJson(json: JSONObject): Condition = when (val type = json.getWireString("type")) {
            "binary" -> Binary(json.getWireString("range", WireRule.NON_EMPTY))
            "fingerprint" -> Fingerprint(json.getWireString("fingerprint", WireRule.NON_EMPTY))
            "os" -> Os(json.getWireString("range", WireRule.NON_EMPTY))
            "device" -> Device(json.getJSONArray("hashedIds").toWireStringList(WireRule.SHA256))
            "attribute" -> Attribute(json.getWireString("key", WireRule.NON_EMPTY), json.getWireString("valueSha256", WireRule.SHA256))
            else -> Unknown(type)
        }
    }
}

/** The discoverable channels of the app, for switching by name at runtime. */
data class ChannelsIndex(val schema: Int, val channels: List<Entry>) {
    data class Entry(val id: String, val name: String)

    companion object {
        const val SCHEMA = 1

        /** Parses the channels index. Another schema major fails it, and so does one entry whose id is not a UUID or whose name is empty. */
        fun fromJson(json: JSONObject): ChannelsIndex {
            val schema = json.getWireInt("schema", minimum = 0)
            if (schema != SCHEMA) throw JSONException("The channels index has schema $schema, this reader reads $SCHEMA")
            return ChannelsIndex(schema, json.getJSONArray("channels").map { Entry(it.getWireString("id", WireRule.UUID), it.getWireString("name", WireRule.NON_EMPTY)) })
        }
    }
}

/** A signature as the envelope carries it: the key id of the signing key and the self-describing `<scheme>:<base64>` value. */
data class Signature(val keyId: String, val value: String) {
    fun toJson(): JSONObject = JSONObject().put("keyId", keyId).put("value", value)

    companion object {
        fun fromJson(json: JSONObject) = Signature(json.getWireString("keyId", WireRule.NON_EMPTY), json.getWireString("value", WireRule.SIGNATURE_VALUE))
    }
}

/**
 * The document at `/apps/{appId}/bundles/{bundleId}/manifest.json`: the signed manifest string, its signature and the
 * reserved encryption slot, with the server's unsigned facts beside them.
 * An envelope stored while bundles carried `patches` still parses; the key is not read.
 */
data class ManifestEnvelope(
    val bundleId: String,
    val createdAt: Long,
    val manifest: String,
    val signature: Signature?,
    val pack: Pack,
    val deltas: List<Delta>,
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

    fun decodeManifest(): BundleManifest = BundleManifest.fromJson(JSONObject(manifest))

    fun toJson(): JSONObject = JSONObject()
        .put("bundleId", bundleId)
        .put("createdAt", Iso8601.format(createdAt))
        .put("manifest", manifest)
        .put("signature", signature?.toJson() ?: JSONObject.NULL)
        .put("encryption", JSONObject.NULL)
        .put("pack", pack.toJson())
        .put("deltas", JSONArray(deltas.map { it.toJson() }))

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
            )
        }
    }
}

/**
 * The bundle manifest the CLI signs as canonical JSON before the upload.
 * A manifest stored while bundles carried `patches` still parses; the key is not read.
 */
data class BundleManifest(
    val appId: String,
    val bundleVersion: String,
    val files: List<File>,
    val fingerprint: String? = null,
    /** The key id of the signing key, `null` when unsigned. */
    val keyId: String? = null,
    val platforms: List<String>,
) {
    data class File(val path: String, val sha256: String, val sizeBytes: Long) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("sha256", sha256).put("sizeBytes", sizeBytes)

        companion object {
            fun fromJson(json: JSONObject) = File(json.getWireString("path", WireRule.RELATIVE_PATH), json.getWireString("sha256", WireRule.SHA256), json.getWireLong("sizeBytes", minimum = 0))
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("appId", appId)
        .put("bundleVersion", bundleVersion)
        .put("files", JSONArray(files.map { it.toJson() }))
        .put("fingerprint", fingerprint ?: JSONObject.NULL)
        .put("keyId", keyId ?: JSONObject.NULL)
        .put("platforms", JSONArray(platforms))

    /** Whether the manifest names the device's app and lists its platform, signed or not. */
    fun isForDevice(appId: String, platform: String): Boolean = this.appId == appId && platform in platforms

    companion object {
        fun fromJson(json: JSONObject) = BundleManifest(
            appId = json.getWireString("appId", WireRule.NON_EMPTY),
            bundleVersion = json.getWireString("bundleVersion"),
            files = json.getJSONArray("files").map(File::fromJson),
            fingerprint = json.getNullableWireString("fingerprint", WireRule.NON_EMPTY),
            keyId = json.getNullableWireString("keyId", WireRule.NON_EMPTY),
            platforms = json.getJSONArray("platforms").toWireStringList(WireRule.NON_EMPTY),
        )
    }
}

/** The manifest of the embedded bundle, read from the resource file. */
typealias EmbeddedBundleManifest = BundleManifest

// A value is checked before it names a file, a directory or a host, so nothing climbs out of its directory.
internal enum class WireRule {
    // A bundle id names a directory.
    IDENTIFIER,

    // A channel id names a path segment of the index URL.
    UUID,

    // A file hash names a file.
    SHA256,

    // Split on code units, so `..` cannot hide behind a combining mark.
    RELATIVE_PATH,
    NON_EMPTY,

    // The server stores the text a device reports as sent.
    PRINTABLE,
    URL,

    // The scheme, a colon and the base64 of the signature.
    SIGNATURE_VALUE,

    // Base64 in its one canonical spelling: padded, with no unused bits set.
    BASE64;

    // A case table: one check per rule.
    @Suppress("CyclomaticComplexMethod")
    fun accepts(value: String): Boolean = when (this) {
        IDENTIFIER -> identifierPattern.matches(value)
        UUID -> uuidPattern.matches(value)
        SHA256 -> sha256Pattern.matches(value)
        RELATIVE_PATH -> '\\' !in value && '\u0000' !in value && value.split('/').none { it.isEmpty() || it == "." || it == ".." }
        NON_EMPTY -> value.isNotEmpty()
        PRINTABLE -> value.isNotEmpty() && value.none { it.isControlCharacter() }
        URL -> runCatching { URI(value) }.getOrNull()?.let { it.scheme?.lowercase() in URL_SCHEMES && !it.host.isNullOrEmpty() } ?: false
        SIGNATURE_VALUE -> signatureValuePattern.matches(value)
        BASE64 -> value.isNotEmpty() && SignatureVerifier.decodeCanonicalBase64(value) != null
    }
}

private val URL_SCHEMES = setOf("http", "https")

// Unicode's `Cc` set: C0, DEL and C1. All sit in the Basic Multilingual Plane, so a check per UTF-16 unit finds every one.
internal fun Char.isControlCharacter(): Boolean = code <= 0x1F || code in 0x7F..0x9F
private val identifierPattern = Regex("[A-Za-z0-9_-]{1,64}")
private val uuidPattern = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
private val sha256Pattern = Regex("[0-9a-f]{64}")
private val signatureValuePattern = Regex("[a-z0-9_-]+:[A-Za-z0-9+/]+=*")

// Only a real string passes, never a number or a boolean that org.json would coerce.
internal fun JSONObject.getWireString(key: String, rule: WireRule? = null): String {
    val value = get(key) as? String ?: throw JSONException("$key is not a string")
    if (rule != null && !rule.accepts(value)) throw JSONException("Not a valid $key: $value")
    return value
}

internal fun parseJsonObject(body: ByteArray): JSONObject = JSONObject(String(body, Charsets.UTF_8))

/** An enum whose values the wire spells as `wire`. */
interface WireEnum {
    val wire: String
}

/** The companion of a [WireEnum]: it reads a value from its wire spelling. */
abstract class WireEnumCompanion<T : WireEnum>(private val entries: () -> List<T>) {
    /** `null` for a spelling this SDK does not know. */
    fun fromWire(value: String?): T? = entries().firstOrNull { it.wire == value }
}

internal fun <T : WireEnum> JSONObject.getWireEnum(key: String, companion: WireEnumCompanion<T>): T {
    val value = getWireString(key)
    return companion.fromWire(value) ?: throw JSONException("Not a valid $key: $value")
}

// The wire carries every field, so an absent key is a broken document, never an empty value.
internal fun JSONObject.getNullableWireString(key: String, rule: WireRule? = null): String? {
    if (!has(key)) throw JSONException("$key is absent; the wire carries it as null when there is none")
    return if (isNull(key)) null else getWireString(key, rule)
}

internal fun JSONObject.getNullableObject(key: String): JSONObject? {
    if (!has(key)) throw JSONException("$key is absent; the wire carries it as null when there is none")
    return if (isNull(key)) null else getJSONObject(key)
}

internal fun JSONObject.getWireBoolean(key: String): Boolean = get(key) as? Boolean ?: throw JSONException("$key is not a boolean")

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

internal fun JSONArray.toWireStringList(rule: WireRule? = null): List<String> = List(length()) { index ->
    val value = get(index) as? String ?: throw JSONException("An entry is not a string")
    if (rule != null && !rule.accepts(value)) throw JSONException("Not a valid entry: $value")
    value
}

internal fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> = this?.let { array -> List(array.length()) { transform(array.getJSONObject(it)) } } ?: emptyList()
