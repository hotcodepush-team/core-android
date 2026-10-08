package com.hotcodepush.core

import org.json.JSONException
import org.json.JSONObject

/** When a downloaded update is applied. */
enum class ApplyStrategy(val wire: String) {
    IMMEDIATE("immediate"), MANUAL("manual"), NEXT_RESUME("next-resume"), NEXT_START("next-start");

    companion object {
        fun fromWire(value: String?): ApplyStrategy? = entries.firstOrNull { it.wire == value }
    }
}

/** Whether the SDK checks on its own, at start, on resume and at the interval; `manual` leaves every cycle to the app's `sync()`. */
enum class CheckStrategy(val wire: String) {
    AUTO("auto"), MANUAL("manual");

    companion object {
        fun fromWire(value: String?): CheckStrategy? = entries.firstOrNull { it.wire == value }
    }
}

/** When an update the check found is downloaded; `manual` stops the cycle after the check. */
enum class DownloadStrategy(val wire: String) {
    AUTO("auto"), MANUAL("manual"), UNMETERED("unmetered");

    companion object {
        fun fromWire(value: String?): DownloadStrategy? = entries.firstOrNull { it.wire == value }
    }
}

/** When a mandatory update is applied; `next-start` is excluded, since it would make the flag mean nothing. */
enum class MandatoryApplyStrategy(val wire: String) {
    IMMEDIATE("immediate"), MANUAL("manual");

    companion object {
        fun fromWire(value: String?): MandatoryApplyStrategy? = entries.firstOrNull { it.wire == value }
    }
}

/** A mandatory strategy in the apply strategies' vocabulary, which holds both of its values. */
internal fun MandatoryApplyStrategy.toApplyStrategy(): ApplyStrategy = when (this) {
    MandatoryApplyStrategy.IMMEDIATE -> ApplyStrategy.IMMEDIATE
    MandatoryApplyStrategy.MANUAL -> ApplyStrategy.MANUAL
}

/** What ends the readiness gate: the first render, or an explicit `notifyReady()`. */
enum class ReadySignal(val wire: String) {
    MANUAL("manual"), RENDER("render");

    companion object {
        fun fromWire(value: String?): ReadySignal? = entries.firstOrNull { it.wire == value }
    }
}

/** The resource file: the project's `hotcodepush.json` with the channel resolved to its id, plus what only the embed step knows. */
data class Configuration(
    val appId: String,
    /** The channel the build follows; `null` in a build whose build step ran without a token or offline and never resolved the channel's name. */
    val channelId: String?,
    val checkStrategy: CheckStrategy,
    val checkIntervalSeconds: Double,
    val downloadStrategy: DownloadStrategy,
    val applyStrategy: ApplyStrategy,
    val mandatoryApplyStrategy: MandatoryApplyStrategy,
    /** The least time in the background before a `next-resume` apply. */
    val applyOnResumeAfterSeconds: Double,
    val readySignal: ReadySignal,
    val readyTimeoutSeconds: Double,
    val enabledInDebugBuilds: Boolean,
    val publicKeys: List<DevicePublicKey>,
    val builtAt: Long,
    val fingerprint: String?,
    /** The embedded bundle's files; `null` in a build that bundled no JavaScript, which embeds no bundle and never updates. */
    val embeddedBundleManifest: EmbeddedBundleManifest?,
    val embeddedBundleId: String?,
    val filesBaseUrl: String,
    val updatesBaseUrl: String,
) {
    /**
     * Whether a manifest, pack or delta URL lies under the files or the updates host: it starts with the base URL and a `/`,
     * so another scheme, userinfo, a look-alike host, another port or another path is off the host.
     */
    fun isUrlOnConfiguredHost(url: String): Boolean = listOf(filesBaseUrl, updatesBaseUrl).any { url.startsWith("$it/") }

    companion object {
        const val DEFAULT_FILES_BASE_URL = "https://files.hotcodepush.com"
        const val DEFAULT_UPDATES_BASE_URL = "https://updates.hotcodepush.com"

        fun decode(text: String): Configuration = fromJson(JSONObject(text))

        fun fromJson(json: JSONObject) = Configuration(
            appId = json.getWireString("appId", WireRule.IDENTIFIER),
            channelId = json.getNullableWireString("channelId", WireRule.NON_EMPTY),
            checkStrategy = CheckStrategy.fromWire(json.optNullableString("checkStrategy")) ?: CheckStrategy.AUTO,
            checkIntervalSeconds = json.getSeconds("checkIntervalSeconds", 900.0, minimum = MINIMUM_CHECK_INTERVAL_SECONDS),
            downloadStrategy = DownloadStrategy.fromWire(json.optNullableString("downloadStrategy")) ?: DownloadStrategy.AUTO,
            applyStrategy = ApplyStrategy.fromWire(json.optNullableString("applyStrategy")) ?: ApplyStrategy.NEXT_START,
            mandatoryApplyStrategy = MandatoryApplyStrategy.fromWire(json.optNullableString("mandatoryApplyStrategy")) ?: MandatoryApplyStrategy.IMMEDIATE,
            applyOnResumeAfterSeconds = json.getSeconds("applyOnResumeAfterSeconds", 300.0),
            readySignal = ReadySignal.fromWire(json.optNullableString("readySignal")) ?: ReadySignal.RENDER,
            readyTimeoutSeconds = json.getSeconds("readyTimeoutSeconds", 10.0, minimum = MINIMUM_READY_TIMEOUT_SECONDS),
            enabledInDebugBuilds = json.optBoolean("enabledInDebugBuilds", true),
            publicKeys = json.optJSONArray("publicKeys").map(DevicePublicKey::fromJson),
            builtAt = Iso8601.parse(json.getString("builtAt")),
            fingerprint = json.optNullableString("fingerprint"),
            embeddedBundleManifest = json.getNullableObject("embeddedBundleManifest")?.let(EmbeddedBundleManifest::fromJson),
            embeddedBundleId = json.getOptionalWireString("embeddedBundleId", WireRule.IDENTIFIER),
            filesBaseUrl = json.getOptionalWireString("filesBaseUrl", WireRule.URL) ?: DEFAULT_FILES_BASE_URL,
            updatesBaseUrl = json.getOptionalWireString("updatesBaseUrl", WireRule.URL) ?: DEFAULT_UPDATES_BASE_URL,
        )
    }
}

/**
 * A duration in seconds, the default when the file leaves it out: a number, never a string read as one, and at least its
 * floor, else the file is refused like any other schema violation, never clamped.
 */
private fun JSONObject.getSeconds(key: String, default: Double, minimum: Double = 0.0): Double {
    if (isNull(key)) return default
    val seconds = (get(key) as? Number)?.toDouble() ?: throw JSONException("$key is not a number of seconds")
    if (seconds < minimum) throw JSONException("$key is below its floor of $minimum seconds: $seconds")
    return seconds
}

/** The floor of `checkIntervalSeconds`: a zero made the core check in a tight loop. */
private const val MINIMUM_CHECK_INTERVAL_SECONDS = 60.0

/** The floor of `readyTimeoutSeconds`: the gate has no off switch. */
private const val MINIMUM_READY_TIMEOUT_SECONDS = 1.0

/** Each stage's strategy for one `sync()` call, overriding the configuration. */
data class SyncOptions(
    val applyStrategy: ApplyStrategy? = null,
    val downloadStrategy: DownloadStrategy? = null,
    val mandatoryApplyStrategy: MandatoryApplyStrategy? = null,
)

/** The apply strategies for one `downloadUpdate()` call; the download strategy is pinned to `auto`. */
data class DownloadUpdateOptions(
    val applyStrategy: ApplyStrategy? = null,
    val mandatoryApplyStrategy: MandatoryApplyStrategy? = null,
)
