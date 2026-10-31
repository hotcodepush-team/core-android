package com.hotcodepush.core

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException

/** When a downloaded update is applied. */
enum class ApplyStrategy(val wire: String) {
    IMMEDIATE("immediate"), MANUAL("manual"), NEXT_RESUME("next_resume"), NEXT_START("next_start");

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

/** When a mandatory update is applied; `next_start` is excluded, since it would make the flag mean nothing. */
enum class MandatoryApplyStrategy(val wire: String) {
    IMMEDIATE("immediate"), MANUAL("manual");

    companion object {
        fun fromWire(value: String?): MandatoryApplyStrategy? = entries.firstOrNull { it.wire == value }
    }
}

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
    /** The channel the build follows; `null` when the build step ran without a token or offline. */
    val channelId: String?,
    val checkStrategy: CheckStrategy,
    val checkIntervalSeconds: Double,
    val downloadStrategy: DownloadStrategy,
    val applyStrategy: ApplyStrategy,
    val mandatoryApplyStrategy: MandatoryApplyStrategy,
    /** The least time in the background before a `next_resume` apply. */
    val nextResumeMinimumBackgroundSeconds: Double,
    val readySignal: ReadySignal,
    val readyTimeoutSeconds: Double,
    val isEnabledInDebugBuilds: Boolean,
    val publicKeys: List<DevicePublicKey>,
    val builtAt: Long,
    val fingerprint: String?,
    /** The embedded bundle's files; `null` in a build that bundled no JavaScript, which never updates. */
    val embeddedBundleManifest: EmbeddedBundleManifest?,
    val embeddedBundleId: String?,
    val filesBaseUrl: String,
    val updatesBaseUrl: String,
) {
    /**
     * Whether a URL starts with the files or updates base URL and a `/`.
     * So another scheme, userinfo, a look-alike host, another port or another path is off the host.
     */
    fun isOnConfiguredHost(url: String): Boolean = listOf(filesBaseUrl, updatesBaseUrl).any { url.startsWith("$it/") }

    companion object {
        const val DEFAULT_FILES_BASE_URL = "https://files.hotcodepush.com"
        const val DEFAULT_UPDATES_BASE_URL = "https://updates.hotcodepush.com"

        /** The message for an SDK that runs without a core because the assets lack the resource file. */
        const val MISSING_MESSAGE = "HotCodePush is not configured: hotcodepush.json is missing from the app's assets. Run `npx hotcodepush init` and build the app once."

        fun decode(text: String): Configuration = fromJson(JSONObject(text))

        fun fromJson(json: JSONObject) = Configuration(
            appId = json.getWireString("appId", WireRule.IDENTIFIER),
            channelId = json.getNullableWireString("channelId", WireRule.NON_EMPTY),
            checkStrategy = json.getOption("checkStrategy", CheckStrategy.AUTO) { json.getWireEnum(it, CheckStrategy::fromWire) },
            checkIntervalSeconds = json.getOption("checkIntervalSeconds", 900.0) { json.getSeconds(it, minimum = MINIMUM_CHECK_INTERVAL_SECONDS) },
            downloadStrategy = json.getOption("downloadStrategy", DownloadStrategy.AUTO) { json.getWireEnum(it, DownloadStrategy::fromWire) },
            applyStrategy = json.getOption("applyStrategy", ApplyStrategy.NEXT_START) { json.getWireEnum(it, ApplyStrategy::fromWire) },
            mandatoryApplyStrategy = json.getOption("mandatoryApplyStrategy", MandatoryApplyStrategy.IMMEDIATE) { json.getWireEnum(it, MandatoryApplyStrategy::fromWire) },
            nextResumeMinimumBackgroundSeconds = json.getOption("nextResumeMinimumBackgroundSeconds", 300.0) { json.getSeconds(it) },
            readySignal = json.getOption("readySignal", ReadySignal.RENDER) { json.getWireEnum(it, ReadySignal::fromWire) },
            readyTimeoutSeconds = json.getOption("readyTimeoutSeconds", 10.0) { json.getSeconds(it, minimum = MINIMUM_READY_TIMEOUT_SECONDS) },
            isEnabledInDebugBuilds = json.getOption("isEnabledInDebugBuilds", true, json::getWireBoolean),
            publicKeys = json.getOption("publicKeys", emptyList()) { json.getJSONArray(it).map(DevicePublicKey::fromJson) },
            builtAt = json.getTimestamp("builtAt"),
            fingerprint = json.getNullableWireString("fingerprint", WireRule.NON_EMPTY),
            embeddedBundleManifest = json.getNullableObject("embeddedBundleManifest")?.let(EmbeddedBundleManifest::fromJson),
            embeddedBundleId = json.getNullableWireString("embeddedBundleId", WireRule.IDENTIFIER),
            filesBaseUrl = json.getOption("filesBaseUrl", DEFAULT_FILES_BASE_URL) { json.getWireString(it, WireRule.URL) },
            updatesBaseUrl = json.getOption("updatesBaseUrl", DEFAULT_UPDATES_BASE_URL) { json.getWireString(it, WireRule.URL) },
        )

        /** Reads the resource file from the app's assets, `null` when it is missing. A file the reader refuses throws. */
        fun read(context: Context, assetPath: String = "hotcodepush.json"): Configuration? {
            val text = try {
                context.assets.open(assetPath).bufferedReader().use { it.readText() }
            } catch (exception: FileNotFoundException) {
                return null
            }
            return decode(text)
        }

        /** The message for an SDK that runs without a core because the reader refused the resource file. */
        fun resolveRefusedMessage(refusal: Exception): String =
            "HotCodePush is not configured: the app's hotcodepush.json was refused: ${refusal.message}. Check the project's hotcodepush.json and build the app again."
    }
}

// An SDK option the file leaves out takes its default; a null, a wrong type or an unknown value refuses the file.
private fun <T> JSONObject.getOption(key: String, default: T, read: (String) -> T): T = if (has(key)) read(key) else default

private fun <T> JSONObject.getWireEnum(key: String, fromWire: (String) -> T?): T {
    val value = getWireString(key)
    return fromWire(value) ?: throw JSONException("Not a valid $key: $value")
}

// A value below the floor refuses the file like any other schema violation, never clamped.
private fun JSONObject.getSeconds(key: String, minimum: Double = 0.0): Double {
    val seconds = (get(key) as? Number)?.toDouble() ?: throw JSONException("$key is not a number of seconds")
    if (seconds < minimum) throw JSONException("$key is below its floor of $minimum seconds: $seconds")
    return seconds
}

// A zero made the core check in a tight loop.
private const val MINIMUM_CHECK_INTERVAL_SECONDS = 60.0

// The readiness gate has no off switch.
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
