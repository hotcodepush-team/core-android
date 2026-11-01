package com.hotcodepush.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** An outcome event or check event, queued in the outbox until the events endpoint acknowledges it. */
data class DeviceEvent(
    val type: String,
    val releaseId: String? = null,
    val status: String? = null,
    val reason: String? = null,
    val conditionType: ConditionType? = null,
    val downloadSizeBytes: Long? = null,
    val packKind: String? = null,
    /** The app's `rollback({ reason })` on `app_requested`: printable, at most 256 characters. */
    val detail: String? = null,
) {
    /** Encodes the keys of the event type as the protocol schema reads them; an optional key is left out when empty. */
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .putIfNotNull("releaseId", releaseId)
        .putIfNotNull("status", status)
        .putIfNotNull("reason", reason)
        .putIfNotNull("conditionType", conditionType?.wire)
        .putIfNotNull("downloadSizeBytes", downloadSizeBytes)
        .putIfNotNull("packKind", packKind)
        .putIfNotNull("detail", detail)

    companion object {
        fun checked(releaseId: String, status: SyncStatus, reason: SkippedReason? = null, conditionType: ConditionType? = null) =
            DeviceEvent("checked", releaseId = releaseId, status = status.wire, reason = reason?.wire, conditionType = conditionType)

        fun downloaded(releaseId: String, downloadSizeBytes: Long, packKind: PackKind) =
            DeviceEvent("downloaded", releaseId = releaseId, downloadSizeBytes = downloadSizeBytes, packKind = packKind.wire)

        fun applied(releaseId: String) = DeviceEvent("applied", releaseId = releaseId)

        fun confirmed(releaseId: String) = DeviceEvent("confirmed", releaseId = releaseId)

        fun failed(releaseId: String, reason: String, detail: String? = null) = DeviceEvent("failed", releaseId = releaseId, reason = reason, detail = detail)

        fun fromJson(json: JSONObject) = DeviceEvent(
            type = json.getString("type"),
            releaseId = json.optNullableString("releaseId"),
            status = json.optNullableString("status"),
            reason = json.optNullableString("reason"),
            conditionType = ConditionType.fromWire(json.optNullableString("conditionType")),
            downloadSizeBytes = if (json.isNull("downloadSizeBytes")) null else json.optLong("downloadSizeBytes"),
            packKind = json.optNullableString("packKind"),
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

    // A report the endpoint refuses would cost the batch its events at every sync, so the device never sends one.
    internal val isReadable: Boolean
        get() = listOfNotNull(binaryBuild, binaryVersion, channelId, osVersion, fingerprint).all(WireRule.PRINTABLE::accepts) &&
            listOfNotNull(embeddedBundleId, releaseId).all(WireRule.IDENTIFIER::accepts) &&
            attributes.all { (key, value) -> AttributeRules.isValidKey(key) && AttributeRules.isValidValue(value) }

    companion object {
        fun fromJson(json: JSONObject) = DeviceReport(
            attributes = json.getJSONObject("attributes").toStringMap(),
            binaryBuild = json.getString("binaryBuild"),
            binaryVersion = json.getString("binaryVersion"),
            channelId = json.getString("channelId"),
            channelSource = json.getWireEnum("channelSource", ChannelSource),
            embeddedBundleId = json.optNullableString("embeddedBundleId"),
            fingerprint = json.optNullableString("fingerprint"),
            osVersion = json.getString("osVersion"),
            releaseId = json.optNullableString("releaseId"),
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

    internal val isReadable: Boolean
        get() = WireRule.UUID.accepts(deviceId) && WireRule.PRINTABLE.accepts(sdkVersion) && platform in ChannelIndex.PLATFORMS &&
            events.size <= MAXIMUM_EVENT_COUNT && (report?.isReadable ?: true)

    companion object {
        /** The outbox cap and the largest batch a device sends; the events endpoint refuses a larger one. */
        const val MAXIMUM_EVENT_COUNT = 200
    }
}

/** The `202` answer: the server time the device keeps as `reportedAt`. */
data class DeviceEventsResponse(val reportedAt: Long) {
    companion object {
        fun fromJson(json: JSONObject) = DeviceEventsResponse(Iso8601.parse(json.getString("reportedAt")))
    }
}

internal data class DeviceEventsAcknowledgement(val hasReport: Boolean, val reportedAt: Long)

// The first stamp of a UTC month that carried the report stays, so the device compares `cappedAt` with the stamp the server counted.
internal fun resolveKeptReportedAt(reportedAt: Long?, acknowledgement: DeviceEventsAcknowledgement): Long? {
    if (!acknowledgement.hasReport) return reportedAt
    if (reportedAt == null || resolveUtcMonthNumber(acknowledgement.reportedAt) > resolveUtcMonthNumber(reportedAt)) return acknowledgement.reportedAt
    return reportedAt
}

// Months since year zero, so a later month compares greater across a year's turn.
internal fun resolveUtcMonthNumber(epochMillis: Long): Int = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
    timeInMillis = epochMillis
    get(Calendar.YEAR) * 12 + get(Calendar.MONTH)
}

internal sealed class BatchAnswer {
    data class Acknowledged(val reportedAt: Long) : BatchAnswer()
    data class Refused(val status: Int) : BatchAnswer()

    // A null status means the endpoint could not be reached.
    data class Failed(val status: Int?) : BatchAnswer()

    companion object {
        fun of(response: HttpResponse?): BatchAnswer = when (val status = response?.status) {
            null -> Failed(null)
            202 -> runCatching { DeviceEventsResponse.fromJson(parseJsonObject(response.body)) }.getOrNull()?.let { Acknowledged(it.reportedAt) } ?: Failed(status)
            408, 429 -> Failed(status)
            in 400..499 -> Refused(status)
            else -> Failed(status)
        }
    }
}

internal fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
