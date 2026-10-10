package com.hotcodepush.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    private val immediateApply = Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE)

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
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), result)
        assertEquals("b2", harness.loader.persisted)
        assertTrue(harness.loader.loaded.isEmpty())
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertEquals("<html>v2</html>", File(harness.loader.projectionDirectory("b2"), "index.html").readText())
        val status = harness.core.getState()
        assertEquals(v2.release.release, status.nextRelease)
        assertNull(status.currentRelease)
        assertEquals(1L, status.indexSequence)

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
    fun shouldAnswerTheReleaseBeforeTheStartAsPreviousReleaseOnceAfterASwitchAtTheStart() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.restart()
        harness.core.handleAppStart()
        harness.core.notifyReady()
        harness.publish(listOf(v2, v3), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.restart()
        harness.core.handleAppStart()
        assertEquals(NotifyReadyResult(v3.release.release, v2.release.release, false, null), harness.core.notifyReady())
        assertEquals(NotifyReadyResult(v3.release.release, null, false, null), harness.core.notifyReady())
    }

    @Test
    fun shouldAnswerTheReleaseBeforeTheReloadAsPreviousReleaseAfterAnImmediateApply() = runBlocking {
        val harness = Harness(immediateApply)
        val v2 = Fixture.release(1, "b2", v2Content)
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.publish(listOf(v2, v3), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(NotifyReadyResult(v3.release.release, v2.release.release, false, null), harness.core.notifyReady())
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleWhenTheBinaryChanged() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
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
    fun shouldTakeTheFetchedIndexAfterANewBinaryWhenTheCachedIndexHasAFarFutureSequence() = runBlocking {
        val harness = Harness()
        harness.publish(emptyList(), 9_999_999_999_999)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.publish(emptyList(), 1_759_900_000_000, etag = "\"e2\"")
        harness.restart(Fixture.configuration(builtAt = Fixture.BUILT_AT + 60_000))
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(1_759_900_000_000L, harness.core.getState().indexSequence)
        assertEquals(Fixture.indexUrl() to emptyMap<String, String>(), harness.http.requests.last())
    }

    @Test
    fun shouldKeepTheCurrentReleaseWhenTheBinaryIsTheSame() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
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
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
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
        assertTrue(harness.listener.rollbacks.isEmpty())
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
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(SyncStatus.APPLIED, result.status)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(1, harness.scheduler.tasks.size)
        harness.scheduler.fire()
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertEquals(listOf("b2"), status.failedBundleIds)
        assertEquals(listOf("b2", null), harness.loader.loaded)
        assertEquals(SyncResult.skipped(v2.release.release, SkippedReason.BUNDLE_FAILED_BEFORE), harness.core.sync(SyncTrigger.MANUAL))
        val ready = harness.core.notifyReady()
        assertTrue(ready.isRolledBack)
        assertEquals(RollbackReason.READINESS_TIMED_OUT, ready.rollbackReason)
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
        assertEquals(RollbackReason.APP_CRASHED, harness.listener.rollbacks.last().reason)
        assertNull(harness.loader.loaded.last())
    }

    @Test
    fun shouldFallBackToTheLastConfirmedReleaseNotTheEmbeddedBundle() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        harness.publish(listOf(v2, v3), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.rollbackUpdate("fatal")
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
        assertEquals(SyncStatus.DOWNLOADED, harness.core.sync(SyncTrigger.MANUAL).status)
        harness.http.isOffline = false
        harness.publish(emptyList(), 4, etag = "\"e0\"")
        assertEquals(SyncStatus.DOWNLOADED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertEquals(5L, harness.core.getState().indexSequence)
    }

    @Test
    fun shouldIgnoreAnOlderSequenceWhenTheCachedIndexIsYoungerThanADay() = runBlocking {
        val harness = Harness()
        harness.publish(emptyList(), 5)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.clock.now += 23 * 3_600_000L
        harness.publish(emptyList(), 4, etag = "\"e0\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(5L, harness.core.getState().indexSequence)
    }

    @Test
    fun shouldTakeAnOlderSequenceWhenTheCachedIndexIsOlderThanADay() = runBlocking {
        val harness = Harness()
        harness.publish(emptyList(), 5)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.clock.now += 25 * 3_600_000L
        harness.publish(emptyList(), 4, etag = "\"e0\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(4L, harness.core.getState().indexSequence)
        assertEquals(harness.clock.now, harness.core.getState().indexFetchedAt)
    }

    @Test
    fun shouldKeepASequenceOfMillisecondsAndIgnoreOneMillisecondOlder() = runBlocking {
        val harness = Harness()
        harness.publish(emptyList(), 1_759_900_000_000)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(1_759_900_000_000L, StateStore(harness.store).cachedIndex?.body?.sequence)
        harness.publish(emptyList(), 1_759_900_000_001, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.publish(emptyList(), 1_759_900_000_000, etag = "\"e1\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(1_759_900_000_001L, harness.core.getState().indexSequence)
    }

    @Test
    fun shouldFailOfflineWithoutACachedIndex() = runBlocking {
        val harness = Harness()
        harness.http.isOffline = true
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(SyncStatus.FAILED, result.status)
        assertEquals(FailedReason.DEVICE_OFFLINE.name, result.reason)
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
    fun shouldRefuseATamperedManifestAsInvalid() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.http.stubJson(v2.release.manifestUrl, v2.envelope.copy(manifest = v2.envelope.manifest + " ").toJson())
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(SyncStatus.FAILED, result.status)
        assertEquals(FailedReason.MANIFEST_INVALID.name, result.reason)
    }

    @Test
    fun shouldRefuseAnUnsignedManifestOnceAPublicKeyIsConfigured() = runBlocking {
        val harness = Harness(Fixture.configuration(publicKeys = listOf(SignatureFixtures().devicePublicKey("rsa-4096-a"))))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(FailedReason.SIGNATURE_INVALID.name, harness.core.sync(SyncTrigger.MANUAL).reason)
    }

    @Test
    fun shouldApplyAManifestSignedByAListedKey() = runBlocking {
        val fixture = SignatureFixtures()
        val harness = Harness(Fixture.configuration(publicKeys = listOf(fixture.devicePublicKey("rsa-4096-b"), fixture.devicePublicKey("rsa-4096-a"))))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.http.stubJson(v2.release.manifestUrl, v2.envelope.copy(signature = fixture.sign(v2.envelope.manifest, "rsa-4096-a")).toJson())
        harness.core.handleAppStart()
        assertEquals(SyncStatus.DOWNLOADED, harness.core.sync(SyncTrigger.MANUAL).status)
    }

    @Test
    fun shouldRefuseAManifestSignedByAKeyTheAppDoesNotHold() = runBlocking {
        val fixture = SignatureFixtures()
        val harness = Harness(Fixture.configuration(publicKeys = listOf(fixture.devicePublicKey("rsa-4096-b"))))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.http.stubJson(v2.release.manifestUrl, v2.envelope.copy(signature = fixture.sign(v2.envelope.manifest, "rsa-4096-a")).toJson())
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(FailedReason.SIGNATURE_INVALID.name, result.reason)
        assertTrue(result.message?.contains("does not hold") == true)
        assertEquals(FailedReason.SIGNATURE_INVALID, harness.listener.failed.single().reason)
    }

    @Test
    fun shouldAdoptAReleaseCarryingTheRunningBundleWithoutAReloadAndAnswerUpToDate() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        val rollback = Fixture.release(2, "b2", v2Content)
        harness.publish(listOf(v2, rollback), 2, etag = "\"e2\"")
        assertEquals(SyncResult.upToDate(rollback.release.release), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals("r2", harness.core.getState().currentRelease?.id)
        assertEquals("r2", harness.core.getState().fallbackRelease?.id)
    }

    @Test
    fun shouldAnswerUpToDateWhenADownloadAdoptsAReleaseCarryingTheRunningBundle() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        val rollback = Fixture.release(2, "b2", v2Content)
        harness.publish(listOf(v2, rollback), 2, etag = "\"e2\"")
        assertEquals(SyncResult.upToDate(rollback.release.release), harness.core.downloadUpdate())
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals("r2", harness.core.getState().currentRelease?.id)
    }

    @Test
    fun shouldDiscardAHeldApplyWhoseReleaseWasRevokedWhileItWaitedAndReloadNothing() = runBlocking {
        val harness = Harness(immediateApply)
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.applied(v2.release.release, "notes 1"), harness.core.sync(SyncTrigger.MANUAL))
        harness.publish(listOf(v2), 2, revoked = listOf("r1"), etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleRendered()
        assertTrue(harness.loader.loaded.isEmpty())
        assertNull(harness.core.getState().currentRelease)
        assertNull(harness.core.getState().nextRelease)
        assertTrue(harness.loader.hasPersisted && harness.loader.persisted == null)
    }

    @Test
    fun shouldDiscardAHeldApplyUpdateWhoseReleaseWasRevokedWhileItWaitedAndReloadNothing() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(ApplyUpdateResult(ApplyStatus.APPLIED, v2.release.release), harness.core.applyUpdate())
        harness.publish(listOf(v2), 2, revoked = listOf("r1"), etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleRendered()
        assertTrue(harness.loader.loaded.isEmpty())
        assertNull(harness.core.getState().currentRelease)
        assertNull(harness.core.getState().nextRelease)
    }

    @Test
    fun shouldRevertToTheEmbeddedBundleWhenTheRunningReleaseIsRevoked() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.publish(listOf(v2), 2, revoked = listOf("r1"), etag = "\"e2\"")
        assertEquals(SyncResult.skipped(null, SkippedReason.RELEASE_REVOKED), harness.core.sync(SyncTrigger.MANUAL))
        assertNull(harness.loader.loaded.last())
        assertNull(harness.core.getState().currentRelease)
    }

    @Test
    fun shouldQueueTheSwitchWithTheReloadWhileRestartsAreNotAllowed() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.setRestartAllowed(false)
        assertEquals(SyncStatus.APPLIED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertTrue(harness.loader.loaded.isEmpty())
        val queued = harness.core.getState()
        assertNull(queued.currentRelease)
        assertEquals(v2.release.release, queued.nextRelease)
        assertTrue(harness.scheduler.tasks.isEmpty())
        assertTrue(StateStore(harness.store).unsentEvents.none { it.type == "applied" })
        harness.core.setRestartAllowed(true)
        assertEquals(listOf("b2"), harness.loader.loaded)
        val applied = harness.core.getState()
        assertEquals(v2.release.release, applied.currentRelease)
        assertNull(applied.nextRelease)
        assertEquals(1, harness.scheduler.tasks.size)
    }

    @Test
    fun shouldApplyAnUpdateAtTheFirstRenderAndNotBeforeWhileRestartsAreNotAllowed() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(ApplyStrategy.MANUAL, harness.core.sync(SyncTrigger.MANUAL).applyAt)
        assertEquals(ApplyUpdateResult(ApplyStatus.APPLIED, v2.release.release), harness.core.applyUpdate())
        assertTrue(harness.loader.loaded.isEmpty())
        assertNull(harness.core.getState().currentRelease)
        harness.core.setRestartAllowed(false)
        harness.core.handleRendered()
        assertEquals(listOf("b2"), harness.loader.loaded)
        val status = harness.core.getState()
        assertEquals(v2.release.release, status.currentRelease)
        assertNull(status.nextRelease)
    }

    @Test
    fun shouldRollBackAtOnceWhileRestartsAreNotAllowed() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.core.setRestartAllowed(false)
        harness.core.rollbackUpdate("fatal")
        assertEquals(listOf("b2", null), harness.loader.loaded)
        val status = harness.core.getState()
        assertNull(status.currentRelease)
        assertEquals(listOf("b2"), status.failedBundleIds)
    }

    @Test
    fun shouldClearUpdatesAtTheFirstRenderAndNotBeforeWhileRestartsAreNotAllowed() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.clearUpdates()
        assertTrue(harness.loader.loaded.isEmpty())
        assertEquals(v2.release.release, harness.core.getState().nextRelease)
        assertEquals(listOf("b2"), harness.files.bundleIds())
        harness.core.setRestartAllowed(false)
        harness.core.handleRendered()
        assertEquals(listOf<String?>(null), harness.loader.loaded)
        assertNull(harness.core.getState().nextRelease)
        assertTrue(harness.files.bundleIds().isEmpty())
    }

    @Test
    fun shouldApplyANextResumeReleaseAfterApplyOnResumeAfterSeconds() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.NEXT_RESUME))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        assertEquals(ApplyStrategy.NEXT_RESUME, harness.core.sync(SyncTrigger.MANUAL).applyAt)
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
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.NEXT_RESUME))
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
    fun shouldKeepAMandatoryReleaseTheAppTookOverWaitingAtAResumeUnderNextResume() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.NEXT_RESUME, mandatoryApplyStrategy = MandatoryApplyStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content, isMandatory = true)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.MANUAL), harness.core.sync(SyncTrigger.MANUAL))
        harness.core.handleAppPause()
        harness.clock.now += 600_000
        harness.core.handleAppResume()
        assertTrue(harness.loader.loaded.isEmpty())
        assertEquals(v2.release.release, harness.core.getState().nextRelease)
        assertNull(harness.core.getState().currentRelease)
    }

    @Test
    fun shouldKeepAReleaseDownloadedUnderACallsManualApplyWaitingAtTheNextStartWhateverTheConfigurationSays() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.MANUAL), harness.core.sync(SyncTrigger.MANUAL, SyncOptions(applyStrategy = ApplyStrategy.MANUAL)))
        startAgain(harness, Fixture.configuration())
        assertNull(harness.core.getState().currentRelease)
        assertEquals(v2.release.release, harness.core.getState().nextRelease)
        harness.core.handleRendered()
        assertEquals(ApplyUpdateResult(ApplyStatus.APPLIED, v2.release.release), harness.core.applyUpdate())
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldApplyAReleaseDownloadedUnderACallsNextResumeAtTheResumeAndNotAtTheNextStart() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL, SyncOptions(applyStrategy = ApplyStrategy.NEXT_RESUME))
        startAgain(harness, Fixture.configuration())
        assertNull(harness.core.getState().currentRelease)
        harness.core.handleRendered()
        harness.core.handleAppPause()
        harness.clock.now += 300_000
        harness.core.handleAppResume()
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
    }

    @Test
    fun shouldKeepTheMomentDecidedAtTheDownloadWhenAnAutomaticCycleFindsTheReleaseDownloaded() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.MANUAL), harness.core.downloadUpdate(DownloadUpdateOptions(applyStrategy = ApplyStrategy.MANUAL)))
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.MANUAL), harness.core.sync(SyncTrigger.INTERVAL))
        startAgain(harness, Fixture.configuration())
        assertNull(harness.core.getState().currentRelease)
        assertEquals(listOf(ApplyStrategy.MANUAL), harness.listener.downloaded.map { it.applyAt })
    }

    @Test
    fun shouldDecideTheMomentAgainWhenACallWithItsOwnStrategyFindsTheReleaseDownloaded() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.downloadUpdate(DownloadUpdateOptions(applyStrategy = ApplyStrategy.MANUAL))
        assertEquals(SyncResult.applied(v2.release.release, "notes 1"), harness.core.downloadUpdate(DownloadUpdateOptions(applyStrategy = ApplyStrategy.IMMEDIATE)))
        harness.core.handleRendered()
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldApplyADownloadUnderTheCallsApplyStrategies() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_RESUME), harness.core.downloadUpdate(DownloadUpdateOptions(applyStrategy = ApplyStrategy.NEXT_RESUME)))
        val mandatory = Harness()
        val v3 = Fixture.release(1, "b3", v2Content, isMandatory = true)
        mandatory.publish(listOf(v3), 1)
        mandatory.core.handleAppStart()
        assertEquals(SyncResult.downloaded(v3.release.release, "notes 1", ApplyStrategy.MANUAL), mandatory.core.downloadUpdate(DownloadUpdateOptions(mandatoryApplyStrategy = MandatoryApplyStrategy.MANUAL)))
    }

    @Test
    fun shouldCheckTheIndexAgainBeforeADownloadAndDownloadNothingRevokedSinceTheCheck() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncStatus.AVAILABLE, harness.core.checkForUpdate().status)
        harness.publish(listOf(v2), 2, revoked = listOf(v2.release.id), etag = "\"e2\"")
        assertEquals(SyncResult.upToDate(null), harness.core.downloadUpdate())
        assertEquals("\"e1\"", harness.http.requests.last { it.first == Fixture.indexUrl() }.second["If-None-Match"])
        assertTrue(harness.http.requests.none { it.first == v2.envelope.pack.url || it.first == v2.release.manifestUrl })
        assertNull(harness.core.getState().nextRelease)
    }

    @Test
    fun shouldSkipOnAMeteredConnectionUnderTheUnmeteredStrategy() = runBlocking {
        val harness = Harness()
        harness.loader.isMetered = true
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.skipped(v2.release.release, SkippedReason.CONNECTION_METERED), harness.core.sync(SyncTrigger.MANUAL, SyncOptions(downloadStrategy = DownloadStrategy.UNMETERED)))
    }

    @Test
    fun shouldResolveAChannelNameThroughTheChannelsIndex() = runBlocking {
        val harness = Harness()
        harness.http.stubJson("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/v1/index.json", org.json.JSONObject().put("schema", 1).put("channels", org.json.JSONArray().put(org.json.JSONObject().put("id", "5ab00000-0000-4000-8000-000000000002").put("name", "staging"))))
        harness.http.stubJson("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/5ab00000-0000-4000-8000-000000000002/android/v1/index.json", ChannelIndex(1, 1, Fixture.APP_ID, "5ab00000-0000-4000-8000-000000000002", "android", false, null, emptyList(), emptyList()).toJson())
        harness.core.setChannel(ChannelChoice.Name("staging"))
        assertEquals(SyncResult.upToDate(null), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(ChannelResult("5ab00000-0000-4000-8000-000000000002", "staging", ChannelSource.RUNTIME), harness.core.channel())
        harness.core.setChannel(ChannelChoice.Name("nowhere"))
        assertEquals(FailedReason.CHANNEL_UNKNOWN.name, harness.core.sync(SyncTrigger.MANUAL).reason)
    }

    @Test
    fun shouldAnswerNoIdForARuntimeNameBeforeASyncResolvedItAndTheIdAfter() = runBlocking {
        val harness = Harness()
        harness.http.stubJson("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/v1/index.json", org.json.JSONObject().put("schema", 1).put("channels", org.json.JSONArray().put(org.json.JSONObject().put("id", "5ab00000-0000-4000-8000-000000000002").put("name", "staging"))))
        harness.http.stubJson("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/5ab00000-0000-4000-8000-000000000002/android/v1/index.json", ChannelIndex(1, 1, Fixture.APP_ID, "5ab00000-0000-4000-8000-000000000002", "android", false, null, emptyList(), emptyList()).toJson())
        harness.core.setChannel(ChannelChoice.Name("staging"))
        assertEquals(ChannelResult(null, "staging", ChannelSource.RUNTIME), harness.core.channel())
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(ChannelResult("5ab00000-0000-4000-8000-000000000002", "staging", ChannelSource.RUNTIME), harness.core.channel())
    }

    @Test
    fun shouldRefuseARuntimeChannelIdThatIsNotAUuidAndKeepTheChannel() = runBlocking {
        val harness = Harness()
        for (id in listOf("../../other-app/channels/${Fixture.CHANNEL_ID}", "c1", "${Fixture.CHANNEL_ID}?x=1", "")) {
            val error = runCatching { harness.core.setChannel(ChannelChoice.Id(id)) }.exceptionOrNull()
            assertTrue(id, error is PlainException)
        }
        assertEquals(ChannelResult(Fixture.CHANNEL_ID, null, ChannelSource.CONFIG), harness.core.channel())
        assertTrue(harness.http.requests.isEmpty())
    }

    @Test
    fun shouldRefuseEveryCallAndFetchNothingWhenTheConfiguredChannelIdIsNotAUuid() = runBlocking {
        val harness = Harness(Fixture.configuration(channelId = "../../other-app/channels/${Fixture.CHANNEL_ID}"))
        harness.core.handleAppStart()
        assertTrue(runCatching { harness.core.sync(SyncTrigger.MANUAL) }.exceptionOrNull() is PlainException)
        assertTrue(runCatching { harness.core.checkForUpdate() }.exceptionOrNull() is PlainException)
        assertTrue(runCatching { harness.core.downloadUpdate() }.exceptionOrNull() is PlainException)
        assertTrue(harness.http.requests.isEmpty())
        assertTrue(harness.listener.failed.isEmpty())
    }

    @Test
    fun shouldFailWithIndexInvalidAndCacheNothingWhenTheIndexNamesAnotherAppChannelOrPlatform() = runBlocking {
        val harness = Harness()
        val index = Fixture.index(1, listOf(Fixture.release(1, "b2", v2Content).release))
        for (other in listOf(index.copy(appId = "another-app"), index.copy(channelId = "5ab00000-0000-4000-8000-000000000002"), index.copy(platform = "ios"))) {
            harness.http.stubJson(Fixture.indexUrl(), other.toJson())
            val result = harness.core.sync(SyncTrigger.MANUAL)
            assertEquals(FailedReason.INDEX_INVALID.name, result.reason)
            assertNull(harness.core.getState().indexSequence)
        }
    }

    @Test
    fun shouldFailWithIndexInvalidWhenTheChannelsIndexNamesTheChannelByAnIdThatIsNotAUuid() = runBlocking {
        val harness = Harness()
        harness.http.stubJson("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/v1/index.json", org.json.JSONObject().put("schema", 1).put("channels", org.json.JSONArray().put(org.json.JSONObject().put("id", "../../other-app/channels/c1").put("name", "staging"))))
        harness.core.setChannel(ChannelChoice.Name("staging"))
        assertEquals(FailedReason.INDEX_INVALID.name, harness.core.sync(SyncTrigger.MANUAL).reason)
        assertEquals(listOf("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/channels/v1/index.json"), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldAnswerFailedAndSyncAgainWhenSomethingThrowsInsideACycle() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.listener.updateAvailableFailure = IllegalStateException("the listener broke")
        val failed = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(FailedReason.INDEX_INVALID.name, failed.reason)
        assertTrue(failed.message, failed.message!!.contains("the listener broke"))
        harness.listener.updateAvailableFailure = null
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), harness.core.sync(SyncTrigger.MANUAL))
    }

    @Test
    fun shouldLogAndNeverThrowOutOfAnAutomaticCycleAndSyncAgainAfterIt() = runBlocking {
        val harness = Harness(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        harness.http.isOffline = true
        harness.listener.updateFailedFailure = IllegalStateException("the listener broke")
        harness.core.handleAppStart()
        assertTrue(harness.uncaught.isEmpty())
        assertTrue(harness.core.debugSnapshot().log.any { it.message.contains("the listener broke") })
        harness.listener.updateFailedFailure = null
        assertEquals(FailedReason.DEVICE_OFFLINE.name, harness.core.sync(SyncTrigger.MANUAL).reason)
        assertEquals(1, harness.listener.failed.size)
    }

    @Test
    fun shouldFailTheDownloadAndKeepTheProcessWhenTheDownloadRunsOutOfMemory() = runBlocking {
        val harness = Harness()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.http.downloadFailure = OutOfMemoryError("a large file")
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(FailedReason.DOWNLOAD_FAILED.name, result.reason)
        assertEquals(FailedReason.DOWNLOAD_FAILED.name, StateStore(harness.store).unsentEvents.last().reason)
        assertNull(harness.core.getState().nextRelease)
    }

    @Test
    fun shouldDownloadOnceWhenASyncASecondDownloadAndACheckAreAskedWhileADownloadRuns() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.http.downloadGate = CompletableDeferred()
        val download = harness.scope.async { harness.core.downloadUpdate() }
        val sync = harness.scope.async { harness.core.sync(SyncTrigger.MANUAL) }
        val secondDownload = harness.scope.async { harness.core.downloadUpdate() }
        val check = harness.scope.async { harness.core.checkForUpdate() }
        assertEquals(1, harness.http.requests.count { it.first.contains("/deltas/") })
        assertEquals(1, harness.http.requests.count { it.first == Fixture.indexUrl() })
        harness.http.downloadGate?.complete(Unit)
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), download.await())
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), sync.await())
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), secondDownload.await())
        assertEquals(SyncResult.available(v2.release.release, "notes 1", v2.release.sizeBytes), check.await())
        assertEquals(1, harness.http.requests.count { it.first.contains("/deltas/") })
        assertEquals(1, harness.http.requests.count { it.first == v2.envelope.pack.url })
    }

    @Test
    fun shouldSendTheEventsWithoutTheReportAndLogItWhenAStoredAttributeIsOneTheEventsEndpointRefuses() = runBlocking {
        val harness = Harness()
        harness.acknowledgeEvents()
        StateStore(harness.store).attributes = mapOf("plan" to "beta\u0085")
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.sync(SyncTrigger.MANUAL)
        val batch = JSONObject(String(harness.http.posts.last().third))
        assertTrue(batch.isNull("report"))
        assertTrue(batch.getJSONArray("events").length() > 0)
        assertTrue(harness.core.debugSnapshot().log.any { it.code == "REPORT_UNREADABLE" })
    }

    @Test
    fun shouldSendTheEventsWithoutTheReportWhenTheBinaryVersionIsEmpty() = runBlocking {
        val harness = Harness(device = DeviceFacts("android", "", "57", "14", "0.0.0", false))
        harness.acknowledgeEvents()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(JSONObject(String(harness.http.posts.last().third)).isNull("report"))
    }

    @Test
    fun shouldKeepTheBatchInTheOutboxAndSendNothingWhenTheSdkVersionIsOneTheEventsEndpointRefuses() = runBlocking {
        val harness = Harness(device = DeviceFacts("android", "2.4.1", "57", "14", "0.0.0\u0000", false))
        harness.acknowledgeEvents()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(harness.http.posts.isEmpty())
        assertTrue(StateStore(harness.store).unsentEvents.isNotEmpty())
        assertTrue(harness.core.debugSnapshot().log.any { it.code == "REPORT_UNREADABLE" })
    }

    @Test
    fun shouldJoinARunningDownloadWhenTheAppAsksForTheDownloadAgain() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.http.downloadGate = CompletableDeferred()
        val download = harness.scope.async { harness.core.downloadUpdate() }
        val secondDownload = harness.scope.async { harness.core.downloadUpdate() }
        harness.http.downloadGate?.complete(Unit)
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), download.await())
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), secondDownload.await())
        assertEquals(1, harness.http.requests.count { it.first == Fixture.indexUrl() })
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
        assertEquals(SkippedReason.DEVICE_INCOMPATIBLE.name, events[0].reason)
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
        assertEquals(state.reportedAt, harness.core.getState().reportedAt)
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
    fun shouldDropTheBatchsEventsAndKeepTheReportUnacknowledgedWhenTheEndpointAnswers400() = runBlocking {
        assertBatchRefused(400)
    }

    @Test
    fun shouldDropTheBatchsEventsAndKeepTheReportUnacknowledgedWhenTheEndpointAnswers404() = runBlocking {
        assertBatchRefused(404)
    }

    @Test
    fun shouldDropTheBatchsEventsAndKeepTheReportUnacknowledgedWhenTheEndpointAnswers422() = runBlocking {
        assertBatchRefused(422)
    }

    @Test
    fun shouldKeepTheOutboxWhenTheEndpointAnswers408() = runBlocking {
        val harness = Harness()
        harness.http.stub(Fixture.eventsUrl(), status = 408, body = ByteArray(0))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(1, harness.http.posts.size)
        assertEquals(2, StateStore(harness.store).unsentEvents.size)
        assertNull(StateStore(harness.store).reportedAt)
    }

    @Test
    fun shouldKeepTheOutboxWhenTheEndpointDoesNotAnswer() = runBlocking {
        val harness = Harness()
        harness.http.stub(Fixture.eventsUrl(), status = 500, body = ByteArray(0))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.http.isOffline = true
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(2, harness.http.posts.size)
        assertEquals(2, StateStore(harness.store).unsentEvents.size)
        assertEquals("2 events kept for the next sync: the events endpoint could not be reached", harness.core.debugSnapshot().log.last().message)
    }

    @Test
    fun shouldKeepAnEventEnqueuedWhileARefusedBatchWasInFlight() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.http.stub(Fixture.eventsUrl(), status = 400, body = ByteArray(0))
        harness.http.whilePosting = { harness.core.notifyReady() }
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        val sent = JSONObject(String(harness.http.posts.first().third))
        assertEquals(listOf("checked", "downloaded", "applied"), sent.getJSONArray("events").map { it.getString("type") })
        assertEquals(listOf(DeviceEvent.confirmed("r1")), StateStore(harness.store).unsentEvents)
    }

    @Test
    fun shouldKeepAnEventEnqueuedWhileAnAcknowledgedBatchWasInFlightWhenTheOutboxWasAtItsCap() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.acknowledgeEvents()
        harness.http.whilePosting = { harness.core.notifyReady() }
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        StateStore(harness.store).unsentEvents = List(200) { DeviceEvent.applied("r-old-$it") }
        harness.core.sync(SyncTrigger.MANUAL)
        val sent = JSONObject(String(harness.http.posts.first().third))
        assertEquals(200, sent.getJSONArray("events").length())
        assertEquals(listOf(DeviceEvent.confirmed("r1")), StateStore(harness.store).unsentEvents)
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
    fun shouldKeepTheReportedAtWhenABatchOfEventsAloneIsAcknowledgedLaterInTheMonth() = runBlocking {
        val harness = Harness()
        harness.acknowledgeEvents("2023-11-14T23:00:00.000Z")
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.acknowledgeEvents("2023-11-20T12:00:00.000Z")
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(JSONObject(String(harness.http.posts[1].third)).isNull("report"))
        assertEquals(Iso8601.parse("2023-11-14T23:00:00.000Z"), harness.core.getState().reportedAt)
    }

    @Test
    fun shouldKeepTheReportedAtWhenAReportWithAChangedFactIsAcknowledgedLaterInTheMonth() = runBlocking {
        val harness = Harness()
        harness.acknowledgeEvents("2023-11-14T23:00:00.000Z")
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.acknowledgeEvents("2023-11-20T12:00:00.000Z")
        harness.core.setAttributes(mapOf("plan" to "beta"))
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(mapOf("plan" to "beta"), StateStore(harness.store).acknowledgedReport?.attributes)
        assertEquals(Iso8601.parse("2023-11-14T23:00:00.000Z"), harness.core.getState().reportedAt)
    }

    @Test
    fun shouldTakeTheReportedAtOfTheFirstReportAcknowledgedInALaterMonth() = runBlocking {
        val harness = Harness()
        harness.acknowledgeEvents("2023-11-14T23:00:00.000Z")
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.clock.now += 40L * 86_400_000
        harness.acknowledgeEvents("2023-12-24T23:00:00.000Z")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(Iso8601.parse("2023-12-24T23:00:00.000Z"), harness.core.getState().reportedAt)
    }

    @Test
    fun shouldSendNothingWhenLiveUpdatesAreOffInADebugBuild() = runBlocking {
        val harness = Harness(Fixture.configuration(enabledInDebugBuilds = false), isDebugBuild = true)
        harness.acknowledgeEvents()
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.skipped(null, SkippedReason.BUILD_DEBUG), harness.core.sync(SyncTrigger.MANUAL))
        assertTrue(harness.http.posts.isEmpty())
    }

    @Test
    fun shouldSyncOnStartAndResumeWhenTheCheckStrategyIsAuto() = runBlocking {
        val harness = Harness(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
        harness.core.handleAppResume()
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
        harness.clock.now += 1_000_000
        harness.restart(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        harness.core.handleAppResume()
        assertEquals(SyncTrigger.RESUME, StateStore(harness.store).lastCheck?.trigger)
    }

    @Test
    fun shouldPauseTheIntervalTimerInTheBackgroundAndReArmItOnResume() = runBlocking {
        val harness = Harness(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
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
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
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
        harness.core.notifyReady()
        harness.publish(listOf(v2, v3, v4), 3, etag = "\"e3\"")
        harness.core.sync(SyncTrigger.MANUAL, SyncOptions(applyStrategy = ApplyStrategy.NEXT_START))
        for (bundleId in listOf("b2", "b3", "b4")) assertTrue(bundleId, File(harness.loader.projectionDirectory(bundleId), "index.html").isFile)
        harness.loader.served = "b4"
        harness.restart(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
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
    fun shouldStartWithoutWaitingForTheCleanupAndCleanUpOnceTheRunningDownloadEnded() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        val stale = "<html>stale</html>".toByteArray()
        harness.files.writeFile(stale, Hashing.sha256Hex(stale))
        harness.files.writeManifest(DownloaderHarness.manifest(listOf(BundleManifest.File("index.html", Hashing.sha256Hex(stale), stale.size.toLong()))), "b0")
        harness.http.downloadGate = CompletableDeferred()
        val sync = harness.scope.async { harness.core.sync(SyncTrigger.MANUAL) }
        harness.core.handleAppStart()
        assertEquals(listOf("b0"), harness.files.bundleIds())
        harness.http.downloadGate?.complete(Unit)
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), sync.await())
        assertEquals(listOf("b2"), harness.files.bundleIds())
        assertTrue(!harness.files.hasFile(Hashing.sha256Hex(stale)))
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(v2Content)))
    }

    @Test
    fun shouldClearUpdatesToTheEmbeddedBundleAndKeepTheIdentity() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.core.setAttributes(mapOf("plan" to "beta"))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleRendered()
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
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.MANUAL, downloadStrategy = DownloadStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        assertEquals(SyncResult.available(v2.release.release, "notes 1", 15), harness.core.sync(SyncTrigger.MANUAL))
        assertTrue(!harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertEquals(1, harness.listener.available.size)
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.MANUAL), harness.core.downloadUpdate())
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(v2Content)))
        assertEquals(listOf(ApplyStrategy.MANUAL), harness.listener.downloaded.map { it.applyAt })
        assertTrue(harness.loader.loaded.isEmpty())
        val state = harness.core.getState()
        assertEquals(v2.release.release, state.nextRelease)
        assertEquals(SyncStatus.DOWNLOADED, state.lastCheck?.result?.status)
        assertEquals(ApplyUpdateResult(ApplyStatus.APPLIED, v2.release.release), harness.core.applyUpdate())
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(ApplyUpdateResult(ApplyStatus.NOTHING_TO_APPLY, v2.release.release), harness.core.applyUpdate())
    }

    @Test
    fun shouldDownloadOnCallWhateverTheConnectionUnderTheUnmeteredStrategy() = runBlocking {
        val harness = Harness(Fixture.configuration(downloadStrategy = DownloadStrategy.UNMETERED))
        harness.loader.isMetered = true
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.skipped(v2.release.release, SkippedReason.CONNECTION_METERED), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.NEXT_START), harness.core.downloadUpdate())
        assertEquals(listOf(ApplyStrategy.NEXT_START), harness.listener.downloaded.map { it.applyAt })
    }

    @Test
    fun shouldAnswerAppliedFromADownloadUnderTheImmediateStrategyAndReloadAtTheFirstRender() = runBlocking {
        val harness = Harness(immediateApply)
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.applied(v2.release.release, "notes 1"), harness.core.downloadUpdate())
        assertTrue(harness.listener.downloaded.isEmpty())
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.handleRendered()
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldApplyAMandatoryReleaseAtOnceWhateverTheApplyStrategy() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.NEXT_START))
        val v2 = Fixture.release(1, "b2", v2Content, isMandatory = true)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(SyncStatus.APPLIED, result.status)
        assertEquals(true, result.release?.isMandatory)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertTrue(harness.listener.downloaded.isEmpty())
    }

    @Test
    fun shouldHandAMandatoryReleaseToTheAppUnderTheManualMandatoryStrategy() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.NEXT_START, mandatoryApplyStrategy = MandatoryApplyStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content, isMandatory = true)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.MANUAL), harness.core.sync(SyncTrigger.MANUAL))
        assertEquals(listOf(true), harness.listener.downloaded.map { it.release.isMandatory })
        assertTrue(harness.loader.loaded.isEmpty())
        harness.loader.served = null
        harness.restart(Fixture.configuration(applyStrategy = ApplyStrategy.NEXT_START, mandatoryApplyStrategy = MandatoryApplyStrategy.MANUAL))
        harness.core.handleAppStart()
        val state = harness.core.getState()
        assertNull(state.currentRelease)
        assertEquals(v2.release.release, state.nextRelease)
    }

    @Test
    fun shouldTreatTheNewestReleaseAsMandatoryWhenAMandatoryOneWasMissed() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.NEXT_START, mandatoryApplyStrategy = MandatoryApplyStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content, isMandatory = true)
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        harness.publish(listOf(v2, v3), 1)
        harness.core.handleAppStart()
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals("r2", result.release?.id)
        assertEquals(true, result.release?.isMandatory)
        assertEquals(ApplyStrategy.MANUAL, result.applyAt)
    }

    @Test
    fun shouldTakeAnAttributeValueOf256CodePointsAndRefuseOneWithAC1ControlCharacter() = runBlocking {
        val harness = Harness()
        val emoji = "\uD83D\uDE00".repeat(256)
        harness.core.setAttributes(mapOf("mood" to emoji))
        assertEquals(emoji, harness.core.deviceResult().attributes["mood"])
        val error = runCatching { harness.core.setAttributes(mapOf("plan" to "beta\u0085")) }.exceptionOrNull()
        assertTrue(error is PlainException)
        assertEquals(mapOf("mood" to emoji), harness.core.deviceResult().attributes)
    }

    @Test
    fun shouldRefuseARollbackDetailWithAC1ControlCharacterAndRollNothingBack() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        val error = runCatching { harness.core.rollbackUpdate("checkout\u009f") }.exceptionOrNull()
        assertTrue(error is PlainException)
        assertEquals("b2", harness.core.getState().currentRelease?.bundleId)
    }

    @Test
    fun shouldCarryTheAppsRollbackReasonOnTheFailureEvent() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        harness.core.rollbackUpdate("checkout crashed")
        val failed = StateStore(harness.store).unsentEvents.last { it.type == "failed" }
        assertEquals(RollbackReason.APP_REQUESTED.name, failed.reason)
        assertEquals("checkout crashed", failed.detail)
        val error = runCatching { harness.core.rollbackUpdate("a\nb") }.exceptionOrNull()
        assertTrue(error is PlainException)
    }

    @Test
    fun shouldSyncAndCleanUpAtAStartThatRollsBackACrash() = runBlocking {
        val harness = Harness(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.loader.served = "b2"
        harness.restart(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        harness.core.handleAppStart()
        harness.restart(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        harness.core.handleAppStart()
        assertEquals(RollbackReason.APP_CRASHED, harness.listener.rollbacks.last().reason)
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
        assertTrue(harness.files.bundleIds().isEmpty())
    }

    @Test
    fun shouldAnnounceTheRollbackWhenTheReloadRunsAndNotBefore() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.setRestartAllowed(false)
        harness.scheduler.fire()
        assertTrue(harness.listener.rollbacks.isEmpty())
        assertEquals(listOf("b2"), harness.loader.loaded)
        harness.core.setRestartAllowed(true)
        assertEquals(listOf("b2", null), harness.loader.loaded)
        assertEquals(listOf(RollbackReason.READINESS_TIMED_OUT), harness.listener.rollbacks.map { it.reason })
    }

    @Test
    fun shouldAnnounceARollbackAtTheNextStartWhenTheProcessEndedWhileItsReloadWasHeld() = runBlocking {
        val harness = startOnUnconfirmedRelease()
        harness.core.setRestartAllowed(false)
        harness.scheduler.fire()
        assertTrue(harness.listener.rollbacks.isEmpty())
        startAgain(harness, immediateApply)
        assertEquals(listOf(RollbackReason.READINESS_TIMED_OUT), harness.listener.rollbacks.map { it.reason })
        val ready = harness.core.notifyReady()
        assertTrue(ready.isRolledBack)
        assertEquals(RollbackReason.READINESS_TIMED_OUT, ready.rollbackReason)
        assertEquals("r1", ready.previousRelease?.id)
    }

    @Test
    fun shouldAnnounceARollbackAgainAtTheNextStartWhenTheAppNeverCameUpAfterTheReload() = runBlocking {
        val harness = startOnUnconfirmedRelease()
        harness.core.rollbackUpdate(null)
        assertEquals(1, harness.listener.rollbacks.size)
        startAgain(harness, immediateApply)
        assertEquals(listOf(RollbackReason.APP_REQUESTED, RollbackReason.APP_REQUESTED), harness.listener.rollbacks.map { it.reason })
    }

    @Test
    fun shouldNotAnnounceARollbackAgainAtTheNextStartWhenTheAppRenderedAfterTheReload() = runBlocking {
        val harness = startOnUnconfirmedRelease()
        harness.core.rollbackUpdate(null)
        harness.core.handleRendered()
        startAgain(harness, immediateApply)
        assertEquals(1, harness.listener.rollbacks.size)
        assertNull(StateStore(harness.store).pendingRollbackEvent)
    }

    @Test
    fun shouldNotAnnounceARollbackAgainAtTheNextStartWhenTheAppNotifiedReadyAfterTheReload() = runBlocking {
        val harness = startOnUnconfirmedRelease()
        harness.core.rollbackUpdate(null)
        harness.core.notifyReady()
        startAgain(harness, immediateApply)
        assertEquals(1, harness.listener.rollbacks.size)
        assertNull(StateStore(harness.store).pendingRollbackEvent)
    }

    @Test
    fun shouldAnnounceOnceAtAStartThatRollsBackACrashItself() = runBlocking {
        val harness = Harness()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.loader.served = "b2"
        harness.restart()
        harness.core.handleAppStart()
        harness.restart()
        harness.core.handleAppStart()
        assertEquals(listOf(RollbackReason.APP_CRASHED), harness.listener.rollbacks.map { it.reason })
    }

    @Test
    fun shouldKeepTheNoticeWhenTheReadinessTimerRanOutAndNothingRendered() = runBlocking {
        val harness = startOnUnconfirmedRelease()
        harness.scheduler.fire()
        assertEquals(listOf(RollbackReason.READINESS_TIMED_OUT), harness.listener.rollbacks.map { it.reason })
        assertEquals(RollbackReason.READINESS_TIMED_OUT, StateStore(harness.store).pendingRollbackEvent?.reason)
        startAgain(harness, immediateApply)
        assertEquals(listOf(RollbackReason.READINESS_TIMED_OUT, RollbackReason.READINESS_TIMED_OUT), harness.listener.rollbacks.map { it.reason })
    }

    @Test
    fun shouldKeepTheNoticeWhenTheAppRendersWhileTheRollbacksReloadIsHeld() = runBlocking {
        val harness = startOnUnconfirmedRelease()
        harness.core.setRestartAllowed(false)
        harness.scheduler.fire()
        harness.core.handleRendered()
        assertEquals(RollbackReason.READINESS_TIMED_OUT, StateStore(harness.store).pendingRollbackEvent?.reason)
        harness.core.setRestartAllowed(true)
        assertEquals(listOf(RollbackReason.READINESS_TIMED_OUT), harness.listener.rollbacks.map { it.reason })
        assertEquals(RollbackReason.READINESS_TIMED_OUT, StateStore(harness.store).pendingRollbackEvent?.reason)
    }

    @Test
    fun shouldRemoveTheNoticeAtTheStartOfANewBinary() = runBlocking {
        val harness = startOnUnconfirmedRelease()
        harness.core.rollbackUpdate(null)
        harness.loader.served = null
        harness.restart(Fixture.configuration(builtAt = Fixture.BUILT_AT + 86_400_000))
        harness.core.handleAppStart()
        assertEquals(1, harness.listener.rollbacks.size)
        assertNull(StateStore(harness.store).pendingRollbackEvent)
        assertEquals(false, harness.core.notifyReady().isRolledBack)
    }

    @Test
    fun shouldRemoveTheNoticeWhenTheAppClearsUpdates() = runBlocking {
        val harness = startOnUnconfirmedRelease()
        harness.core.setRestartAllowed(false)
        harness.scheduler.fire()
        harness.core.clearUpdates()
        assertEquals(listOf("b2", null), harness.loader.loaded)
        assertNull(StateStore(harness.store).pendingRollbackEvent)
        assertNull(StateStore(harness.store).lastRollback)
        startAgain(harness, immediateApply)
        assertTrue(harness.listener.rollbacks.isEmpty())
    }

    @Test
    fun shouldFailOfflineNotUnknownWhenAChannelNameCannotBeResolved() = runBlocking {
        val harness = Harness()
        harness.core.setChannel(ChannelChoice.Name("staging"))
        harness.http.isOffline = true
        val result = harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(FailedReason.DEVICE_OFFLINE.name, result.reason)
        assertEquals(listOf(FailedReason.DEVICE_OFFLINE), harness.listener.failed.map { it.reason })
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
        assertEquals(SyncStatus.DOWNLOADED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertTrue(harness.http.requests.any { it.first == deltaUrl })
        assertEquals(PackKind.DELTA.wire, StateStore(harness.store).unsentEvents.first { it.type == "downloaded" }.packKind)
    }

    @Test
    fun shouldTakeTheStreamedDeltaWhenTheDeviceIsTwoReleasesBehind() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        val v3 = Fixture.release(2, "b3", "<html>v3</html>".toByteArray())
        val v4 = Fixture.release(3, "b4", "<html>v4</html>".toByteArray())
        val streamedUrl = "${Fixture.UPDATES_BASE_URL}/v1/apps/${Fixture.APP_ID}/bundles/b4/deltas/b2"
        harness.publish(listOf(v4, v3, v2), 2, etag = "\"e2\"")
        harness.http.stubJson(v4.release.manifestUrl, v4.envelope.copy(deltas = listOf(ManifestEnvelope.Delta("b3", "${v4.envelope.pack.url}-delta-b3", v4.pack.size.toLong()))).toJson())
        harness.http.stub(streamedUrl, body = v4.pack)
        assertEquals(SyncStatus.APPLIED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertTrue(harness.http.requests.any { it.first == streamedUrl })
        assertTrue(harness.http.requests.none { it.first == v4.envelope.pack.url })
        assertEquals(PackKind.STREAMED.wire, StateStore(harness.store).unsentEvents.last { it.type == "downloaded" }.packKind)
    }

    @Test
    fun shouldDiscardADownloadedReleaseRevokedBeforeTheStartThatWouldApplyIt() = runBlocking {
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
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncResult.downloaded(v2.release.release, "notes 1", ApplyStrategy.MANUAL), harness.core.sync(SyncTrigger.MANUAL))
        harness.publish(emptyList(), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(ApplyUpdateResult(ApplyStatus.NOTHING_TO_APPLY, null), harness.core.applyUpdate())
        assertTrue(harness.loader.loaded.isEmpty())
        assertNull(harness.core.getState().nextRelease)
    }

    @Test
    fun shouldBeginTheStartSyncAtOnceWhenTheRunningReleaseIsConfirmed() = runBlocking {
        val harness = startOnConfirmedRelease(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
    }

    @Test
    fun shouldBeginNoStartSyncWhenTheCheckStrategyIsManual() = runBlocking {
        val harness = startOnConfirmedRelease(Fixture.configuration())
        harness.core.handleRendered()
        harness.core.notifyReady()
        assertEquals(SyncTrigger.MANUAL, StateStore(harness.store).lastCheck?.trigger)
    }

    @Test
    fun shouldBeginTheStartSyncAtTheConfirmationWhenTheReleaseIsNew() = runBlocking {
        val harness = Harness()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.loader.served = "b2"
        harness.restart(Fixture.configuration(checkStrategy = CheckStrategy.AUTO, readySignal = ReadySignal.MANUAL))
        harness.core.handleAppStart()
        harness.core.handleRendered()
        assertEquals(SyncTrigger.MANUAL, StateStore(harness.store).lastCheck?.trigger)
        harness.core.notifyReady()
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
    }

    @Test
    fun shouldReloadAnImmediateApplyAtTheFirstRenderAndNotBefore() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        assertEquals(SyncStatus.APPLIED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertTrue(harness.loader.loaded.isEmpty())
        assertEquals(v2.release.release, harness.core.getState().nextRelease)
        harness.core.handleRendered()
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        assertNull(harness.core.getState().fallbackRelease)
    }

    @Test
    fun shouldReloadAMandatoryReleaseAtTheFirstRenderAndNotBefore() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.NEXT_START))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content, isMandatory = true)), 1)
        harness.core.handleAppStart()
        assertEquals(SyncStatus.APPLIED, harness.core.sync(SyncTrigger.MANUAL).status)
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.handleRendered()
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldRunARestartHeldByTheAppAndTheStartOnceWhenTheAppAllowsRestartsLast() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.setRestartAllowed(false)
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleRendered()
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.setRestartAllowed(true)
        assertEquals(listOf("b2"), harness.loader.loaded)
        harness.core.handleRendered()
        harness.core.setRestartAllowed(true)
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldRunARestartHeldByTheAppAndTheStartOnceWhenTheStartSettlesLast() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.setRestartAllowed(false)
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.setRestartAllowed(true)
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.handleRendered()
        assertEquals(listOf("b2"), harness.loader.loaded)
        harness.core.handleRendered()
        harness.core.setRestartAllowed(true)
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldRollBackAndReloadAtTheReadyTimeoutWhenNothingRendered() = runBlocking {
        val harness = Harness()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.loader.served = "b2"
        harness.restart()
        harness.core.handleAppStart()
        assertTrue(harness.loader.loaded.isEmpty())
        harness.scheduler.fire()
        assertEquals(listOf<String?>(null), harness.loader.loaded)
        assertEquals(listOf(RollbackReason.READINESS_TIMED_OUT), harness.listener.rollbacks.map { it.reason })
    }

    @Test
    fun shouldRollBackAtOnceWhenNothingRendered() = runBlocking {
        val harness = startOnConfirmedRelease(Fixture.configuration())
        harness.core.rollbackUpdate("fatal")
        assertEquals(listOf<String?>(null), harness.loader.loaded)
        assertEquals(listOf(RollbackReason.APP_REQUESTED), harness.listener.rollbacks.map { it.reason })
    }

    @Test
    fun shouldHoldTheNextRestartUntilTheReloadedAppRenders() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(listOf("b2"), harness.loader.loaded)
        harness.publish(listOf(v2, Fixture.release(2, "b3", "<html>v3</html>".toByteArray())), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(listOf("b2"), harness.loader.loaded)
        harness.core.handleRendered()
        assertEquals(listOf("b2", "b3"), harness.loader.loaded)
        assertEquals(v2.release.release, harness.core.getState().fallbackRelease)
    }

    @Test
    fun shouldReloadOnceWhenTheAppAppliesAnUpdateWhileTheAppHoldsARestart() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.setRestartAllowed(false)
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.applyUpdate()
        assertEquals(listOf("b2"), harness.loader.loaded)
        harness.core.handleRendered()
        harness.core.setRestartAllowed(true)
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldReloadOnceWhenTheAppRollsBackWhileTheStartHoldsARestart() = runBlocking {
        val harness = startOnConfirmedRelease(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content), Fixture.release(2, "b3", "<html>v3</html>".toByteArray())), 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.rollbackUpdate("fatal")
        assertEquals(listOf<String?>(null), harness.loader.loaded)
        harness.core.handleRendered()
        assertEquals(listOf<String?>(null), harness.loader.loaded)
        assertNull(harness.core.getState().currentRelease)
    }

    @Test
    fun shouldReloadAnImmediateApplyAtNotifyReadyAndNotBefore() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE, readySignal = ReadySignal.MANUAL))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.notifyReady()
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldReloadAMandatoryReleaseAtNotifyReadyAndNotBefore() = runBlocking {
        val harness = Harness(Fixture.configuration(readySignal = ReadySignal.MANUAL))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content, isMandatory = true)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.notifyReady()
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldSwitchToAHeldImmediateApplyAtTheNextStartWhenItNeverRan() = runBlocking {
        val configuration = Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE)
        val harness = Harness(configuration)
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(harness.loader.loaded.isEmpty())
        startAgain(harness, configuration)
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
    }

    @Test
    fun shouldSwitchToAHeldMandatoryReleaseAtTheNextStartWhenItNeverRan() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content, isMandatory = true)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertTrue(harness.loader.loaded.isEmpty())
        startAgain(harness, Fixture.configuration())
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
    }

    @Test
    fun shouldRunTheEmbeddedBundleAtTheNextStartWhenAHeldMoveFromARevokedReleaseNeverRan() = runBlocking {
        val harness = startOnConfirmedRelease(Fixture.configuration())
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 2, revoked = listOf("r1"), etag = "\"e2\"")
        assertEquals(SyncResult.skipped(null, SkippedReason.RELEASE_REVOKED), harness.core.sync(SyncTrigger.MANUAL))
        assertTrue(harness.loader.loaded.isEmpty())
        startAgain(harness, Fixture.configuration())
        assertNull(harness.core.getState().currentRelease)
        assertNull(harness.loader.servedBundleId())
    }

    @Test
    fun shouldRunTheOlderReleaseAtTheNextStartWhenAHeldMoveFromARevokedReleaseNeverRan() = runBlocking {
        val configuration = Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE, mandatoryApplyStrategy = MandatoryApplyStrategy.MANUAL)
        val harness = startOnConfirmedRelease(configuration)
        val releases = listOf(Fixture.release(1, "b2", v2Content), Fixture.release(2, "b3", "<html>v3</html>".toByteArray()))
        harness.core.handleRendered()
        harness.publish(releases, 2, etag = "\"e2\"")
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        startAgain(harness, configuration)
        harness.publish(releases, 3, revoked = listOf("r2"), etag = "\"e3\"")
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals("r2", harness.core.getState().currentRelease?.id)
        startAgain(harness, configuration)
        assertEquals("r1", harness.core.getState().currentRelease?.id)
    }

    /** A batch the endpoint refuses loses its events and leaves the report unacknowledged, so the next sync sends the report alone. */
    private suspend fun assertBatchRefused(status: Int) {
        val harness = Harness()
        harness.http.stub(Fixture.eventsUrl(), status = status, body = ByteArray(0))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        val state = StateStore(harness.store)
        assertTrue(state.unsentEvents.isEmpty())
        assertNull(state.reportedAt)
        assertNull(state.acknowledgedReport)
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(2, harness.http.posts.size)
        val next = JSONObject(String(harness.http.posts[1].third))
        assertEquals(0, next.getJSONArray("events").length())
        assertTrue(!next.isNull("report"))
    }

    /** A run that applies v2 at once after the first render and has not confirmed it: its readiness timer is the one scheduled. */
    private suspend fun startOnUnconfirmedRelease(): Harness {
        val harness = Harness(immediateApply)
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(1, harness.scheduler.tasks.size)
        return harness
    }

    /** The third run of an app whose second run confirmed v2: its start finds nothing to switch and nothing to roll back. */
    private suspend fun startOnConfirmedRelease(configuration: Configuration): Harness {
        val harness = Harness()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.loader.served = "b2"
        harness.restart()
        harness.core.handleAppStart()
        harness.core.notifyReady()
        harness.restart(configuration)
        harness.core.handleAppStart()
        return harness
    }

    /** The next start of the process, after a run that may never have come up: the host serves the bundle the loader persisted. */
    private suspend fun startAgain(harness: Harness, configuration: Configuration) {
        harness.loader.served = harness.loader.persisted
        harness.restart(configuration)
        harness.core.handleAppStart()
    }
}
