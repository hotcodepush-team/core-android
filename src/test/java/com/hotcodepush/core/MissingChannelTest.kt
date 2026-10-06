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

/**
 * A build whose build step ran without a token or offline carries no channel: it answers `FAILED` with `UNKNOWN_CHANNEL`,
 * requests nothing and reports nothing until a channel is set at runtime.
 */
class MissingChannelTest {
    private val v2Content = "<html>v2</html>".toByteArray()
    private val failed = SyncResult.failed(null, FailedReason.UNKNOWN_CHANNEL, Core.MISSING_CHANNEL_MESSAGE)
    private val resourceFiles: List<JSONObject> = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/resource-files.json").readText()).getJSONArray("cases").let { cases ->
        List(cases.length()) { cases.getJSONObject(it) }
    }

    private fun harness(autoCheck: Boolean = false) = Harness(Fixture.configuration(channelId = null, autoCheck = autoCheck))

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
    fun shouldFailEveryStageWithUnknownChannelWithoutTouchingTheNetwork() = runBlocking {
        val harness = harness()
        harness.acknowledgeEvents()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        assertEquals(failed, harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(failed, harness.core.checkForUpdate())
        assertEquals(failed, harness.core.downloadUpdate())
        assertTrue(harness.http.requests.isEmpty())
        assertTrue(harness.http.posts.isEmpty())
        assertEquals(listOf(FailedReason.UNKNOWN_CHANNEL, FailedReason.UNKNOWN_CHANNEL, FailedReason.UNKNOWN_CHANNEL), harness.listener.failed.map { it.reason })
        assertEquals(failed, harness.core.getState().lastCheck?.result)
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
        assertNull(harness.core.getState().lastReportAt)
        assertEquals(ChannelResult(null, null, ChannelSource.CONFIG), harness.core.channel())
    }

    @Test
    fun shouldFailTheSyncAtStartWithoutTouchingTheNetworkWhenAutoCheckIsOn() = runBlocking {
        val harness = harness(autoCheck = true)
        harness.core.handleAppStart()
        assertEquals(listOf(FailedReason.UNKNOWN_CHANNEL), harness.listener.failed.map { it.reason })
        assertEquals(Core.MISSING_CHANNEL_MESSAGE, harness.listener.failed.single().message)
        assertTrue(harness.http.requests.isEmpty())
        assertTrue(harness.http.posts.isEmpty())
    }

    @Test
    fun shouldSyncOnAChannelSetAtRuntimeByIdAndReportIt() = runBlocking {
        val harness = harness()
        harness.acknowledgeEvents()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.setChannel(ChannelChoice.Id(Fixture.CHANNEL_ID))
        assertEquals(SyncStatus.UPDATED, harness.core.sync(SyncTrigger.MANUAL).status)
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
        assertEquals(SyncStatus.UPDATED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertEquals(ChannelResult(Fixture.CHANNEL_ID, "beta", ChannelSource.RUNTIME), harness.core.channel())
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
    fun shouldFallBackToNoChannelWhenTheRuntimeChannelServesNoIndex() = runBlocking {
        val harness = harness()
        harness.core.handleAppStart()
        harness.core.setChannel(ChannelChoice.Id("gone"))
        assertEquals(failed, harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(listOf("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/gone/android/v1/index.json"), harness.http.requests.map { it.first })
        assertEquals(ChannelResult(null, null, ChannelSource.CONFIG), harness.core.channel())
    }

    @Test
    fun shouldFallBackToTheConfiguredChannelWhenTheRuntimeChannelServesNoIndex() = runBlocking {
        val harness = Harness()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.setChannel(ChannelChoice.Id("gone"))
        assertEquals(SyncStatus.UPDATED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertEquals(ChannelResult(Fixture.CHANNEL_ID, null, ChannelSource.CONFIG), harness.core.channel())
    }

    @Test
    fun shouldSayInTheDebugReportThatTheBuildHasNoChannelAndWhy() = runBlocking {
        val harness = harness()
        harness.core.handleAppStart()
        harness.core.checkForUpdate()
        val text = DebugReport.text(harness.core.debugSnapshot())
        assertTrue(text, text.contains("Channel\n  Channel id: none: the build carries no channel, it was built without a token or offline\n  Name: none\n  Source: config\n"))
        assertTrue(text, text.contains("Configured channel: none"))
        assertTrue(text, text.contains("Result: FAILED UNKNOWN_CHANNEL"))
        harness.core.setChannel(ChannelChoice.Id(Fixture.CHANNEL_ID))
        assertTrue(DebugReport.text(harness.core.debugSnapshot()).contains("Channel\n  Channel id: ${Fixture.CHANNEL_ID}\n  Name: none\n  Source: runtime\n"))
    }
}
