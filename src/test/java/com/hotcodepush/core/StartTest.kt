package com.hotcodepush.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start as a host sees it: the bundle it serves, a start that fails open, and a reload the core did not perform. */
class StartTest {
    private val v2Content = "<html>v2</html>".toByteArray()

    /** A core whose store holds `v2` downloaded and waiting for the next start, and that start's core. */
    private fun harnessWithAWaitingRelease(): Pair<Harness, Fixture.Published> {
        val harness = Harness()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        runBlocking {
            harness.core.handleAppStart()
            harness.core.sync(SyncTrigger.MANUAL)
        }
        harness.restart()
        harness.http.requests.clear()
        return harness to v2
    }

    @Test
    fun shouldResolveTheWaitingReleaseAsTheStartsBundleWithoutTheNetworkAndOnceAProcess() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        assertEquals("b2", harness.core.resolveStartBundleId())
        assertEquals("b2", harness.core.resolveStartBundleId())
        assertTrue(harness.http.requests.isEmpty())
        harness.core.handleAppStart()
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        assertTrue(StateStore(harness.store).failedBundleIds.isEmpty())
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(1, harness.scheduler.tasks.size)
    }

    @Test
    fun shouldApplyNoWaitingReleaseAndArmNoGateAtAHeadlessStartAndSwitchAtTheNextStartThatRenders() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.loader.served = "b2"
        assertNull(harness.core.resolveStartBundleId(isHeadless = true))
        harness.core.handleAppStart()
        assertNull(harness.core.getState().currentRelease)
        assertEquals(v2.release.release, harness.core.getState().nextRelease)
        assertEquals(listOf<String?>(null), harness.loader.loaded)
        assertTrue(harness.scheduler.tasks.isEmpty())
        harness.restart()
        assertEquals("b2", harness.core.resolveStartBundleId())
        harness.core.handleAppStart()
        assertEquals(1, harness.scheduler.tasks.size)
    }

    @Test
    fun shouldArmNoGateWhenHandleAppStartSaysTheStartIsHeadless() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.core.handleAppStart(isHeadless = true)
        assertEquals(v2.release.release, harness.core.getState().nextRelease)
        assertTrue(harness.scheduler.tasks.isEmpty())
        assertTrue(harness.loader.loaded.isEmpty())
    }

    @Test
    fun shouldResolveTheEmbeddedBundleAsTheStartsBundleWhenThePreviousRunNeverConfirmedTheRelease() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        harness.restart()
        assertNull(harness.core.resolveStartBundleId())
        assertEquals(listOf("b2"), StateStore(harness.store).failedBundleIds)
        harness.core.handleAppStart()
        assertEquals(RollbackReason.APP_CRASHED, harness.listener.rolledBack.single().reason)
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleWhenAStoredReleaseIsMissingAField() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.store.putString("hotcodepush.nextRelease", JSONObject().put("id", "r1").put("bundleId", "b2").toString())
        assertNull(harness.core.resolveStartBundleId())
        harness.core.handleAppStart()
        assertNull(harness.core.getState().nextRelease)
        assertTrue(harness.uncaught.isEmpty())
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleWhenTheStoreIsCorrupt() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        for (key in listOf("currentRelease", "nextRelease", "fallbackRelease", "failedBundleIds", "pendingRollbackEvent", "lastBuiltAt")) harness.store.putString("hotcodepush.$key", "{not json")
        assertNull(harness.core.resolveStartBundleId())
        harness.core.handleAppStart()
        assertNull(harness.core.getState().nextRelease)
    }

    @Test
    fun shouldStartOnTheEmbeddedBundleAndLogWhenTheStartRuleThrows() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.loader.persistFailure = IllegalStateException("the loader broke")
        assertNull(harness.core.resolveStartBundleId())
        harness.loader.persistFailure = null
        harness.core.handleAppStart()
        assertNull(harness.core.getState().currentRelease)
        assertNull(harness.core.getState().nextRelease)
        assertTrue(harness.core.debugSnapshot().log.any { it.message.contains("the loader broke") })
    }

    @Test
    fun shouldNeverThrowAtTheHostWhenLoadingTheResolvedBundleThrows() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.loader.loadFailure = IllegalStateException("the loader broke")
        harness.core.handleAppStart()
        assertEquals("b2", harness.core.getState().currentRelease?.bundleId)
        assertTrue(harness.core.debugSnapshot().log.any { it.message.contains("the loader broke") })
    }

    @Test
    fun shouldRunTheGateAgainWhenTheHostReportsAReloadTheCoreDidNotPerformBeforeTheReleaseIsConfirmed() = runBlocking {
        val (harness, v2) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        val firstTimer = harness.scheduler.tasks.single()
        harness.core.handleAppStart()
        assertTrue(firstTimer.isCancelled)
        assertEquals(2, harness.scheduler.tasks.size)
        assertEquals(listOf("b2"), harness.loader.loaded)
        assertEquals(v2.release.release, harness.core.getState().currentRelease)
        harness.core.handleRendered()
        assertEquals(v2.release.release, harness.core.getState().fallbackRelease)
    }

    @Test
    fun shouldHoldTheAppsRestartUntilTheReloadTheCoreDidNotPerformRenders() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.MANUAL))
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleAppStart()
        harness.core.applyUpdate()
        assertTrue(harness.loader.loaded.isEmpty())
        harness.core.handleRendered()
        assertEquals(listOf("b2"), harness.loader.loaded)
    }

    @Test
    fun shouldAnnounceARollbackNoticeAgainToAReloadTheCoreDidNotPerformBeforeTheAppCameUp() = runBlocking {
        val (harness, _) = harnessWithAWaitingRelease()
        harness.core.handleAppStart()
        harness.restart()
        harness.core.handleAppStart()
        harness.core.handleAppStart()
        assertEquals(2, harness.listener.rolledBack.size)
        harness.core.handleRendered()
        harness.core.handleAppStart()
        assertEquals(2, harness.listener.rolledBack.size)
    }
}
