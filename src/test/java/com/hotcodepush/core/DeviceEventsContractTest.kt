package com.hotcodepush.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

// The endpoint answers 400 for an absent required key, so a nullable key travels as `null` and only an optional one is left out.
class DeviceEventsContractTest {
    private val events = listOf(
        DeviceEvent.checked("r2", SyncStatus.AVAILABLE),
        DeviceEvent.checked("r3", SyncStatus.SKIPPED, SkippedReason.DEVICE_INCOMPATIBLE, ConditionType.BINARY),
        DeviceEvent.checked("r4", SyncStatus.SKIPPED, SkippedReason.CONDITION_UNSUPPORTED),
        DeviceEvent.downloaded("r2", 2048, PackKind.STREAMED),
        DeviceEvent.applied("r2"),
        DeviceEvent.confirmed("r2"),
        DeviceEvent.failed("r2", FailedReason.SIGNATURE_INVALID.wire),
        DeviceEvent.failed("r2", FailedReason.MANIFEST_INVALID.wire),
        DeviceEvent.failed("r2", FailedReason.CONTENT_MISMATCHED.wire),
        DeviceEvent.failed("r2", RollbackReason.APP_REQUESTED.wire, "checkout broke"),
    )

    @Test
    fun shouldRoundTripEveryEventKindThroughTheOutbox() {
        val store = InMemoryStore()
        StateStore(store).unsentEvents = events
        val read = StateStore(store).unsentEvents
        assertEquals(events, read)
        assertEquals(events.map { it.toJson().toString() }, read.map { it.toJson().toString() })
    }

    @Test
    fun shouldSendWhatACoreQueuedThroughARollbackToTheEmbeddedBundleAsTheSchemaAcceptsIt() = runBlocking {
        val harness = Harness(Fixture.configuration(applyStrategy = ApplyStrategy.IMMEDIATE))
        harness.publish(listOf(Fixture.release(1, "b2", "<html>v2</html>".toByteArray())), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleReadyTimeout()
        harness.acknowledgeEvents()
        harness.core.sync(SyncTrigger.MANUAL)
        val batch = JSONObject(String(harness.http.posts.last().third))
        val sent = batch.getJSONArray("events").map { it }
        assertEquals(listOf("checked", "downloaded", "applied", "failed"), sent.map { it.getString("type") })
        assertEquals(RollbackReason.READINESS_TIMED_OUT.wire, sent.last().getString("reason"))
        assertEquals("", resolveSchemaIssues(batch))
        assertTrue(StateStore(harness.store).unsentEvents.isEmpty())
    }

    private fun resolveSchemaIssues(batch: JSONObject): String {
        val script = """
            import { DeviceEventsRequestSchema } from '@hotcodepush/protocol';
            let input = '';
            for await (const chunk of process.stdin) input += chunk;
            const result = DeviceEventsRequestSchema.safeParse(JSON.parse(input));
            process.stdout.write(result.success ? '' : JSON.stringify(result.error.issues));
        """.trimIndent()
        val process = ProcessBuilder("node", "--input-type=module", "-e", script).redirectErrorStream(true).start()
        process.outputStream.use { it.write(batch.toString().toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("node did not answer within a minute", process.waitFor(1, TimeUnit.MINUTES))
        assertEquals(output, 0, process.exitValue())
        return output
    }
}
