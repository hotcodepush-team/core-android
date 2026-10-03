package com.hotcodepush.protocol

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreTest {
    private val v2Content = "<html>v2</html>".toByteArray()

    @Test
    fun shouldRunTheEmbeddedBundleAndBeUpToDateOnAnEmptyChannel() = runBlocking {
        val harness = Harness()
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(SyncResult.upToDate(null), result)
        assertTrue(harness.listener.available.isEmpty())
        assertTrue(harness.listener.failed.isEmpty())
    }

    @Test
    fun shouldDownloadAReleaseAndApplyItAtTheNextStart() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(SyncResult.updated(v2.release.release, "notes 1", InstallMoment.NEXT_START), result)
        assertEquals("b2", harness.loader.persisted)
        assertTrue(harness.loader.loaded.isEmpty())
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertEquals("<html>v2</html>", File(harness.loader.projectionDirectory("b2"), "index.html").readText())
        val status = harness.core.getState()
        assertEquals(v2.release.release, status.nextRelease)
        assertNull(status.currentRelease)
        assertEquals(1, status.indexSequence)

        harness.loader.served = "b2"
        harness.restart()
        harness.core.handleAppStart()
        val started = harness.core.getState()
        assertEquals(v2.release.release, started.currentRelease)
        assertNull(started.nextRelease)
        assertNull(started.fallbackRelease)
        assertEquals(1, harness.scheduler.tasks.size)
        val ready = harness.core.notifyReady()
        assertEquals(NotifyReadyResult(v2.release.release, null, false, null), ready)
        assertEquals(v2.release.release, harness.core.getState().fallbackRelease)
        assertTrue(harness.scheduler.tasks[0].isCancelled)
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleWhenTheBinaryChanged() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        StateStore(harness.store).failedBundleIds = listOf("b0")
        harness.loader.served = null
        harness.restart(Fixture.configuration(builtAt = Fixture.BUILT_AT + 86_400_000))
        harness.core.handleAppStart()
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertNull(status.nextRelease)
        assertNull(status.fallbackRelease)
        assertTrue(status.failedBundleIds.isEmpty())
        assertTrue(harness.loader.hasPersisted && harness.loader.persisted == null)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertTrue(harness.files.bundleIds().isEmpty())
        assertTrue(!harness.loader.projectionDirectory("b2").exists())
    }

    @Test
    fun shouldKeepTheCurrentReleaseWhenTheBinaryIsTheSame() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.restart()
        harness.core.handleAppStart()
        val status = harness.core.getState()
        assertEquals(v2.release.release, status.currentRelease)
        assertEquals(v2.release.release, status.fallbackRelease)
        assertEquals(listOf("b2"), harness.files.bundleIds())
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleWhenTheCurrentReleaseHasNoFilesOnDisk() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.files.deleteEverything()
        harness.loader.deleteProjection("b2")
        harness.restart()
        harness.core.handleAppStart()
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertNull(status.fallbackRelease)
        assertTrue(harness.loader.hasPersisted && harness.loader.persisted == null)
        assertEquals(listOf("b2", null), harness.loader.loaded)
        assertTrue(harness.listener.rolledBack.isEmpty())
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleWhenTheNextReleaseHasNoFilesOnDisk() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.files.deleteEverything()
        harness.loader.deleteProjection("b2")
        harness.loader.served = "b2"
        harness.restart()
        harness.core.handleAppStart()
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertNull(status.nextRelease)
        assertTrue(harness.loader.hasPersisted && harness.loader.persisted == null)
        assertEquals(listOf(null), harness.loader.loaded)
    }

    @Test
    fun shouldRollBackAReleaseThatNeverRendersAndBlocklistIt() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(InstallMoment.IMMEDIATE, result.installAt)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(1, harness.scheduler.tasks.size)
        harness.scheduler.fire()
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertEquals(listOf("b2"), status.failedBundleIds)
        assertEquals(listOf("b2", null), harness.loader.loaded)
        assertEquals(SyncResult.skipped(v2.release.release, SkippedReason.FAILED_BEFORE), harness.core.sync(SyncTrigger.MANUAL))
        val ready = harness.core.notifyReady()
        assertTrue(ready.isRolledBack)
        assertEquals(RollbackReason.READY_TIMEOUT, ready.rollbackReason)
        assertEquals(v2.release.release, ready.previousRelease)
    }

    @Test
    fun shouldTreatAStartOnAnUnconfirmedReleaseAsACrash() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.loader.served = "b2"
        harness.restart()
        harness.core.handleAppStart()
        harness.restart()
        harness.core.handleAppStart()
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertEquals(listOf("b2"), status.failedBundleIds)
        assertEquals(RollbackReason.CRASHED, harness.listener.rolledBack.last().reason)
        assertNull(harness.loader.loaded.last())
    }

    @Test
    fun shouldFallBackToTheLastConfirmedReleaseNotTheEmbeddedBundle() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        harness.publish(listOf(v2, v3), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.rollback("fatal")
        val status = harness.core.getState()
        assertEquals(v2.release.release, status.currentRelease)
        assertEquals(listOf("b3"), status.failedBundleIds)
        assertEquals("b2", harness.loader.loaded.last())
        val events = StateStore(harness.store).unsentEvents
        assertEquals("rolledBack", events.last().type)
        assertEquals("r1", events.last().toReleaseId)
    }

    @Test
    fun shouldKeepTheCachedIndexOfflineAndIgnoreAnOlderSequence() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 5)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.http.isOffline = true
        assertEquals(SyncStatus.UPDATED, harness.core.sync(SyncTrigger.MANUAL).status)
        harness.http.isOffline = false
        harness.publish(emptyList(), 4, etag = "\"e0\"")
        assertEquals(SyncStatus.UPDATED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertEquals(5, harness.core.getState().indexSequence)
    }

    @Test
    fun shouldFailOfflineWithoutACachedIndex() = runBlocking {
        val harness = Harness()
        harness.http.isOffline = true
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(SyncStatus.FAILED, result.status)
        assertEquals(FailedReason.OFFLINE.name, result.reason)
    }

    @Test
    fun shouldSendTheEtagAndAcceptANotModified() = runBlocking {
        val harness = Harness()
        harness.publish(emptyList(), 1, etag = "\"e1\"")
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.http.stub(Fixture.indexUrl(), status = 304, body = ByteArray(0))
        assertEquals(SyncResult.upToDate(null), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals("\"e1\"", harness.http.requests.last().second["If-None-Match"])
    }

    @Test
    fun shouldCheckWithoutDownloading() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.available(v2.release.release, "notes 1", 15), harness.core.checkForUpdate())
        assertTrue(!harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertEquals(listOf(v2.release.release), harness.listener.available.map { it.release })
        assertEquals(SyncTrigger.MANUAL, harness.listener.available.first().trigger)
    }

    @Test
    fun shouldFailVerificationOnATamperedManifest() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.http.stubJson(v2.release.manifestUrl, v2.envelope.copy(manifest = v2.envelope.manifest + " ").toJson())
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(SyncStatus.FAILED, result.status)
        assertEquals(FailedReason.VERIFICATION_FAILED.name, result.reason)
    }

    @Test
    fun shouldRefuseAnUnsignedManifestOnceAPublicKeyIsConfigured() = runBlocking {
        val harness = Harness(Fixture.configuration(publicKeys = listOf("k1")))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(FailedReason.INVALID_SIGNATURE.name, harness.core.sync(SyncTrigger.MANUAL).reason)
    }

    @Test
    fun shouldAdoptAReleaseCarryingTheRunningBundleWithoutAReload() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        val rollback = Fixture.release(2, "b2", v2Content)
        harness.publish(listOf(v2, rollback), 2, etag = "\"e2\"")
        assertEquals(SyncResult.updated(rollback.release.release, "notes 2", InstallMoment.IMMEDIATE), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals("r2", harness.core.getState().currentRelease?.id)
        assertEquals("r2", harness.core.getState().fallbackRelease?.id)
    }

    @Test
    fun shouldRevertToTheEmbeddedBundleWhenTheRunningReleaseIsRevoked() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.publish(listOf(v2), 2, revoked = listOf("r1"), etag = "\"e2\"")
        assertEquals(SyncResult.skipped(null, SkippedReason.RELEASE_REVOKED), harness.core.sync(SyncTrigger.MANUAL))
        assertNull(harness.loader.loaded.last())
        assertNull(harness.core.getState().currentRelease)
    }

    @Test
    fun shouldQueueTheSwitchWithTheReloadWhileRestartsAreNotAllowed() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.setRestartAllowed(false)
        assertEquals(InstallMoment.IMMEDIATE, harness.core.sync(SyncTrigger.MANUAL).installAt)
        assertTrue(harness.loader.loaded.isEmpty())
        val queued = harness.core.getState()
        assertNull(queued.currentRelease)
        assertEquals(v2.release.release, queued.nextRelease)
        assertTrue(harness.scheduler.tasks.isEmpty())
        assertTrue(StateStore(harness.store).unsentEvents.none { it.type == "applied" })
        harness.core.setRestartAllowed(true)
        assertEquals(listOf("b2"), harness.loader.loaded)
        val installed = harness.core.getState()
        assertEquals(v2.release.release, installed.currentRelease)
        assertNull(installed.nextRelease)
        assertEquals(1, harness.scheduler.tasks.size)
    }

    @Test
    fun shouldApplyUpdateAtOnceWhileRestartsAreNotAllowed() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.setRestartAllowed(false)
        assertEquals(InstallMoment.MANUAL, harness.core.sync(SyncTrigger.MANUAL).installAt)
        harness.core.applyUpdate()
        assertEquals(listOf("b2"), harness.loader.loaded)
        val status = harness.core.getState()
        assertEquals(v2.release.release, status.currentRelease)
        assertNull(status.nextRelease)
    }

    @Test
    fun shouldRollBackAtOnceWhileRestartsAreNotAllowed() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.core.setRestartAllowed(false)
        harness.core.rollback("fatal")
        assertEquals(listOf("b2", null), harness.loader.loaded)
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertEquals(listOf("b2"), status.failedBundleIds)
    }

    @Test
    fun shouldClearUpdatesAtOnceWhileRestartsAreNotAllowed() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.setRestartAllowed(false)
        harness.core.clearUpdates()
        assertEquals(listOf("b2", null), harness.loader.loaded)
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertTrue(harness.files.bundleIds().isEmpty())
    }

    @Test
    fun shouldInstallANextResumeReleaseAfterInstallOnResumeAfter() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.NEXT_RESUME))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(InstallMoment.NEXT_RESUME, harness.core.sync(SyncTrigger.MANUAL).installAt)
        harness.core.handleAppPause()
        harness.clock.now += 300_000
        harness.core.handleAppResume()
        assertEquals(listOf("b2"), harness.loader.loaded)
        val status = harness.core.getState()
        assertEquals(v2.release.release, status.currentRelease)
        assertNull(status.nextRelease)
        assertEquals(1, harness.scheduler.tasks.size)
    }

    @Test
    fun shouldKeepANextResumeReleaseWaitingAfterAShortBackground() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.NEXT_RESUME))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleAppResume()
        harness.core.handleAppPause()
        harness.clock.now += 299_000
        harness.core.handleAppResume()
        assertTrue(harness.loader.loaded.isEmpty())
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertEquals(v2.release.release, status.nextRelease)
    }

    @Test
    fun shouldSkipOnAMeteredConnectionUnderTheUnmeteredStrategy() = runBlocking {
        val harness = Harness()
        harness.loader.isMetered = true
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.skipped(v2.release.release, SkippedReason.METERED_CONNECTION), harness.core.sync(SyncTrigger.MANUAL, SyncOptions(downloadStrategy = DownloadStrategy.UNMETERED)))
    }

    @Test
    fun shouldResolveAChannelNameThroughTheChannelsIndex() = runBlocking {
        val harness = Harness()
        harness.http.stubJson("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/v1/index.json", org.json.JSONObject().put("schema", 1).put("channels", org.json.JSONArray().put(org.json.JSONObject().put("id", "c-staging").put("name", "staging"))))
        harness.http.stubJson("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/c-staging/android/v1/index.json", ChannelIndex(1, 1, Fixture.APP_ID, "c-staging", "android", false, null, emptyList(), emptyList()).toJson())
        harness.core.setChannel(ChannelChoice.Name("staging"))
        assertEquals(SyncResult.upToDate(null), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(ChannelResult("c-staging", "staging", ChannelSource.RUNTIME), harness.core.channel())
        harness.core.setChannel(ChannelChoice.Name("nowhere"))
        assertEquals(FailedReason.UNKNOWN_CHANNEL.name, harness.core.sync(SyncTrigger.MANUAL).reason)
    }

    @Test
    fun shouldMergeAttributesAndRefuseInvalidOnes() = runBlocking {
        val harness = Harness()
        harness.core.setAttributes(mapOf("plan" to "beta", "userId" to "42"))
        harness.core.setAttributes(mapOf("plan" to null))
        val device = harness.core.deviceResult()
        assertEquals(mapOf("userId" to "42"), device.attributes)
        assertEquals(ChannelSource.CONFIG, device.channel.source)
        assertEquals("fp1:abc", device.fingerprint)
        val error = runCatching { harness.core.setAttributes(mapOf("bad key" to "x")) }.exceptionOrNull()
        assertTrue(error is PlainException && error.message!!.contains("identifier"))
    }

    @Test
    fun shouldReportChecksOncePerRelease() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content, conditions = listOf(Condition.Os(">=99")))
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.sync(SyncTrigger.MANUAL)
        val events = StateStore(harness.store).unsentEvents.filter { it.type == "checked" }
        assertEquals(1, events.size)
        assertEquals(SkippedReason.INCOMPATIBLE.name, events[0].reason)
        assertEquals(ConditionType.OS, events[0].condition)
    }

    @Test
    fun shouldSendTheOutboxAndTheReportAfterASyncAndClearThemOnA202() = runBlocking {
        val harness = Harness()
        harness.acknowledgeEvents()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(1, harness.http.posts.size)
        assertEquals(Fixture.eventsUrl(), harness.http.posts[0].first)
        assertEquals("application/json", harness.http.posts[0].second["Content-Type"])
        val body = JSONObject(String(harness.http.posts[0].third))
        val state = StateStore(harness.store)
        assertEquals(state.deviceId, body.getString("deviceId"))
        assertEquals("android", body.getString("platform"))
        assertEquals("0.0.0", body.getString("sdkVersion"))
        assertEquals(listOf("checked", "downloaded"), body.getJSONArray("events").map { it.getString("type") })
        val report = body.getJSONObject("report")
        assertEquals(Fixture.CHANNEL_ID, report.getString("channelId"))
        assertEquals("config", report.getString("channelSource"))
        assertEquals("2.4.1", report.getString("binaryVersion"))
        assertEquals("fp1:abc", report.getString("fingerprint"))
        assertEquals("embedded", report.getString("embeddedBundleId"))
        assertTrue(report.isNull("releaseId"))
        assertTrue(state.unsentEvents.isEmpty())
        assertEquals(Iso8601.parse("2023-11-14T23:00:00.000Z"), state.reportedAt)
        assertEquals(Fixture.CHANNEL_ID, state.acknowledgedReport?.channelId)
        assertEquals(state.reportedAt, harness.core.getState().lastReportAt)
    }

    @Test
    fun shouldKeepTheOutboxWhenTheEventsEndpointFailsAndRetryAtTheNextSync() = runBlocking {
        val harness = Harness()
        harness.http.stub(Fixture.eventsUrl(), status = 500, body = ByteArray(0))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(1, harness.http.posts.size)
        assertEquals(2, StateStore(harness.store).unsentEvents.size)
        assertNull(StateStore(harness.store).reportedAt)
        harness.acknowledgeEvents()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(2, harness.http.posts.size)
        assertTrue(StateStore(harness.store).unsentEvents.isEmpty())
    }

    @Test
    fun shouldSendTheReportOncePerChangeAndAgainWhenTheMonthBegan() = runBlocking {
        val harness = Harness()
        harness.acknowledgeEvents()
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(1, harness.http.posts.size)
        harness.core.setAttributes(mapOf("plan" to "beta"))
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(2, harness.http.posts.size)
        val changed = JSONObject(String(harness.http.posts[1].third))
        assertEquals(mapOf("plan" to "beta"), changed.getJSONObject("report").getJSONObject("attributes").toStringMap())
        assertEquals(0, changed.getJSONArray("events").length())
        harness.clock.now += 40L * 86_400_000
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(3, harness.http.posts.size)
        assertTrue(!JSONObject(String(harness.http.posts[2].third)).isNull("report"))
    }

    @Test
    fun shouldSendNothingWhenLiveUpdatesAreOffInADebugBuild() = runBlocking {
        val harness = Harness(Fixture.configuration(enabledInDebugBuilds = false), isDebugBuild = true)
        harness.acknowledgeEvents()
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.skipped(null, SkippedReason.DEBUG_BUILD), harness.core.sync(SyncTrigger.MANUAL))
        assertTrue(harness.http.posts.isEmpty())
    }

    @Test
    fun shouldSyncOnStartAndResumeWhenAutoCheckIsOn() = runBlocking {
        val harness = Harness(Fixture.configuration(autoCheck = true))
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
        harness.core.handleAppResume()
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
        harness.clock.now += 1_000_000
        harness.restart(Fixture.configuration(autoCheck = true))
        harness.core.handleAppResume()
        assertEquals(SyncTrigger.RESUME, StateStore(harness.store).lastCheck?.trigger)
    }

    @Test
    fun shouldPauseTheIntervalTimerInTheBackgroundAndReArmItOnResume() = runBlocking {
        val harness = Harness(Fixture.configuration(autoCheck = true))
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        assertEquals(listOf(900.0), harness.scheduler.tasks.map { it.seconds })
        harness.core.handleAppPause()
        assertTrue(harness.scheduler.tasks[0].isCancelled)
        harness.clock.now += 600_000
        harness.core.handleAppResume()
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
        assertEquals(listOf(900.0, 300.0), harness.scheduler.tasks.map { it.seconds })
    }

    @Test
    fun shouldDeleteTheServedTreesAndFilesOfBundlesNoKeptReleaseLists() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        val v4 = Fixture.release(3, "b4", "<html>v4</html>".toByteArray())
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.publish(listOf(v2, v3), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.publish(listOf(v2, v3, v4), 3, etag = "\"e3\"")
        harness.core.sync(SyncTrigger.MANUAL, SyncOptions(installStrategy = InstallStrategy.NEXT_START))
        for (bundleId in listOf("b2", "b3", "b4")) assertTrue(bundleId, File(harness.loader.projectionDirectory(bundleId), "index.html").isFile)
        harness.loader.served = "b4"
        harness.restart(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        harness.core.handleAppStart()
        val status = harness.core.getState()
        assertEquals("b4", status.currentRelease?.bundleId)
        assertEquals("b3", status.fallbackRelease?.bundleId)
        assertTrue(!harness.loader.projectionDirectory("b2").exists())
        assertTrue(File(harness.loader.projectionDirectory("b3"), "index.html").isFile)
        assertTrue(File(harness.loader.projectionDirectory("b4"), "index.html").isFile)
        assertEquals(listOf("b3", "b4"), harness.files.bundleIds())
        assertTrue(!harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertTrue(!harness.files.hasFile(Hashing.sha256Hex("js-b2")))
        assertTrue(harness.files.hasFile(Hashing.sha256Hex("<html>v3</html>")))
        assertTrue(harness.files.hasFile(Hashing.sha256Hex("<html>v4</html>")))
    }

    @Test
    fun shouldClearUpdatesToTheEmbeddedBundleAndKeepTheIdentity() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        harness.core.setAttributes(mapOf("plan" to "beta"))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.clearUpdates()
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertTrue(status.failedBundleIds.isEmpty())
        assertTrue(!harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertNull(harness.loader.loaded.last())
        assertEquals(mapOf("plan" to "beta"), harness.core.deviceResult().attributes)
    }

    @Test
    fun shouldStopAfterTheCheckUnderTheManualDownloadStrategyAndDownloadOnCall() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.MANUAL, downloadStrategy = DownloadStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.available(v2.release.release, "notes 1", 15), harness.core.sync(SyncTrigger.MANUAL))
        assertTrue(!harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertEquals(1, harness.listener.available.size)
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1"), harness.core.downloadUpdate())
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertEquals(listOf(InstallMoment.MANUAL), harness.listener.downloaded.map { it.installAt })
        assertTrue(harness.loader.loaded.isEmpty())
        val state = harness.core.getState()
        assertEquals(v2.release.release, state.nextRelease)
        assertEquals(SyncStatus.AVAILABLE, state.lastCheck?.result?.status)
        assertEquals(ApplyResult(ApplyStatus.APPLIED, v2.release.release), harness.core.applyUpdate())
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(ApplyResult(ApplyStatus.NOTHING_TO_APPLY, v2.release.release), harness.core.applyUpdate())
    }

    @Test
    fun shouldDownloadOnCallWhateverTheConnectionUnderTheUnmeteredStrategy() = runBlocking {
        val harness = Harness(Fixture.configuration(downloadStrategy = DownloadStrategy.UNMETERED))
        harness.loader.isMetered = true
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.skipped(v2.release.release, SkippedReason.METERED_CONNECTION), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1"), harness.core.downloadUpdate())
        assertEquals(listOf(InstallMoment.NEXT_START), harness.listener.downloaded.map { it.installAt })
    }

    @Test
    fun shouldInstallAMandatoryReleaseAtOnceWhateverTheInstallStrategy() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.NEXT_START))
        val v2 = Fixture.release(1, "b2", v2Content, isMandatory = true)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(InstallMoment.IMMEDIATE, result.installAt)
        assertEquals(true, result.release?.isMandatory)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertTrue(harness.listener.downloaded.isEmpty())
    }

    @Test
    fun shouldHandAMandatoryReleaseToTheAppUnderTheManualMandatoryStrategy() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.NEXT_START, mandatoryInstallStrategy = MandatoryInstallStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content, isMandatory = true)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.updated(v2.release.release, "notes 1", InstallMoment.MANUAL), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(listOf(true), harness.listener.downloaded.map { it.release.isMandatory })
        assertTrue(harness.loader.loaded.isEmpty())
        harness.loader.served = null
        harness.restart(Fixture.configuration(installStrategy = InstallStrategy.NEXT_START, mandatoryInstallStrategy = MandatoryInstallStrategy.MANUAL))
        harness.core.handleAppStart()
        val state = harness.core.getState()
        assertNull(state.currentRelease)
        assertEquals(v2.release.release, state.nextRelease)
    }

    @Test
    fun shouldTreatTheNewestReleaseAsMandatoryWhenAMandatoryOneWasMissed() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.NEXT_START, mandatoryInstallStrategy = MandatoryInstallStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content, isMandatory = true)
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        harness.publish(listOf(v2, v3), 1)
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals("r2", result.release?.id)
        assertEquals(true, result.release?.isMandatory)
        assertEquals(InstallMoment.MANUAL, result.installAt)
    }

    @Test
    fun shouldCarryTheAppsRollbackReasonOnTheFailureEvent() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.core.rollback("checkout crashed")
        val failed = StateStore(harness.store).unsentEvents.last { it.type == "failed" }
        assertEquals(RollbackReason.REPORTED_BY_APP.name, failed.reason)
        assertEquals("checkout crashed", failed.detail)
        val error = runCatching { harness.core.rollback("a\nb") }.exceptionOrNull()
        assertTrue(error is PlainException)
    }

    @Test
    fun shouldSyncAndCleanUpAtAStartThatRollsBackACrash() = runBlocking {
        val harness = Harness(Fixture.configuration(autoCheck = true))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.loader.served = "b2"
        harness.restart(Fixture.configuration(autoCheck = true))
        harness.core.handleAppStart()
        harness.restart(Fixture.configuration(autoCheck = true))
        harness.core.handleAppStart()
        assertEquals(RollbackReason.CRASHED, harness.listener.rolledBack.last().reason)
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
        assertTrue(harness.files.bundleIds().isEmpty())
    }

    @Test
    fun shouldAnnounceTheRollbackWhenTheReloadRunsAndNotBefore() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.setRestartAllowed(false)
        harness.scheduler.fire()
        assertTrue(harness.listener.rolledBack.isEmpty())
        assertEquals(listOf("b2"), harness.loader.loaded)
        harness.core.setRestartAllowed(true)
        assertEquals(listOf("b2", null), harness.loader.loaded)
        assertEquals(listOf(RollbackReason.READY_TIMEOUT), harness.listener.rolledBack.map { it.reason })
    }

    @Test
    fun shouldFailOfflineNotUnknownWhenAChannelNameCannotBeResolved() = runBlocking {
        val harness = Harness()
        harness.core.setChannel(ChannelChoice.Name("staging"))
        harness.http.isOffline = true
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(FailedReason.OFFLINE.name, result.reason)
        assertEquals(listOf(FailedReason.OFFLINE), harness.listener.failed.map { it.reason })
    }

    @Test
    fun shouldFetchTheDeltaAgainstTheEmbeddedBundleOnTheFirstUpdate() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        val deltaUrl = "${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/bundles/b2/deltas/embedded"
        harness.publish(listOf(v2), 1)
        harness.http.stubJson(v2.release.manifestUrl, v2.envelope.copy(deltas = listOf(ManifestEnvelope.Delta("embedded", deltaUrl, v2.pack.size.toLong()))).toJson())
        harness.http.stub(deltaUrl, body = v2.pack)
        harness.core.handleAppStart()
        assertEquals(SyncStatus.UPDATED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertTrue(harness.http.requests.any { it.first == deltaUrl })
        assertEquals(PackKind.DELTA.wire, StateStore(harness.store).unsentEvents.first { it.type == "downloaded" }.packKind)
    }

    @Test
    fun shouldDiscardADownloadedReleaseRevokedBeforeTheStartThatWouldInstallIt() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.publish(listOf(v2), 2, revoked = listOf("r1"), etag = "\"e2\"")
        assertEquals(SyncResult.upToDate(null), harness.core.sync(SyncTrigger.MANUAL))
        harness.loader.served = "b2"
        harness.restart()
        harness.core.handleAppStart()
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertNull(status.nextRelease)
        assertTrue(harness.loader.hasPersisted)
        assertNull(harness.loader.persisted)
        assertEquals(listOf<String?>(null), harness.loader.loaded)
    }

    @Test
    fun shouldDiscardADownloadedReleaseThatLeftTheIndexInsteadOfApplyingIt() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.updated(v2.release.release, "notes 1", InstallMoment.MANUAL), harness.core.sync(SyncTrigger.MANUAL))
        harness.publish(emptyList(), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(ApplyResult(ApplyStatus.NOTHING_TO_APPLY, null), harness.core.applyUpdate())
        assertTrue(harness.loader.loaded.isEmpty())
        assertNull(harness.core.getState().nextRelease)
    }
}
