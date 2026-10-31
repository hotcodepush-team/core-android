package com.hotcodepush.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    @Test
    fun shouldCarryEveryStateKeyOnAFreshInstall() {
        val json = Harness().core.getState().toJson()
        assertEquals(setOf("currentRelease", "nextRelease", "fallbackRelease", "embeddedBundleId", "lastCheck", "index", "failedBundleIds", "reportedAt"), keys(json))
        assertTrue(json.isNull("currentRelease"))
        assertTrue(json.isNull("lastCheck"))
        assertTrue(json.isNull("reportedAt"))
        assertEquals("embedded", json.getString("embeddedBundleId"))
    }

    @Test
    fun shouldCarryEveryDeviceKeyWithNullsForTheEmptyOnes() = runBlocking {
        val json = Harness(Fixture.configuration(fingerprint = null, channelId = null)).core.deviceResult().toJson()
        assertEquals(setOf("id", "platform", "binaryVersion", "binaryBuild", "osVersion", "sdkVersion", "fingerprint", "channel", "attributes"), keys(json))
        assertTrue(json.isNull("fingerprint"))
        val channel = json.getJSONObject("channel")
        assertEquals(setOf("id", "name", "source"), keys(channel))
        assertTrue(channel.isNull("id"))
        assertTrue(channel.isNull("name"))
    }

    @Test
    fun shouldCarryTheKeysOfEachSyncStatus() {
        val release = Release("r1", 1, "b1", "1", false)
        assertEquals(setOf("status", "release"), keys(SyncResult.upToDate(null).toJson()))
        assertTrue(SyncResult.upToDate(null).toJson().isNull("release"))
        assertEquals(setOf("status", "release", "notes", "downloadSizeBytes"), keys(SyncResult.available(release, null, null).toJson()))
        assertEquals(setOf("status", "release", "notes", "applyStrategy"), keys(SyncResult.downloaded(release, null, ApplyStrategy.MANUAL).toJson()))
        assertEquals(setOf("status", "release", "notes"), keys(SyncResult.applied(release, null).toJson()))
        assertEquals(setOf("status", "release", "reason"), keys(SyncResult.skipped(null, SkippedReason.CHANNEL_PAUSED).toJson()))
        assertEquals(setOf("status", "release", "reason", "condition"), keys(SyncResult.skipped(release, SkippedReason.DEVICE_INCOMPATIBLE, ConditionType.OS).toJson()))
        assertEquals(setOf("status", "release", "reason", "message"), keys(SyncResult.failed(null, FailedReason.DEVICE_OFFLINE, "m").toJson()))
        assertEquals(setOf("currentRelease", "previousRelease", "isRolledBack"), keys(NotifyReadyResult(null, null, false, null).toJson()))
        assertEquals(setOf("status", "release"), keys(ApplyUpdateResult(ApplyStatus.NOTHING_TO_APPLY, null).toJson()))
        assertTrue(ApplyUpdateResult(ApplyStatus.NOTHING_TO_APPLY, null).toJson().isNull("release"))
        assertEquals(setOf("release", "notes", "downloadSizeBytes", "trigger"), keys(UpdateAvailableEvent(release, null, null, SyncTrigger.MANUAL).toJson()))
        assertEquals(setOf("release", "applyStrategy", "trigger"), keys(UpdateDownloadedEvent(release, ApplyStrategy.NEXT_START, SyncTrigger.START).toJson()))
        assertEquals(setOf("fromRelease", "toRelease", "reason"), keys(UpdateRolledBackEvent(release, null, RollbackReason.APP_CRASHED).toJson()))
        assertEquals(setOf("release", "reason", "message", "trigger"), keys(UpdateFailedEvent(null, FailedReason.DEVICE_OFFLINE, "m", SyncTrigger.START).toJson()))
        assertEquals(setOf("downloadSizeBytes", "progress", "progressBytes", "releaseId"), keys(DownloadProgressEvent(4, 1, "r1").toJson()))
    }

    @Test
    fun shouldCarryTheShareOfThePackThatArrivedAsProgress() {
        val json = DownloadProgressEvent(downloadSizeBytes = 4, progressBytes = 1, releaseId = "r1").toJson()
        assertEquals(4L, json.getLong("downloadSizeBytes"))
        assertEquals(0.25, json.getDouble("progress"), 0.0)
        assertEquals(1L, json.getLong("progressBytes"))
        assertEquals("r1", json.getString("releaseId"))
        assertEquals(0.0, DownloadProgressEvent(downloadSizeBytes = 0, progressBytes = 0, releaseId = "r1").progress, 0.0)
    }
}
