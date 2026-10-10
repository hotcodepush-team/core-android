package com.hotcodepush.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

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
    /** The app's `rollback({ reason })` on `APP_REQUESTED`: printable, at most 256 characters. */
    val detail: String? = null,
) {
    /**
     * The keys of the event's type as the protocol's schema reads them: an optional key is left out when it holds
     * nothing, a nullable one is always there, so a rollback's `toReleaseId` is `null` for the embedded bundle, never absent.
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
            .put("type", type)
            .putIfNotNull("releaseId", releaseId)
            .putIfNotNull("bundleId", bundleId)
            .putIfNotNull("status", status)
            .putIfNotNull("reason", reason)
            .putIfNotNull("condition", condition?.wire)
            .putIfNotNull("bytes", bytes)
            .putIfNotNull("packKind", packKind)
            .putIfNotNull("fromReleaseId", fromReleaseId)
            .putIfNotNull("detail", detail)
        if (type == ROLLED_BACK) json.put("toReleaseId", toReleaseId ?: JSONObject.NULL)
        return json
    }

    companion object {
        private const val ROLLED_BACK = "rolledBack"

        fun checked(releaseId: String, status: SyncStatus, reason: SkippedReason? = null, condition: ConditionType? = null) =
            DeviceEvent("checked", releaseId = releaseId, status = status.wire, reason = reason?.name, condition = condition)

        fun downloaded(releaseId: String, bundleId: String, bytes: Long, packKind: PackKind) =
            DeviceEvent("downloaded", releaseId = releaseId, bundleId = bundleId, bytes = bytes, packKind = packKind.wire)

        fun applied(releaseId: String) = DeviceEvent("applied", releaseId = releaseId)

        fun confirmed(releaseId: String) = DeviceEvent("confirmed", releaseId = releaseId)

        fun failed(releaseId: String, reason: String, detail: String? = null) = DeviceEvent("failed", releaseId = releaseId, reason = reason, detail = detail)

        fun rolledBack(fromReleaseId: String, toReleaseId: String?) = DeviceEvent(ROLLED_BACK, fromReleaseId = fromReleaseId, toReleaseId = toReleaseId)

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

    /**
     * Whether the events endpoint reads the report: every text fact printable, every attribute under the attribute rules and
     * every id an identifier. A report it refuses costs the batch's events at every sync, so the device never sends one.
     */
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
            channelSource = ChannelSource.entries.first { it.wire == json.getString("channelSource") },
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

    /** Whether the events endpoint reads the batch whole: the device's id and the SDK's version printable, a platform it knows, no more events than the outbox holds and a readable report. */
    internal val isReadable: Boolean
        get() = listOf(deviceId, sdkVersion).all(WireRule.PRINTABLE::accepts) && platform in ChannelIndex.PLATFORMS &&
            events.size <= MAXIMUM_EVENT_COUNT && (report?.isReadable ?: true)

    companion object {
        /** The outbox's cap and so the largest batch a device sends; the events endpoint refuses a larger one. */
        const val MAXIMUM_EVENT_COUNT = 200
    }
}

/** The `202`: the server time the device keeps as `reportedAt` by `resolveKeptReportedAt`. */
data class DeviceEventsResponse(val reportedAt: Long) {
    companion object {
        fun fromJson(json: JSONObject) = DeviceEventsResponse(Iso8601.parse(json.getString("reportedAt")))
    }
}

/** A `202` as the device reads it: the server time, and whether the acknowledged batch carried the device report. */
internal data class DeviceEventsAcknowledgement(val hasReport: Boolean, val reportedAt: Long)

/**
 * The `reportedAt` a device keeps after a `202`: the stamp of the first acknowledged batch of a UTC month that carried the
 * device report, the month read from the stamp itself. A later acknowledgement replaces it only when it falls in a later UTC
 * month, and a batch of events alone never moves it, so the device compares with `cappedAt` the stamp the server counted it by.
 */
internal fun resolveKeptReportedAt(reportedAt: Long?, acknowledgement: DeviceEventsAcknowledgement): Long? {
    if (!acknowledgement.hasReport) return reportedAt
    if (reportedAt == null || resolveUtcMonthNumber(acknowledgement.reportedAt) > resolveUtcMonthNumber(reportedAt)) return acknowledgement.reportedAt
    return reportedAt
}

/** The months since year zero in UTC, so a later month compares greater across a year's turn. */
internal fun resolveUtcMonthNumber(epochMillis: Long): Int = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
    timeInMillis = epochMillis
    get(Calendar.YEAR) * 12 + get(Calendar.MONTH)
}

/** What the events endpoint's answer means for a batch: taken, refused for good, or kept for the next sync. */
internal sealed class BatchAnswer {
    data class Acknowledged(val reportedAt: Long) : BatchAnswer()
    data class Refused(val status: Int) : BatchAnswer()

    /** No response, or one that asks for the batch again: `null` when the endpoint could not be reached. */
    data class Failed(val status: Int?) : BatchAnswer()

    companion object {
        /** A readable `202` takes the batch; a 4xx other than 408 and 429 refuses it for good; anything else asks for it again. */
        fun of(response: HttpResponse?): BatchAnswer = when (val status = response?.status) {
            null -> Failed(null)
            202 -> runCatching { DeviceEventsResponse.fromJson(JSONObject(String(response.body, Charsets.UTF_8))) }.getOrNull()?.let { Acknowledged(it.reportedAt) } ?: Failed(status)
            408, 429 -> Failed(status)
            in 400..499 -> Refused(status)
            else -> Failed(status)
        }
    }
}

internal fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
