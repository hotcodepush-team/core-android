package com.hotcodepush.core

import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The screen itself, on the JVM through Robolectric: what it shows, what the check adds and what the share sheet receives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugScreenActivityTest {
    @Test
    fun shouldShowTheStateCheckNowAndShareTheTextWithTheLastChecksCode() {
        val harness = Harness()
        harness.http.isOffline = true
        runBlocking { harness.core.handleAppStart() }
        val application = RuntimeEnvironment.getApplication()
        DebugScreen.show(application, harness.core)
        val started = shadowOf(application).nextStartedActivity
        assertEquals(DebugScreenActivity::class.java.name, started.component?.className)

        val activity = Robolectric.buildActivity(DebugScreenActivity::class.java, started).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val buttons = content.getChildAt(0) as ViewGroup
        val report = (content.getChildAt(1) as ViewGroup).getChildAt(0) as TextView
        assertTrue(report.text.toString(), report.text.contains("Last check\n  When: never"))

        (buttons.getChildAt(0) as Button).performClick()
        idleMainLooperUntil { report.text.contains("Result: FAILED DEVICE_OFFLINE") }
        assertTrue(harness.http.requestThreads.isNotEmpty())
        assertTrue(harness.http.requestThreads.none { it == Looper.getMainLooper().thread })

        (buttons.getChildAt(1) as Button).performClick()
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val shared = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(report.text.toString(), shared?.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(shared?.getStringExtra(Intent.EXTRA_TEXT)?.contains("Result: FAILED DEVICE_OFFLINE") == true)
    }

    /** The check runs off the main thread and renders back on it: the main looper runs until the screen shows the answer, ten seconds at most. */
    private fun idleMainLooperUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("the condition held within ten seconds", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
    }

    @Test
    fun shouldCloseWhenNoCoreIsBehindTheScreen() {
        DebugScreen.core = null
        val activity = Robolectric.buildActivity(DebugScreenActivity::class.java).setup().get()
        assertTrue(activity.isFinishing)
        assertNull(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
    }
}
