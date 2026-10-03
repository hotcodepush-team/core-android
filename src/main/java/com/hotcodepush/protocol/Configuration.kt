package com.hotcodepush.protocol

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
    val channelId: String,
    val autoCheck: Boolean,
    val checkInterval: Double,
    val downloadStrategy: DownloadStrategy,
    val installStrategy: InstallStrategy,
    val mandatoryInstallStrategy: MandatoryInstallStrategy,
    val installOnResumeAfter: Double,
    val readySignal: ReadySignal,
    val readyTimeout: Double,
    val enabledInDebugBuilds: Boolean,
    val publicKeys: List<String>,
    val builtAt: Long,
    val fingerprint: String?,
    val embeddedBundleManifest: BundleManifest,
    val embeddedBundleId: String?,
    val filesBaseUrl: String,
    val updatesBaseUrl: String,
) {
    companion object {
        const val DEFAULT_FILES_BASE_URL = "https://files.hotcodepush.com"
        const val DEFAULT_UPDATES_BASE_URL = "https://updates.hotcodepush.com"

        fun decode(text: String): Configuration = fromJson(JSONObject(text))

        fun fromJson(json: JSONObject) = Configuration(
            appId = json.getString("appId"),
            channelId = json.getString("channelId"),
            autoCheck = json.optBoolean("autoCheck", true),
            checkInterval = json.optDouble("checkInterval", 900.0),
            downloadStrategy = DownloadStrategy.fromWire(json.optNullableString("downloadStrategy")) ?: DownloadStrategy.AUTO,
            installStrategy = InstallStrategy.fromWire(json.optNullableString("installStrategy")) ?: InstallStrategy.NEXT_START,
            mandatoryInstallStrategy = MandatoryInstallStrategy.fromWire(json.optNullableString("mandatoryInstallStrategy")) ?: MandatoryInstallStrategy.IMMEDIATE,
            installOnResumeAfter = json.optDouble("installOnResumeAfter", 300.0),
            readySignal = ReadySignal.fromWire(json.optNullableString("readySignal")) ?: ReadySignal.RENDER,
            readyTimeout = maxOf(1.0, json.optDouble("readyTimeout", 10.0)),
            enabledInDebugBuilds = json.optBoolean("enabledInDebugBuilds", true),
            publicKeys = json.optJSONArray("publicKeys").toStringList(),
            builtAt = Iso8601.parse(json.getString("builtAt")),
            fingerprint = json.optNullableString("fingerprint"),
            embeddedBundleManifest = BundleManifest.fromJson(json.getJSONObject("embeddedBundleManifest")),
            embeddedBundleId = json.optNullableString("embeddedBundleId"),
            filesBaseUrl = json.optNullableString("filesBaseUrl") ?: DEFAULT_FILES_BASE_URL,
            updatesBaseUrl = json.optNullableString("updatesBaseUrl") ?: DEFAULT_UPDATES_BASE_URL,
        )
    }
}

/** Each stage's strategy for one `sync()` call, overriding the configuration. */
data class SyncOptions(
    val downloadStrategy: DownloadStrategy? = null,
    val installStrategy: InstallStrategy? = null,
    val mandatoryInstallStrategy: MandatoryInstallStrategy? = null,
)
