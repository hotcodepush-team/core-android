package com.hotcodepush.core

import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MissingChannelTest {
    private val v2Content = "<html>v2</html>".toByteArray()
    private val failed = SyncResult.failed(null, FailedReason.CHANNEL_UNKNOWN, Core.MISSING_CHANNEL_MESSAGE)
    private val resourceFiles: List<JSONObject> = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/resource-files.json").readText()).getJSONArray("cases").let { cases ->
        List(cases.length()) { cases.getJSONObject(it) }
    }

    // Automatic checks are on, so a failure a test sees proves the explicit call alone failed.
    private fun harness() = Harness(Fixture.configuration(channelId = null, checkStrategy = CheckStrategy.AUTO))

    @Test
    fun shouldReadANullChannelFromTheFixtureOfABuildWithoutAChannel() {
        val withoutChannel = resourceFiles.single { it.getString("name").contains("without a channel") }
        val withoutEmbeddedBundle = resourceFiles.single { it.isNull("embeddedBundleManifest") }
        assertNull(Configuration.fromJson(withoutChannel.getJSONObject("resourceFile")).channelId)
        for (case in resourceFiles - withoutChannel - withoutEmbeddedBundle) assertEquals(case.getString("name"), "c1", Configuration.fromJson(case.getJSONObject("resourceFile")).channelId)
    }

    @Test
    fun shouldRefuseAResourceFileWithoutTheChannelKeyOrWithAnEmptyChannel() {
        val resourceFile = resourceFiles.first().getJSONObject("resourceFile")
        assertThrows(JSONException::class.java) { Configuration.fromJson(JSONObject(resourceFile.toString()).apply { remove("channelId") }) }
        assertThrows(JSONException::class.java) { Configuration.fromJson(JSONObject(resourceFile.toString()).put("channelId", "")) }
    }

    @Test
    fun shouldFailAnExplicitSyncWithUnknownChannelRequestNothingAndFireUpdateFailedWithTheManualTriggerWhenTheDeviceHasNoChannel() = runBlocking {
        val harness = harnessWithARelease()
        harness.core.handleAppStart()
        assertEquals(failed, harness.core.sync(SyncTrigger.MANUAL))
        assertFailedExplicitly(harness)
        assertEquals(LastCheck(harness.clock.now, SyncTrigger.MANUAL, failed), harness.core.getState().lastCheck)
    }

    @Test
    fun shouldFailAnExplicitCheckWithUnknownChannelRequestNothingAndFireUpdateFailedWithTheManualTriggerWhenTheDeviceHasNoChannel() = runBlocking {
        val harness = harnessWithARelease()
        harness.core.handleAppStart()
        assertEquals(failed, harness.core.checkForUpdate())
        assertFailedExplicitly(harness)
        assertEquals(LastCheck(harness.clock.now, SyncTrigger.MANUAL, failed), harness.core.getState().lastCheck)
    }

    @Test
    fun shouldFailAnExplicitDownloadWithUnknownChannelRequestNothingAndFireUpdateFailedWithTheManualTriggerWhenTheDeviceHasNoChannel() = runBlocking {
        val harness = harnessWithARelease()
        harness.core.handleAppStart()
        assertEquals(failed, harness.core.downloadUpdate())
        assertFailedExplicitly(harness)
    }

    @Test
    fun shouldSkipAnExplicitSyncWithDebugBuildWhenTheBuildIsDisabledAndTheDeviceHasNoChannel() = runBlocking {
        val harness = Harness(Fixture.configuration(isEnabledInDebugBuilds = false, channelId = null), isDebugBuild = true)
        harness.core.handleAppStart()
        assertEquals(SyncResult.skipped(null, SkippedReason.BUILD_DEBUG), harness.core.sync(SyncTrigger.MANUAL))
        assertTrue(harness.listener.failed.isEmpty())
    }

    @Test
    fun shouldStartNoCheckAtStartWhenTheDeviceHasNoChannel() = runBlocking {
        val harness = harnessWithARelease()
        harness.core.handleAppStart()
        assertNoCheckStarted(harness)
    }

    @Test
    fun shouldStartNoCheckAtTheConfirmationOfANewReleaseWhenTheDeviceHasNoChannel() = runBlocking {
        val harness = harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.setChannel(ChannelChoice.Id(Fixture.CHANNEL_ID))
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.setChannel(null)
        val requestCount = harness.http.requests.size
        harness.loader.served = "b2"
        harness.restart(Fixture.configuration(channelId = null, checkStrategy = CheckStrategy.AUTO))
        harness.core.handleAppStart()
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        harness.core.notifyReady()
        assertEquals(SyncTrigger.MANUAL, StateStore(harness.store).lastCheck?.trigger)
        assertEquals(requestCount, harness.http.requests.size)
        assertTrue(harness.listener.failed.isEmpty())
    }

    @Test
    fun shouldStartNoCheckOnResumeWhenTheDeviceHasNoChannel() = runBlocking {
        val harness = harnessWithARelease()
        harness.core.handleAppStart()
        harness.core.handleAppPause()
        harness.clock.now += 1_000_000
        harness.core.handleAppResume()
        assertNoCheckStarted(harness)
    }

    @Test
    fun shouldStartNoCheckAndArmNoTimerWhenTheIntervalFiresAndTheDeviceHasNoChannel() = runBlocking {
        val harness = harnessWithARelease()
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(listOf(900.0), harness.scheduler.tasks.map { it.seconds })
        harness.scheduler.fire()
        assertEquals(SyncTrigger.MANUAL, StateStore(harness.store).lastCheck?.trigger)
        assertEquals(listOf(SyncTrigger.MANUAL), harness.listener.failed.map { it.trigger })
        assertTrue(harness.scheduler.tasks.isEmpty())
        assertTrue(harness.http.requests.isEmpty())
    }

    @Test
    fun shouldCheckOnItsOwnAgainAtTheNextResumeWhenAChannelWasSetAtRuntime() = runBlocking {
        val harness = harness()
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        harness.core.setChannel(ChannelChoice.Id(Fixture.CHANNEL_ID))
        harness.core.handleAppResume()
        val lastCheck = StateStore(harness.store).lastCheck
        assertEquals(SyncTrigger.RESUME, lastCheck?.trigger)
        assertEquals(SyncResult.upToDate(null), lastCheck?.result)
    }

    @Test
    fun shouldPostNoReportAndQueueNothing() = runBlocking {
        val harness = harness()
        harness.acknowledgeEvents()
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        val state = StateStore(harness.store)
        assertTrue(harness.http.posts.isEmpty())
        assertTrue(state.unsentEvents.isEmpty())
        assertNull(state.acknowledgedReport)
        assertNull(harness.core.getState().reportedAt)
        assertEquals(GetChannelResult(null, null, ChannelSource.CONFIG), harness.core.getChannel())
    }

    @Test
    fun shouldSyncOnAChannelSetAtRuntimeByIdAndReportIt() = runBlocking {
        val harness = harness()
        harness.acknowledgeEvents()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.setChannel(ChannelChoice.Id(Fixture.CHANNEL_ID))
        assertEquals(SyncStatus.DOWNLOADED, harness.core.sync(SyncTrigger.MANUAL).status)
        val report = JSONObject(String(harness.http.posts.single().third)).getJSONObject("report")
        assertEquals(Fixture.CHANNEL_ID, report.getString("channelId"))
        assertEquals("runtime", report.getString("channelSource"))
    }

    @Test
    fun shouldSyncOnAChannelSetAtRuntimeByName() = runBlocking {
        val harness = harness()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.http.stubJson("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/v1/index.json", JSONObject().put("schema", 1).put("channels", org.json.JSONArray(listOf(JSONObject().put("id", Fixture.CHANNEL_ID).put("name", "beta")))))
        harness.core.handleAppStart()
        harness.core.setChannel(ChannelChoice.Name("beta"))
        assertEquals(SyncStatus.DOWNLOADED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertEquals(GetChannelResult(Fixture.CHANNEL_ID, "beta", ChannelSource.RUNTIME), harness.core.getChannel())
    }

    @Test
    fun shouldAnswerUnknownChannelAgainOnceTheRuntimeChoiceIsCleared() = runBlocking {
        val harness = harness()
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        harness.core.setChannel(ChannelChoice.Id(Fixture.CHANNEL_ID))
        assertEquals(SyncStatus.UP_TO_DATE, harness.core.sync(SyncTrigger.MANUAL).status)
        val requestsOnTheRuntimeChannel = harness.http.requests.size
        harness.core.setChannel(null)
        assertEquals(failed, harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(requestsOnTheRuntimeChannel, harness.http.requests.size)
    }

    @Test
    fun shouldFailWithUnknownChannelKeepTheRuntimeChoiceAndKeepCheckingWhenTheRuntimeChannelServesNoIndexAndTheBuildCarriesNone() = runBlocking {
        val harness = harness()
        harness.core.setChannel(ChannelChoice.Id(GONE_CHANNEL_ID))
        harness.core.handleAppStart()
        assertEquals(listOf(UpdateFailedEvent(null, FailedReason.CHANNEL_UNKNOWN, "The channel $GONE_CHANNEL_ID set at runtime serves no index.", SyncTrigger.START)), harness.listener.failed)
        assertEquals(GetChannelResult(GONE_CHANNEL_ID, null, ChannelSource.RUNTIME), harness.core.getChannel())
        assertEquals(listOf(GONE_INDEX_URL), harness.http.requests.map { it.first })
        harness.scheduler.fire()
        assertEquals(SyncTrigger.INTERVAL, StateStore(harness.store).lastCheck?.trigger)
        assertEquals(listOf(900.0), harness.scheduler.tasks.map { it.seconds })
    }

    @Test
    fun shouldFailWithUnknownChannelKeepTheRuntimeChoiceAndRequestNoOtherIndexWhenTheRuntimeChannelServesNoIndexAndTheBuildCarriesOne() = runBlocking {
        val harness = Harness(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        val requestsOnTheConfiguredChannel = harness.http.requests.size
        harness.core.setChannel(ChannelChoice.Id(GONE_CHANNEL_ID))
        assertEquals(SyncResult.failed(null, FailedReason.CHANNEL_UNKNOWN, "The channel $GONE_CHANNEL_ID set at runtime serves no index."), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(GetChannelResult(GONE_CHANNEL_ID, null, ChannelSource.RUNTIME), harness.core.getChannel())
        assertEquals(listOf(GONE_INDEX_URL), harness.http.requests.drop(requestsOnTheConfiguredChannel).map { it.first })
        assertEquals(listOf(900.0), harness.scheduler.tasks.filter { !it.isCancelled }.map { it.seconds })
    }

    @Test
    fun shouldFailWithUnknownChannelAndKeepCheckingWhenTheConfiguredChannelServesNoIndex() = runBlocking {
        val harness = Harness(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        harness.core.handleAppStart()
        assertEquals(listOf(UpdateFailedEvent(null, FailedReason.CHANNEL_UNKNOWN, "The channel ${Fixture.CHANNEL_ID} of this build serves no index.", SyncTrigger.START)), harness.listener.failed)
        assertEquals(GetChannelResult(Fixture.CHANNEL_ID, null, ChannelSource.CONFIG), harness.core.getChannel())
        harness.scheduler.fire()
        assertEquals(SyncTrigger.INTERVAL, StateStore(harness.store).lastCheck?.trigger)
        assertEquals(listOf(900.0), harness.scheduler.tasks.map { it.seconds })
    }

    @Test
    fun shouldSayInTheDebugReportThatTheBuildHasNoChannelAndWhy() = runBlocking {
        val harness = harness()
        harness.core.handleAppStart()
        harness.core.checkForUpdate()
        val text = DebugReport.text(harness.core.debugSnapshot())
        assertTrue(text, text.contains("Channel\n  Channel id: none: the build carries no channel, it was built without a token or offline\n  Name: none\n  Source: config\n"))
        assertTrue(text, text.contains("Configured channel: none"))
        assertTrue(text, text.contains("Result: failed channel_unknown"))
        harness.core.setChannel(ChannelChoice.Id(Fixture.CHANNEL_ID))
        assertTrue(DebugReport.text(harness.core.debugSnapshot()).contains("Channel\n  Channel id: ${Fixture.CHANNEL_ID}\n  Name: none\n  Source: runtime\n"))
    }

    private fun harnessWithARelease() = harness().apply {
        acknowledgeEvents()
        publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
    }

    private fun assertFailedExplicitly(harness: Harness) {
        assertEquals(listOf(UpdateFailedEvent(null, FailedReason.CHANNEL_UNKNOWN, Core.MISSING_CHANNEL_MESSAGE, SyncTrigger.MANUAL)), harness.listener.failed)
        assertTrue(harness.http.requests.isEmpty())
        assertTrue(harness.http.posts.isEmpty())
    }

    private fun assertNoCheckStarted(harness: Harness) {
        val state = StateStore(harness.store)
        assertNull(state.lastCheck)
        assertNull(state.lastSyncAt)
        assertEquals(emptyList<LogEntry>(), harness.core.debugSnapshot().log)
        assertTrue(harness.listener.failed.isEmpty())
        assertTrue(harness.http.requests.isEmpty())
        assertTrue(harness.http.posts.isEmpty())
        assertTrue(harness.scheduler.tasks.isEmpty())
    }

    private companion object {
        const val GONE_CHANNEL_ID = "90e00000-0000-4000-8000-000000000003"
        const val GONE_INDEX_URL = "${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/$GONE_CHANNEL_ID/android/v1/index.json"
    }
}
