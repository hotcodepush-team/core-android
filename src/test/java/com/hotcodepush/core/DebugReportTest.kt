package com.hotcodepush.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportTest {
    private val v2Content = "<html>v2</html>".toByteArray()

    @Test
    fun shouldCarryTheLastChecksCodeInTheShareText() = runBlocking {
        val harness = Harness()
        harness.publish(listOf(Fixture.release(1, "b2", v2Content, conditions = listOf(Condition.Binary(">=9.0.0")))), 1_759_900_000_000)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        val snapshot = harness.core.debugSnapshot()
        val text = DebugReport.text(snapshot)
        assertTrue(text, text.contains("Device id: ${harness.core.deviceResult().id}"))
        assertTrue(text, text.contains("Result: SKIPPED DEVICE_INCOMPATIBLE binary"))
        assertTrue(text, text.contains("Sequence: 1759900000000"))
        assertTrue(text, text.contains("Running: the embedded bundle"))
        assertTrue(text, text.contains("SKIPPED DEVICE_INCOMPATIBLE binary — manual: release #1 (1.1.0) is not taken"))
        assertEquals(listOf("Device", "Channel", "Releases", "Last check", "Index", "Configuration", "Log"), DebugReport.sections(snapshot).map { it.title })
    }

    @Test
    fun shouldPrintEverySectionAsLabelsAndValues() {
        val running = Release("r2", 2, "b2", "1.2.0", true)
        val device = DeviceResult("d1", "android", "2.4.1", "57", "14", "0.0.0", null, ChannelResult("c2", "beta", ChannelSource.RUNTIME), mapOf("plan" to "pro", "cohort" to "a"))
        val state = StateResult(running, null, running, null, null, null, null, listOf("b0", "b1"), null)
        val log = listOf(LogEntry(Fixture.BUILT_AT, "APPLIED", "release r2 is the running release"))
        val expected = """
            HotCodePush debug report, 2023-11-14T22:13:21.000Z

            Device
              Device id: d1
              Platform: android
              Binary: 2.4.1 (57)
              OS: 14
              SDK: 0.0.0
              Fingerprint: none
              Debug build: yes
              Attributes: cohort=a, plan=pro

            Channel
              Channel id: c2
              Name: beta
              Source: runtime

            Releases
              Running: #2 (1.2.0), bundle b2, mandatory
              Downloaded: none
              Fallback: #2 (1.2.0), bundle b2, mandatory
              Embedded bundle: not registered
              Failed bundles: b0, b1

            Last check
              When: never

            Index
              Sequence: none
              Fetched at: never
              Last report at: never

            Configuration
              App id: a0000000-0000-4000-8000-000000000001
              Configured channel: c0000000-0000-4000-8000-000000000001
              Built at: 2023-11-14T22:13:20.000Z
              Files host: https://files.test
              Updates host: https://updates.test
              Auto check: off
              Strategies: download auto, install next-start, mandatory immediate
              Ready signal: render, 10 s
              Debug builds: enabled
              Public keys: 0

            Log
              2023-11-14T22:13:20.000Z: APPLIED — release r2 is the running release
        """.trimIndent()
        assertEquals(expected, DebugReport.text(DebugSnapshot(Fixture.BUILT_AT + 1_000, device, Fixture.configuration(), true, state, log)))
    }

    @Test
    fun shouldLogTheDownloadTheInstallAndTheReportOfASync() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        harness.acknowledgeEvents()
        val v2 = Fixture.release(1, "b2", v2Content)
        harness.publish(listOf(v2), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.notifyReady()
        val log = harness.core.debugSnapshot().log
        val lifecycle = log.filter { !it.code.startsWith("REPORT") }
        assertEquals(listOf("DOWNLOADED", "APPLIED", "UPDATED", "CONFIRMED"), lifecycle.map { it.code })
        assertEquals("release r1: ${v2.pack.size} bytes as full pack", lifecycle[0].message)
        assertEquals("manual: release #1 (1.1.0) installs immediate", lifecycle[2].message)
        assertTrue(log.map { it.code }.joinToString(), log.any { it.code == "REPORTED" })
    }

    @Test
    fun shouldLogARateLimitedReportAndKeepTheOutbox() = runBlocking {
        val harness = Harness()
        harness.http.stubJson(Fixture.eventsUrl(), JSONObject().put("error", "E_RATE_LIMITED"), status = 429)
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        val log = harness.core.debugSnapshot().log
        assertEquals("REPORT_FAILED", log.last().code)
        assertEquals("2 events kept for the next sync: HTTP 429", log.last().message)
        assertEquals(2, StateStore(harness.store).unsentEvents.size)
    }

    @Test
    fun shouldLogARefusedBatchWithTheCountAndTheStatus() = runBlocking {
        val harness = Harness()
        harness.http.stubJson(Fixture.eventsUrl(), JSONObject().put("error", "E_VALIDATION"), status = 422)
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        val log = harness.core.debugSnapshot().log
        assertEquals("REPORT_REFUSED", log.last().code)
        assertEquals("2 events dropped: HTTP 422", log.last().message)
    }

    @Test
    fun shouldLogAnUnreadableAcknowledgementAsAFailedReport() = runBlocking {
        val harness = Harness()
        harness.http.stub(Fixture.eventsUrl(), status = 202, body = "accepted".toByteArray())
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.sync(SyncTrigger.MANUAL)
        val log = harness.core.debugSnapshot().log
        assertEquals("REPORT_FAILED", log.last().code)
        assertEquals("2 events kept for the next sync: HTTP 202", log.last().message)
        assertEquals(2, StateStore(harness.store).unsentEvents.size)
        assertNull(StateStore(harness.store).reportedAt)
    }

    @Test
    fun shouldLogARollbackWithItsReason() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        harness.publish(listOf(Fixture.release(1, "b2", v2Content)), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleReadyTimeout()
        val log = harness.core.debugSnapshot().log.filter { !it.code.startsWith("REPORT") }
        assertEquals(listOf("FAILED READINESS_TIMED_OUT", "ROLLED_BACK"), log.takeLast(2).map { it.code })
        assertEquals("release r1 rolled back to the embedded bundle", log.last().message)
    }

    @Test
    fun shouldKeepTheNewestTwoHundredEntries() = runBlocking {
        val harness = Harness()
        harness.acknowledgeEvents()
        harness.publish(emptyList(), 1)
        harness.core.handleAppStart()
        repeat(LogEntry.CAPACITY + 5) { harness.core.checkForUpdate() }
        val log = harness.core.debugSnapshot().log
        assertEquals(LogEntry.CAPACITY, log.size)
        assertEquals("UP_TO_DATE", log.last().code)
    }
}
