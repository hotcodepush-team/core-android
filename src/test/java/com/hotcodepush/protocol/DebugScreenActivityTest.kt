package com.hotcodepush.protocol

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
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(report.text.toString(), report.text.contains("Result: FAILED OFFLINE"))

        (buttons.getChildAt(1) as Button).performClick()
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val shared = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(report.text.toString(), shared?.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(shared?.getStringExtra(Intent.EXTRA_TEXT)?.contains("Result: FAILED OFFLINE") == true)
    }

    @Test
    fun shouldCloseWhenNoCoreIsBehindTheScreen() {
        DebugScreen.core = null
        val activity = Robolectric.buildActivity(DebugScreenActivity::class.java).setup().get()
        assertTrue(activity.isFinishing)
        assertNull(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
    }
}
