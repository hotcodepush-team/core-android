package com.hotcodepush.core

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLifecycleTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    private fun startedHarness(isProcessInForeground: Boolean = true): Harness {
        val harness = Harness(Fixture.configuration(checkStrategy = CheckStrategy.AUTO))
        harness.processState.isInTheForeground = isProcessInForeground
        harness.publish(emptyList(), 1)
        runBlocking { harness.core.handleAppStart() }
        return harness
    }

    private fun pendingIntervals(harness: Harness): List<Double> = harness.scheduler.tasks.filter { !it.isCancelled }.map { it.seconds }

    @Test
    fun shouldSkipTheFirstResumeAndHandleEveryPauseAndResumeAfterIt() {
        val harness = startedHarness()
        harness.core.observeAppLifecycle(application)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(listOf(900.0), harness.scheduler.tasks.map { it.seconds })

        activity.pause()
        assertTrue(harness.scheduler.tasks.single().isCancelled)
        harness.clock.now += 600_000
        activity.resume()

        assertEquals(listOf(300.0), pendingIntervals(harness))
    }

    @Test
    fun shouldHandleTheFirstResumeWhenTheProcessStartedInTheBackground() {
        val harness = startedHarness(isProcessInForeground = false)
        harness.core.observeAppLifecycle(application)
        val scheduledAtStart = harness.scheduler.tasks.size

        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals(scheduledAtStart + 1, harness.scheduler.tasks.size)
    }

    @Test
    fun shouldObserveOnceWhenCalledTwice() {
        val harness = startedHarness()
        harness.core.observeAppLifecycle(application)
        harness.core.observeAppLifecycle(application)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()

        activity.pause()
        harness.clock.now += 600_000
        activity.resume()

        assertEquals(listOf(900.0, 300.0), harness.scheduler.tasks.map { it.seconds })
    }

    @Test
    fun shouldTolerateABridgeThatStillHandlesThePauseAndTheResumeItself() = runBlocking {
        val harness = startedHarness()
        harness.core.observeAppLifecycle(application)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()

        activity.pause()
        harness.core.handleAppPause()
        harness.clock.now += 600_000
        activity.resume()
        harness.core.handleAppResume()

        assertEquals(listOf(300.0), pendingIntervals(harness))
        assertEquals(SyncTrigger.START, StateStore(harness.store).lastCheck?.trigger)
    }
}
