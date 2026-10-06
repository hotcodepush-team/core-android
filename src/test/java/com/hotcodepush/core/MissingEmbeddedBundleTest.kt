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
 * A build whose build step found no JavaScript bundled embeds no bundle: live updates are off in it, every cycle skips with
 * `DEBUG_BUILD` without a request, and nothing is sent.
 */
class MissingEmbeddedBundleTest {
    private val v2Content = "<html>v2</html>".toByteArray()
    private val skipped = SyncResult.skipped(null, SkippedReason.DEBUG_BUILD)
    private val withoutEmbeddedBundle: JSONObject = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/resource-files.json").readText()).getJSONArray("cases").let { cases ->
        List(cases.length()) { cases.getJSONObject(it) }.single { it.isNull("embeddedBundleManifest") }
    }

    /** A later binary than the harness's first one, built without an embedded bundle. */
    private fun configurationWithoutEmbeddedBundle(channelId: String? = Fixture.CHANNEL_ID) = Fixture.configuration(builtAt = Fixture.BUILT_AT + 86_400_000, channelId = channelId, hasEmbeddedBundle = false)

    /** A debug build served by the development server, with debug builds enabled as the project leaves them, and a release on its channel. */
    private fun harnessWithoutEmbeddedBundle() = Harness(configurationWithoutEmbeddedBundle(), isDebugBuild = true).apply {
        acknowledgeEvents()
        publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
    }

    @Test
    fun shouldReadAResourceFileWhoseEmbeddedBundleManifestIsNullAndRefuseOneWithoutTheKey() {
        val resourceFile = withoutEmbeddedBundle.getJSONObject("resourceFile")
        val configuration = Configuration.fromJson(resourceFile)
        assertNull(configuration.embeddedBundleManifest)
        assertNull(configuration.embeddedBundleId)
        assertThrows(JSONException::class.java) { Configuration.fromJson(JSONObject(resourceFile.toString()).apply { remove("embeddedBundleManifest") }) }
    }

    @Test
    fun shouldSkipASyncWithDebugBuildAndRequestNothingWhenTheBuildEmbedsNoBundle() = runBlocking {
        val harness = harnessWithoutEmbeddedBundle()
        harness.core.handleAppStart()
        assertEquals(skipped, harness.core.sync(SyncTrigger.MANUAL))
        assertTrue(harness.http.requests.isEmpty())
        assertTrue(harness.http.posts.isEmpty())
    }

    @Test
    fun shouldSkipACheckWithDebugBuildAndRequestNothingWhenTheBuildEmbedsNoBundle() = runBlocking {
        val harness = harnessWithoutEmbeddedBundle()
        harness.core.handleAppStart()
        assertEquals(skipped, harness.core.checkForUpdate())
        assertTrue(harness.http.requests.isEmpty())
        assertTrue(harness.http.posts.isEmpty())
    }

    @Test
    fun shouldSkipADownloadWithDebugBuildAndRequestNothingWhenTheBuildEmbedsNoBundle() = runBlocking {
        val harness = harnessWithoutEmbeddedBundle()
        harness.core.handleAppStart()
        assertEquals(skipped, harness.core.downloadUpdate())
        assertTrue(harness.http.requests.isEmpty())
        assertTrue(harness.http.posts.isEmpty())
    }

    @Test
    fun shouldSkipWithDebugBuildWhenTheBuildEmbedsNoBundleIsNoDebugBuildAndHasDebugBuildsEnabled() = runBlocking {
        val harness = Harness(configurationWithoutEmbeddedBundle(), isDebugBuild = false)
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        assertEquals(skipped, harness.core.sync(SyncTrigger.MANUAL))
        assertTrue(harness.http.requests.isEmpty())
    }

    @Test
    fun shouldEmptyTheStoreAndAnnounceNothingAtTheStartOfABuildWithoutAnEmbeddedBundleWhenTheStoreHoldsAnotherBinarysReleases() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        val v4 = Fixture.release(3, "b4", "<html>v4</html>".toByteArray())
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.publish(listOf(v2, v3), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.publish(listOf(v2, v3, v4), 3, etag = "\"e3\"")
        harness.core.sync(SyncTrigger.MANUAL, SyncOptions(installStrategy = InstallStrategy.NEXT_START))
        val held = harness.core.getState()
        assertEquals(listOf("r2", "r3", "r1"), listOf(held.currentRelease?.id, held.nextRelease?.id, held.fallbackRelease?.id))
        assertEquals("b4", harness.loader.persisted)
        val outbox = StateStore(harness.store).unsentEvents
        val timerCount = harness.scheduler.tasks.size
        harness.loader.served = "b4"
        harness.restart(configurationWithoutEmbeddedBundle())
        harness.core.handleAppStart()
        val started = harness.core.getState()
        assertNull(started.currentRelease)
        assertNull(started.nextRelease)
        assertNull(started.fallbackRelease)
        assertTrue(started.failedBundleIds.isEmpty())
        assertTrue(harness.listener.rolledBack.isEmpty())
        assertEquals(timerCount, harness.scheduler.tasks.size)
        assertEquals(outbox, StateStore(harness.store).unsentEvents)
        assertNull(harness.loader.persisted)
        assertNull(harness.loader.loaded.last())
    }

    @Test
    fun shouldSendNoBatchWhenTheBuildEmbedsNoBundleAndTheOutboxHoldsEvents() = runBlocking {
        val harness = Harness()
        harness.http.stub(Fixture.eventsUrl(), status = 500, body = ByteArray(0))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        val outbox = StateStore(harness.store).unsentEvents
        assertEquals(2, outbox.size)
        val postCount = harness.http.posts.size
        harness.acknowledgeEvents()
        harness.restart(configurationWithoutEmbeddedBundle())
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(postCount, harness.http.posts.size)
        assertEquals(outbox, StateStore(harness.store).unsentEvents)
    }

    @Test
    fun shouldAnswerNothingToApplyAndNoRollbackWhenTheBuildEmbedsNoBundle() = runBlocking {
        val harness = harnessWithoutEmbeddedBundle()
        harness.core.handleAppStart()
        assertEquals(ApplyResult(ApplyStatus.NOTHING_TO_APPLY, null), harness.core.applyUpdate())
        assertEquals(NotifyReadyResult(null, null, false, null), harness.core.notifyReady())
        harness.core.rollbackUpdate(null)
        assertTrue(harness.loader.loaded.isEmpty())
        assertTrue(harness.listener.rolledBack.isEmpty())
    }

    @Test
    fun shouldSayOnTheDebugReportThatTheBuildEmbedsNoBundle() = runBlocking {
        val harness = Harness(configurationWithoutEmbeddedBundle(channelId = null), isDebugBuild = true)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        val text = DebugReport.text(harness.core.debugSnapshot())
        assertTrue(text, text.contains("Embedded bundle: none: the build embeds no bundle, live updates are off in it"))
        assertTrue(text, text.contains("Result: SKIPPED DEBUG_BUILD"))
    }
}
