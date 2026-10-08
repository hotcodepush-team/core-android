package com.hotcodepush.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** The start as a host sees it: the bundle it serves, a start that fails open or never answers in time, and a reload the core did not perform. */
class StartTest {
    private val v2Content = "<html>v2</html>".toByteArray()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A core whose store holds `v2` downloaded and waiting for the next start, and that start's core. */
    private fun harnessWithAWaitingRelease(configuration: Configuration = Fixture.configuration()): Pair<Harness, Fixture.Published> {
        val harness = Harness(configuration)
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        runBlocking {
            harness.core.handleAppStart()
            harness.core.sync(SyncTrigger.MANUAL)
        }
        harness.restart(configuration)
        harness.http.requests.clear()
        return harness to v2
    }

    @Test
    fun shouldAnswerTheWaitingReleaseAsTheBundleToServeWithoutTheNetwork() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        assertEquals("b2", harness.core.handleAppStart())
        assertTrue(harness.http.requests.isEmpty())
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(1, harness.scheduler.tasks.size)
    }

    /** The next start on threads of its own, held at its first write until the latch it answers opens, as a slow store holds it. */
    private fun holdTheNextStart(harness: Harness): CountDownLatch {
        val latch = CountDownLatch(1)
        harness.loader.persistLatch = latch
        harness.restart(scope = backgroundScope)
        return latch
    }

    @Test
    fun shouldAnswerTheStartsBundleToAHostWaitingInSynchronousCode() {
        val (harness, _) = harnessWithAWaitingRelease()
        assertEquals("b2", harness.core.handleAppStartBlocking())
    }

    @Test
    fun shouldAnswerTheEmbeddedBundleWhenTheStartTakesLongerThanTheTimeoutAndReloadTheHostOnceItRan() {
        val (harness, v2) = harnessWithAWaitingRelease()
        val latch = holdTheNextStart(harness)
        assertNull(harness.core.handleAppStartBlocking(isHeadless = false, timeout = 0.05))
        latch.countDown()
        runBlocking { assertEquals(NotifyReadyResult(v2.release.release, null, false, null), harness.core.notifyReady()) }
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldConfirmTheReleaseAtTheRenderOfTheReloadedBundleNotOfTheEmbeddedOneWhenTheStartTookLongerThanTheTimeout() {
        val (harness, v2) = harnessWithAWaitingRelease()
        val latch = holdTheNextStart(harness)
        assertNull(harness.core.handleAppStartBlocking(isHeadless = false, timeout = 0.05))
        val embeddedRender = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { harness.core.handleRendered() }
        latch.countDown()
        runBlocking {
            embeddedRender.join()
            assertEquals(listOf("b2"), harness.loader.loaded)
            assertNull(harness.core.getState().fallbackRelease)
            harness.core.handleRendered()
            assertEquals(v2.release.release, harness.core.getState().fallbackRelease)
        }
    }

    @Test
    fun shouldHoldARestartUntilTheReloadedBundleRendersWhenAStartThatTookLongerThanTheTimeoutReloadsAfterARender() {
        val (harness, _) = harnessWithAWaitingRelease()
        val latch = holdTheNextStart(harness)
        runBlocking { harness.core.handleRendered() }
        assertNull(harness.core.handleAppStartBlocking(isHeadless = false, timeout = 0.05))
        latch.countDown()
        runBlocking {
            harness.core.clearUpdates()
            assertEquals(listOf("b2"), harness.loader.loaded)
            harness.core.handleRendered()
            assertEquals(listOf("b2", null), harness.loader.loaded)
        }
    }

    @Test
    fun shouldConfirmNothingAtANotifyReadyOfTheEmbeddedBundleWhenTheStartTookLongerThanTheTimeout() {
        val (harness, v2) = harnessWithAWaitingRelease()
        val latch = holdTheNextStart(harness)
        assertNull(harness.core.handleAppStartBlocking(isHeadless = false, timeout = 0.05))
        val embeddedReady = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { harness.core.notifyReady() }
        latch.countDown()
        runBlocking {
            assertEquals(NotifyReadyResult(v2.release.release, null, false, null), embeddedReady.await())
            assertNull(harness.core.getState().fallbackRelease)
        }
    }

    @Test
    fun shouldApplyNoWaitingReleaseAndArmNoGateAtAHeadlessStartAndSwitchAtTheNextStartThatRenders() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.loader.served = "b2"
        assertNull(harness.core.handleAppStart(isHeadless = true))
        assertNull(harness.core.getState().currentRelease)
        assertEquals(v2.release.release, harness.core.getState().nextRelease)
        assertTrue(harness.loader.loaded.isEmpty())
        assertTrue(harness.scheduler.tasks.isEmpty())
        harness.restart()
        assertEquals("b2", harness.core.handleAppStart())
        assertEquals(1, harness.scheduler.tasks.size)
    }

    @Test
    fun shouldKeepAHeldInstallPersistedAcrossAHeadlessStartAndApplyItAtTheNextStart() = runBlocking {
        val configuration = Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE)
        val harness = Harness(configuration)
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals("b2", harness.loader.persisted)
        assertTrue(harness.loader.loaded.isEmpty())
        harness.loader.served = harness.loader.persisted
        harness.restart(configuration)
        assertNull(harness.core.handleAppStart(isHeadless = true))
        assertEquals("b2", harness.loader.persisted)
        assertTrue(harness.loader.loaded.isEmpty())
        harness.loader.served = harness.loader.persisted
        harness.restart(configuration)
        assertEquals("b2", harness.core.handleAppStart())
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
    }

    @Test
    fun shouldRollBackACrashAndRunTheAutomaticCheckAtAHeadlessStart() = runBlocking {
        val configuration = Fixture.configuration(autoCheck = true)
        val (harness, _) = harnessWithAWaitingRelease(configuration)
        harness.core.handleAppStart()
        harness.restart(configuration)
        harness.http.requests.clear()
        assertNull(harness.core.handleAppStart(isHeadless = true))
        assertEquals(listOf("b2"), StateStore(harness.store).failedBundleIds)
        assertTrue(harness.http.requests.any { it.first == Fixture.indexUrl() })
    }

    @Test
    fun shouldAnswerTheEmbeddedBundleWhenThePreviousRunNeverConfirmedTheRelease() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        harness.restart()
        assertNull(harness.core.handleAppStart())
        assertEquals(listOf("b2"), StateStore(harness.store).failedBundleIds)
        assertEquals(RollbackReason.APP_CRASHED, harness.listener.rolledBack.single().reason)
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleWhenAStoredReleaseIsMissingAField() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.store.putString("hotcodepush.nextRelease", JSONObject().put("id", "r1").put("bundleId", "b2").toString())
        assertNull(harness.core.handleAppStart())
        assertNull(harness.core.getState().nextRelease)
        assertTrue(harness.uncaught.isEmpty())
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleWhenTheStoreIsCorrupt() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        for (key in listOf("currentRelease", "nextRelease", "fallbackRelease", "failedBundleIds", "pendingRollbackEvent", "lastBuiltAt")) harness.store.putString("hotcodepush.$key", "{not json")
        assertNull(harness.core.handleAppStart())
        assertNull(harness.core.getState().nextRelease)
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleAndLogWhenTheStartRuleThrows() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.loader.persistFailure = IllegalStateException("the loader broke")
        assertNull(harness.core.handleAppStart())
        assertNull(harness.core.getState().currentRelease)
        assertNull(harness.core.getState().nextRelease)
        assertTrue(harness.core.debugSnapshot().log.any { it.message.contains("the loader broke") })
    }

    @Test
    fun shouldNeverThrowAtTheHostWhenLoadingTheBundleThrows() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.loader.loadFailure = IllegalStateException("the loader broke")
        assertEquals("b2", harness.core.handleAppStart())
        assertTrue(harness.core.debugSnapshot().log.any { it.message.contains("the loader broke") })
    }

    @Test
    fun shouldGateAReleaseNotYetConfirmedAgainAtAReloadAndNeverTakeItForACrash() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        val firstTimer = harness.scheduler.tasks.single()
        assertEquals("b2", harness.core.handleAppReload())
        assertTrue(firstTimer.isCancelled)
        assertEquals(2, harness.scheduler.tasks.size)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertTrue(StateStore(harness.store).failedBundleIds.isEmpty())
        harness.core.handleRendered()
        assertEquals(v2.release.release, harness.core.getState().fallbackRelease)
    }

    @Test
    fun shouldApplyAReleaseWaitingForTheNextStartAtAReload() = runBlocking {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        assertEquals("b2", harness.core.handleAppReload())
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(1, harness.scheduler.tasks.size)
    }

    @Test
    fun shouldHoldTheAppsRestartUntilTheReloadedAppRenders() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        assertNull(harness.core.handleAppReload())
        harness.core.applyUpdate()
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.handleRendered()
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldAnnounceARollbackNoticeAgainToAReloadBeforeTheAppCameUp() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        harness.restart()
        harness.core.handleAppStart()
        harness.core.handleAppReload()
        assertEquals(2, harness.listener.rolledBack.size)
        harness.core.handleRendered()
        harness.core.handleAppReload()
        assertEquals(2, harness.listener.rolledBack.size)
    }

    @Test
    fun shouldStartTheGatesFullWindowAgainAtTheResumeAndRollBackOnlyWhenItRanOutInTheForeground() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        harness.clock.now += 3_000
        harness.core.handleAppPause()
        assertTrue(harness.scheduler.tasks.single().isCancelled)
        harness.clock.now += 600_000
        harness.core.handleAppResume()
        assertEquals(10.0, harness.scheduler.tasks.last().seconds, 0.0)
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        harness.scheduler.fire()
        assertEquals(RollbackReason.READINESS_TIMED_OUT, harness.listener.rolledBack.single().reason)
    }

    @Test
    fun shouldStartTheGatePausedWhenTheProcessStartsInTheBackgroundAndStartItsFullWindowAtTheFirstResume() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.isProcessInForeground = false
        harness.core.handleAppStart()
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        assertTrue(harness.scheduler.tasks.isEmpty())
        harness.clock.now += 600_000
        harness.core.handleAppResume()
        assertEquals(10.0, harness.scheduler.tasks.single().seconds, 0.0)
        harness.scheduler.fire()
        assertEquals(RollbackReason.READINESS_TIMED_OUT, harness.listener.rolledBack.single().reason)
    }

    @Test
    fun shouldIgnoreATimeoutThatFiresAsTheTimerPauses() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        val timer = harness.scheduler.tasks.single()
        harness.core.handleAppPause()
        timer.block()
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        assertTrue(harness.listener.rolledBack.isEmpty())
    }

    @Test
    fun shouldConfirmAReleaseThatRendersAfterALongBackground() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        harness.core.handleAppPause()
        harness.clock.now += 3_600_000
        harness.scheduler.fire()
        harness.core.handleAppResume()
        harness.core.handleRendered()
        assertEquals(v2.release.release, harness.core.getState().fallbackRelease)
        assertTrue(harness.listener.rolledBack.isEmpty())
    }
}
