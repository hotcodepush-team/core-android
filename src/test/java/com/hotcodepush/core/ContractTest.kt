package com.hotcodepush.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every result carries every key of the typed contract, `null` when empty, never an absent key. */
class ContractTest {
    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    @Test
    fun shouldCarryEveryStateKeyOnAFreshInstall() {
        val json = Harness().core.getState().toJson()
        assertEquals(setOf("currentRelease", "nextRelease", "fallbackRelease", "embeddedBundleId", "lastCheck", "index", "failedBundleIds", "lastReportAt"), keys(json))
        assertTrue(json.isNull("currentRelease"))
        assertTrue(json.isNull("lastCheck"))
        assertTrue(json.isNull("lastReportAt"))
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
        assertEquals(setOf("status", "release", "notes", "downloadBytes"), keys(SyncResult.available(release, null, null).toJson()))
        assertEquals(setOf("status", "release", "notes"), keys(SyncResult.downloaded(release, null).toJson()))
        assertEquals(setOf("status", "release", "notes", "installAt"), keys(SyncResult.updated(release, null, InstallMoment.IMMEDIATE).toJson()))
        assertEquals(setOf("status", "release", "reason"), keys(SyncResult.skipped(null, SkippedReason.CHANNEL_PAUSED).toJson()))
        assertEquals(setOf("status", "release", "reason", "condition"), keys(SyncResult.skipped(release, SkippedReason.INCOMPATIBLE, ConditionType.OS).toJson()))
        assertEquals(setOf("status", "release", "reason", "message"), keys(SyncResult.failed(null, FailedReason.OFFLINE, "m").toJson()))
        assertEquals(setOf("currentRelease", "previousRelease", "isRolledBack"), keys(NotifyReadyResult(null, null, false, null).toJson()))
        assertEquals(setOf("status", "release"), keys(ApplyResult(ApplyStatus.NOTHING_TO_APPLY, null).toJson()))
        assertTrue(ApplyResult(ApplyStatus.NOTHING_TO_APPLY, null).toJson().isNull("release"))
        assertEquals(setOf("release", "notes", "downloadBytes", "trigger"), keys(UpdateAvailableEvent(release, null, null, SyncTrigger.MANUAL).toJson()))
        assertEquals(setOf("release", "reason", "message", "trigger"), keys(UpdateFailedEvent(null, FailedReason.OFFLINE, "m", SyncTrigger.START).toJson()))
    }
}
