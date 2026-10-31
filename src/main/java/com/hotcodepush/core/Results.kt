package com.hotcodepush.core

import org.json.JSONArray
import org.json.JSONObject

enum class SyncTrigger(val wire: String) { START("start"), RESUME("resume"), INTERVAL("interval"), MANUAL("manual") }

enum class SyncStatus(val wire: String) { UP_TO_DATE("up_to_date"), AVAILABLE("available"), DOWNLOADED("downloaded"), APPLIED("applied"), SKIPPED("skipped"), FAILED("failed") }

enum class SkippedReason(val wire: String) {
    BUILD_DEBUG("build_debug"), BUNDLE_FAILED_BEFORE("bundle_failed_before"), CHANNEL_PAUSED("channel_paused"),
    CONDITION_UNSUPPORTED("condition_unsupported"), CONNECTION_METERED("connection_metered"), DEVICE_INCOMPATIBLE("device_incompatible"),
    DEVICE_NOT_IN_ROLLOUT("device_not_in_rollout"), DEVICE_NOT_TARGETED("device_not_targeted"), RELEASE_OLDER_THAN_BINARY("release_older_than_binary"),
    RELEASE_REVOKED("release_revoked"), SPENDING_CAP_REACHED("spending_cap_reached")
}

enum class FailedReason(val wire: String) {
    CHANNEL_UNKNOWN("channel_unknown"), CONTENT_MISMATCHED("content_mismatched"), DEVICE_OFFLINE("device_offline"), DOWNLOAD_FAILED("download_failed"),
    INDEX_INVALID("index_invalid"), MANIFEST_INVALID("manifest_invalid"), SIGNATURE_INVALID("signature_invalid")
}

enum class RollbackReason(val wire: String) { APP_CRASHED("app_crashed"), APP_REQUESTED("app_requested"), READINESS_TIMED_OUT("readiness_timed_out") }

enum class PackKind(val wire: String) { FULL("full"), DELTA("delta"), STREAMED("streamed"), FILES("files") }

/**
 * One shape for `SyncResult`, `CheckForUpdateResult` and `DownloadUpdateResult`; the status says which fields are set.
 * `downloaded` carries `applyStrategy`, the moment the apply runs, or `manual` for the app's `applyUpdate()`. `applied` means
 * the apply happened and the reload follows.
 */
data class SyncResult(
    val status: SyncStatus,
    val release: Release?,
    val reason: String? = null,
    val condition: ConditionType? = null,
    val notes: String? = null,
    val applyStrategy: ApplyStrategy? = null,
    val downloadSizeBytes: Long? = null,
    val message: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", status.wire)
        .put("release", release?.toJson() ?: JSONObject.NULL)
        .putIfNotNull("reason", reason)
        .putIfNotNull("condition", condition?.wire)
        .putIfNotNull("notes", notes, nullWhenStatus = status == SyncStatus.APPLIED || status == SyncStatus.AVAILABLE || status == SyncStatus.DOWNLOADED)
        .putIfNotNull("applyStrategy", applyStrategy?.wire)
        .putIfNotNull("downloadSizeBytes", downloadSizeBytes, nullWhenStatus = status == SyncStatus.AVAILABLE)
        .putIfNotNull("message", message)

    companion object {
        fun upToDate(release: Release?) = SyncResult(SyncStatus.UP_TO_DATE, release)
        fun available(release: Release, notes: String?, downloadSizeBytes: Long?) = SyncResult(SyncStatus.AVAILABLE, release, notes = notes, downloadSizeBytes = downloadSizeBytes)
        fun downloaded(release: Release, notes: String?, applyStrategy: ApplyStrategy) = SyncResult(SyncStatus.DOWNLOADED, release, notes = notes, applyStrategy = applyStrategy)
        fun applied(release: Release, notes: String?) = SyncResult(SyncStatus.APPLIED, release, notes = notes)
        fun skipped(release: Release?, reason: SkippedReason, condition: ConditionType? = null) = SyncResult(SyncStatus.SKIPPED, release, reason = reason.wire, condition = condition)
        fun failed(release: Release?, reason: FailedReason, message: String) = SyncResult(SyncStatus.FAILED, release, reason = reason.wire, message = message)

        fun fromJson(json: JSONObject) = SyncResult(
            status = SyncStatus.entries.first { it.wire == json.getString("status") },
            release = json.optJSONObject("release")?.let(Release::fromJson),
            reason = json.optNullableString("reason"),
            condition = json.optNullableString("condition")?.let { wire -> ConditionType.entries.firstOrNull { it.wire == wire } },
            notes = json.optNullableString("notes"),
            applyStrategy = json.optNullableString("applyStrategy")?.let(ApplyStrategy::fromWire),
            downloadSizeBytes = if (json.isNull("downloadSizeBytes")) null else json.optLong("downloadSizeBytes"),
            message = json.optNullableString("message"),
        )
    }
}

enum class ApplyStatus(val wire: String) { APPLIED("applied"), NOTHING_TO_APPLY("nothing_to_apply") }

/** What `applyUpdate()` answers: the update is the current release and the reload follows, or nothing waits. */
data class ApplyUpdateResult(val status: ApplyStatus, val release: Release?) {
    fun toJson(): JSONObject = JSONObject().put("status", status.wire).put("release", release?.toJson() ?: JSONObject.NULL)
}

data class NotifyReadyResult(val currentRelease: Release?, val previousRelease: Release?, val isRolledBack: Boolean, val rollbackReason: RollbackReason?) {
    fun toJson(): JSONObject = JSONObject()
        .put("currentRelease", currentRelease?.toJson() ?: JSONObject.NULL)
        .put("previousRelease", previousRelease?.toJson() ?: JSONObject.NULL)
        .put("isRolledBack", isRolledBack)
        .putIfNotNull("rollbackReason", rollbackReason?.wire)
}

data class LastCheck(val checkedAt: Long, val trigger: SyncTrigger, val result: SyncResult) {
    fun toJson(): JSONObject = JSONObject().put("checkedAt", Iso8601.format(checkedAt)).put("trigger", trigger.wire).put("result", result.toJson())

    companion object {
        fun fromJson(json: JSONObject) = LastCheck(Iso8601.parse(json.getString("checkedAt")), SyncTrigger.entries.first { it.wire == json.getString("trigger") }, SyncResult.fromJson(json.getJSONObject("result")))
    }
}

/** The SDK's state, a snapshot: everything the debug screen shows. */
data class StateResult(
    val currentRelease: Release?,
    val nextRelease: Release?,
    val fallbackRelease: Release?,
    val embeddedBundleId: String?,
    val lastCheck: LastCheck?,
    val indexSequence: Long?,
    val indexFetchedAt: Long?,
    val failedBundleIds: List<String>,
    /** The server time of the first acknowledged device report in the latest UTC month that has one; the spending cap is compared with it. */
    val reportedAt: Long?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("currentRelease", currentRelease?.toJson() ?: JSONObject.NULL)
        .put("nextRelease", nextRelease?.toJson() ?: JSONObject.NULL)
        .put("fallbackRelease", fallbackRelease?.toJson() ?: JSONObject.NULL)
        .put("embeddedBundleId", embeddedBundleId ?: JSONObject.NULL)
        .put("lastCheck", lastCheck?.toJson() ?: JSONObject.NULL)
        .put("index", if (indexSequence != null && indexFetchedAt != null) JSONObject().put("sequence", indexSequence).put("fetchedAt", Iso8601.format(indexFetchedAt)) else JSONObject.NULL)
        .put("failedBundleIds", JSONArray(failedBundleIds))
        .put("reportedAt", reportedAt?.let(Iso8601::format) ?: JSONObject.NULL)
}

enum class ChannelSource(val wire: String) { RUNTIME("runtime"), CONFIG("config") }

/** `id` is `null` while no id is known: a build without a channel, or a runtime name no sync has resolved yet. */
data class ChannelResult(val id: String?, val name: String?, val source: ChannelSource) {
    fun toJson(): JSONObject = JSONObject().put("id", id ?: JSONObject.NULL).put("name", name ?: JSONObject.NULL).put("source", source.wire)
}

data class DeviceResult(
    val id: String,
    val platform: String,
    val binaryVersion: String,
    val binaryBuild: String,
    val osVersion: String,
    val sdkVersion: String,
    val fingerprint: String?,
    val channel: ChannelResult,
    val attributes: Map<String, String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("platform", platform)
        .put("binaryVersion", binaryVersion)
        .put("binaryBuild", binaryBuild)
        .put("osVersion", osVersion)
        .put("sdkVersion", sdkVersion)
        .put("fingerprint", fingerprint ?: JSONObject.NULL)
        .put("channel", channel.toJson())
        .put("attributes", JSONObject(attributes))
}

/** A check found a release the device qualifies for. */
data class UpdateAvailableEvent(val release: Release, val notes: String?, val downloadSizeBytes: Long?, val trigger: SyncTrigger) {
    fun toJson(): JSONObject = JSONObject()
        .put("release", release.toJson())
        .put("notes", notes ?: JSONObject.NULL)
        .put("downloadSizeBytes", downloadSizeBytes ?: JSONObject.NULL)
        .put("trigger", trigger.wire)
}

/** The download completed and the update waits for its apply: `applyStrategy` names the moment, or `manual` for the app's `applyUpdate()`. */
data class UpdateDownloadedEvent(val release: Release, val applyStrategy: ApplyStrategy, val trigger: SyncTrigger) {
    fun toJson(): JSONObject = JSONObject().put("release", release.toJson()).put("applyStrategy", applyStrategy.wire).put("trigger", trigger.wire)
}

/** A check or a download failed. */
data class UpdateFailedEvent(val release: Release?, val reason: FailedReason, val message: String, val trigger: SyncTrigger) {
    fun toJson(): JSONObject = JSONObject()
        .put("release", release?.toJson() ?: JSONObject.NULL)
        .put("reason", reason.wire)
        .put("message", message)
        .put("trigger", trigger.wire)
}

/** While a pack downloads: `progressBytes` of its `downloadSizeBytes` have arrived, `progress` their share from 0 to 1. */
data class DownloadProgressEvent(val downloadSizeBytes: Long, val progressBytes: Long, val releaseId: String) {
    val progress: Double get() = if (downloadSizeBytes > 0) progressBytes.toDouble() / downloadSizeBytes else 0.0

    fun toJson(): JSONObject = JSONObject()
        .put("downloadSizeBytes", downloadSizeBytes)
        .put("progress", progress)
        .put("progressBytes", progressBytes)
        .put("releaseId", releaseId)
}

/** Fired at each start after a rollback, before the readiness gate, until the app is up; `toRelease` is `null` for the embedded bundle. */

data class UpdateRolledBackEvent(val fromRelease: Release, val toRelease: Release?, val reason: RollbackReason) {
    fun toJson(): JSONObject = JSONObject().put("fromRelease", fromRelease.toJson()).put("toRelease", toRelease?.toJson() ?: JSONObject.NULL).put("reason", reason.wire)

    companion object {
        fun fromJson(json: JSONObject) = UpdateRolledBackEvent(Release.fromJson(json.getJSONObject("fromRelease")), json.optJSONObject("toRelease")?.let(Release::fromJson), RollbackReason.entries.first { it.wire == json.getString("reason") })
    }
}

internal fun JSONObject.putIfNotNull(key: String, value: Any?, nullWhenStatus: Boolean = false): JSONObject {
    if (value != null) put(key, value) else if (nullWhenStatus) put(key, JSONObject.NULL)
    return this
}
