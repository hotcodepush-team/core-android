package com.hotcodepush.core

import org.json.JSONException
import org.json.JSONObject

/** When a downloaded update is applied. */
enum class InstallStrategy(val wire: String) {
    IMMEDIATE("immediate"), MANUAL("manual"), NEXT_RESUME("next-resume"), NEXT_START("next-start");

    companion object {
        fun fromWire(value: String?): InstallStrategy? = entries.firstOrNull { it.wire == value }
    }
}

/** When a mandatory update is applied; `next-start` is excluded, since it would make the flag mean nothing. */
enum class MandatoryInstallStrategy(val wire: String) {
    IMMEDIATE("immediate"), MANUAL("manual");

    companion object {
        fun fromWire(value: String?): MandatoryInstallStrategy? = entries.firstOrNull { it.wire == value }
    }
}

/** When an update the check found is downloaded; `manual` stops the cycle after the check. */
enum class DownloadStrategy(val wire: String) {
    AUTO("auto"), MANUAL("manual"), UNMETERED("unmetered");

    companion object {
        fun fromWire(value: String?): DownloadStrategy? = entries.firstOrNull { it.wire == value }
    }
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
    val autoCheck: Boolean,
    val checkInterval: Double,
    val downloadStrategy: DownloadStrategy,
    val installStrategy: InstallStrategy,
    val mandatoryInstallStrategy: MandatoryInstallStrategy,
    val installOnResumeAfter: Double,
    val readySignal: ReadySignal,
    val readyTimeout: Double,
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
            autoCheck = json.optBoolean("autoCheck", true),
            checkInterval = json.getCheckInterval(),
            downloadStrategy = DownloadStrategy.fromWire(json.optNullableString("downloadStrategy")) ?: DownloadStrategy.AUTO,
            installStrategy = InstallStrategy.fromWire(json.optNullableString("installStrategy")) ?: InstallStrategy.NEXT_START,
            mandatoryInstallStrategy = MandatoryInstallStrategy.fromWire(json.optNullableString("mandatoryInstallStrategy")) ?: MandatoryInstallStrategy.IMMEDIATE,
            installOnResumeAfter = json.optDouble("installOnResumeAfter", 300.0),
            readySignal = ReadySignal.fromWire(json.optNullableString("readySignal")) ?: ReadySignal.RENDER,
            readyTimeout = maxOf(1.0, json.optDouble("readyTimeout", 10.0)),
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

/** The seconds between automatic checks, at least the floor: a zero made the core check in a tight loop. */
private fun JSONObject.getCheckInterval(): Double {
    val seconds = optDouble("checkInterval", 900.0)
    if (seconds < MINIMUM_CHECK_INTERVAL) throw JSONException("checkInterval is below its floor of $MINIMUM_CHECK_INTERVAL seconds: $seconds")
    return seconds
}

private const val MINIMUM_CHECK_INTERVAL = 60.0

/** Each stage's strategy for one `sync()` call, overriding the configuration. */
data class SyncOptions(
    val downloadStrategy: DownloadStrategy? = null,
    val installStrategy: InstallStrategy? = null,
    val mandatoryInstallStrategy: MandatoryInstallStrategy? = null,
)
