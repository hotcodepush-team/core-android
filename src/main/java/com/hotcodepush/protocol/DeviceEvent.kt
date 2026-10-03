package com.hotcodepush.protocol

import org.json.JSONArray
import org.json.JSONObject

/** An outcome event or check event, queued in the outbox until the events endpoint acknowledges it. */
data class DeviceEvent(
    val type: String,
    val releaseId: String? = null,
    val bundleId: String? = null,
    val status: String? = null,
    val reason: String? = null,
    val condition: ConditionType? = null,
    val bytes: Long? = null,
    val packKind: String? = null,
    val fromReleaseId: String? = null,
    val toReleaseId: String? = null,
    /** The app's `rollback({ reason })` on `REPORTED_BY_APP`: printable, at most 256 characters. */
    val detail: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .putIfNotNull("releaseId", releaseId)
        .putIfNotNull("bundleId", bundleId)
        .putIfNotNull("status", status)
        .putIfNotNull("reason", reason)
        .putIfNotNull("condition", condition?.wire)
        .putIfNotNull("bytes", bytes)
        .putIfNotNull("packKind", packKind)
        .putIfNotNull("fromReleaseId", fromReleaseId)
        .putIfNotNull("toReleaseId", toReleaseId)
        .putIfNotNull("detail", detail)

    companion object {
        fun checked(releaseId: String, status: SyncStatus, reason: SkippedReason? = null, condition: ConditionType? = null) =
            DeviceEvent("checked", releaseId = releaseId, status = status.wire, reason = reason?.name, condition = condition)

        fun downloaded(releaseId: String, bundleId: String, bytes: Long, packKind: PackKind) =
            DeviceEvent("downloaded", releaseId = releaseId, bundleId = bundleId, bytes = bytes, packKind = packKind.wire)

        fun applied(releaseId: String) = DeviceEvent("applied", releaseId = releaseId)

        fun confirmed(releaseId: String) = DeviceEvent("confirmed", releaseId = releaseId)

        fun failed(releaseId: String, reason: String, detail: String? = null) = DeviceEvent("failed", releaseId = releaseId, reason = reason, detail = detail)

        fun rolledBack(fromReleaseId: String, toReleaseId: String?) = DeviceEvent("rolledBack", fromReleaseId = fromReleaseId, toReleaseId = toReleaseId)

        fun fromJson(json: JSONObject) = DeviceEvent(
            type = json.getString("type"),
            releaseId = json.optNullableString("releaseId"),
            bundleId = json.optNullableString("bundleId"),
            status = json.optNullableString("status"),
            reason = json.optNullableString("reason"),
            condition = json.optNullableString("condition")?.let { wire -> ConditionType.entries.firstOrNull { it.wire == wire } },
            bytes = if (json.isNull("bytes")) null else json.optLong("bytes"),
            packKind = json.optNullableString("packKind"),
            fromReleaseId = json.optNullableString("fromReleaseId"),
            toReleaseId = json.optNullableString("toReleaseId"),
            detail = json.optNullableString("detail"),
        )
    }
}

/** The facts the device reports, sent when they differ from the acknowledged ones or the month began. */
data class DeviceReport(
    val attributes: Map<String, String>,
    val binaryBuild: String,
    val binaryVersion: String,
    val channelId: String,
    val channelSource: ChannelSource,
    val embeddedBundleId: String?,
    val fingerprint: String?,
    val osVersion: String,
    val releaseId: String?,
    /** The runtime version a bridge reports; this SDK has none. */
    val runtimeVersion: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("attributes", JSONObject(attributes))
        .put("binaryBuild", binaryBuild)
        .put("binaryVersion", binaryVersion)
        .put("channelId", channelId)
        .put("channelSource", channelSource.wire)
        .put("embeddedBundleId", embeddedBundleId ?: JSONObject.NULL)
        .put("fingerprint", fingerprint ?: JSONObject.NULL)
        .put("osVersion", osVersion)
        .put("releaseId", releaseId ?: JSONObject.NULL)
        .put("runtimeVersion", runtimeVersion ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = DeviceReport(
            attributes = json.getJSONObject("attributes").toStringMap(),
            binaryBuild = json.getString("binaryBuild"),
            binaryVersion = json.getString("binaryVersion"),
            channelId = json.getString("channelId"),
            channelSource = ChannelSource.entries.first { it.wire == json.getString("channelSource") },
            embeddedBundleId = json.optNullableString("embeddedBundleId"),
            fingerprint = json.optNullableString("fingerprint"),
            osVersion = json.getString("osVersion"),
            releaseId = json.optNullableString("releaseId"),
            runtimeVersion = json.optNullableString("runtimeVersion"),
        )
    }
}

/** One batch to `POST /v1/apps/{appId}/events`: the outbox and, when it changed, the report. */
data class DeviceEventsRequest(val deviceId: String, val events: List<DeviceEvent>, val platform: String, val report: DeviceReport?, val sdkVersion: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("deviceId", deviceId)
        .put("events", JSONArray(events.map { it.toJson() }))
        .put("platform", platform)
        .put("report", report?.toJson() ?: JSONObject.NULL)
        .put("sdkVersion", sdkVersion)
}

/** The `202`: the server time the device stores as `reportedAt`. */
data class DeviceEventsResponse(val reportedAt: Long) {
    companion object {
        fun fromJson(json: JSONObject) = DeviceEventsResponse(Iso8601.parse(json.getString("reportedAt")))
    }
}

internal fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
