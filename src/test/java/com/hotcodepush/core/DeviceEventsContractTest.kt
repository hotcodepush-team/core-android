package com.hotcodepush.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The batch to the events endpoint as `@hotcodepush/protocol`'s `DeviceEventsRequestSchema` reads it: the endpoint answers
 * 400 for a required key that is absent, so a nullable key travels as `null` and only an optional one is left out.
 */
class DeviceEventsContractTest {
    private val events = listOf(
        DeviceEvent.checked("r2", SyncStatus.AVAILABLE),
        DeviceEvent.checked("r3", SyncStatus.SKIPPED, SkippedReason.INCOMPATIBLE, ConditionType.BINARY),
        DeviceEvent.checked("r4", SyncStatus.SKIPPED, SkippedReason.UNSUPPORTED_CONDITION),
        DeviceEvent.downloaded("r2", "b2", 2048, PackKind.STREAMED),
        DeviceEvent.applied("r2"),
        DeviceEvent.confirmed("r2"),
        DeviceEvent.failed("r2", FailedReason.INVALID_SIGNATURE.name),
        DeviceEvent.failed("r2", RollbackReason.REPORTED_BY_APP.name, "checkout broke"),
        DeviceEvent.rolledBack("r3", "r2"),
        DeviceEvent.rolledBack("r2", null),
    )

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    @Test
    fun shouldCarryTheKeysOfEachEventKind() {
        assertEquals(
            listOf(
                setOf("type", "releaseId", "status"),
                setOf("type", "releaseId", "status", "reason", "condition"),
                setOf("type", "releaseId", "status", "reason"),
                setOf("type", "releaseId", "bundleId", "bytes", "packKind"),
                setOf("type", "releaseId"),
                setOf("type", "releaseId"),
                setOf("type", "releaseId", "reason"),
                setOf("type", "releaseId", "reason", "detail"),
                setOf("type", "fromReleaseId", "toReleaseId"),
                setOf("type", "fromReleaseId", "toReleaseId"),
            ),
            events.map { keys(it.toJson()) },
        )
    }

    @Test
    fun shouldSendARollbackToTheEmbeddedBundleWithANullToReleaseId() {
        val json = DeviceEvent.rolledBack("r2", null).toJson()
        assertTrue(json.has("toReleaseId"))
        assertTrue(json.isNull("toReleaseId"))
        assertTrue(json.toString().contains("\"toReleaseId\":null"))
    }

    @Test
    fun shouldRoundTripEachEventKindThroughTheOutbox() {
        val store = InMemoryStore()
        StateStore(store).unsentEvents = events
        val read = StateStore(store).unsentEvents
        assertEquals(events, read)
        assertEquals(events.map { it.toJson().toString() }, read.map { it.toJson().toString() })
    }

    @Test
    fun shouldCarryEveryReportKeyWithNullsForTheEmptyOnes() {
        val report = DeviceReport(emptyMap(), "57", "2.4.1", "c1", ChannelSource.CONFIG, embeddedBundleId = null, fingerprint = null, osVersion = "14", releaseId = null, runtimeVersion = null).toJson()
        assertEquals(setOf("attributes", "binaryBuild", "binaryVersion", "channelId", "channelSource", "embeddedBundleId", "fingerprint", "osVersion", "releaseId", "runtimeVersion"), keys(report))
        for (key in listOf("embeddedBundleId", "fingerprint", "releaseId", "runtimeVersion")) assertTrue(key, report.isNull(key))
        val request = DeviceEventsRequest("d1", emptyList(), "android", null, "0.0.0").toJson()
        assertEquals(setOf("deviceId", "events", "platform", "report", "sdkVersion"), keys(request))
        assertTrue(request.isNull("report"))
    }

    @Test
    fun shouldEncodeEveryEventKindAndTheReportAsTheProtocolsSchemaAcceptsThem() {
        val report = DeviceReport(mapOf("plan" to "pro"), "57", "2.4.1", "c1", ChannelSource.RUNTIME, "embedded", "fp1:abc", "14", "r2", runtimeVersion = null)
        assertEquals("", resolveSchemaIssues(DeviceEventsRequest("d1", events, "android", report, "0.0.0").toJson()))
        val empty = DeviceReport(emptyMap(), "57", "2.4.1", "c1", ChannelSource.CONFIG, null, null, "14", null, null)
        assertEquals("", resolveSchemaIssues(DeviceEventsRequest("d1", emptyList(), "android", empty, "0.0.0").toJson()))
        assertEquals("", resolveSchemaIssues(DeviceEventsRequest("d1", emptyList(), "android", null, "0.0.0").toJson()))
    }

    @Test
    fun shouldSendWhatACoreQueuedThroughARollbackToTheEmbeddedBundleAsTheSchemaAcceptsIt() = runBlocking {
        val harness = Harness(Fixture.configuration(installStrategy = InstallStrategy.IMMEDIATE))
        harness.publish(listOf(Fixture.release(1, "b2", "<html>v2</html>".toByteArray())), 1)
        harness.core.handleAppStart()
        harness.core.handleRendered()
        harness.core.sync(SyncTrigger.MANUAL)
        harness.core.handleReadyTimeout()
        harness.acknowledgeEvents()
        harness.core.sync(SyncTrigger.MANUAL)
        val batch = JSONObject(String(harness.http.posts.last().third))
        val sent = batch.getJSONArray("events").map { it }
        assertEquals(listOf("checked", "downloaded", "applied", "failed", "rolledBack"), sent.map { it.getString("type") })
        assertTrue(sent.last().has("toReleaseId") && sent.last().isNull("toReleaseId"))
        assertEquals("", resolveSchemaIssues(batch))
        assertTrue(StateStore(harness.store).unsentEvents.isEmpty())
    }

    @Test
    fun shouldBeRefusedByTheSchemaWhenARollbackLeavesItsToReleaseIdOut() {
        val event = DeviceEvent.rolledBack("r2", null).toJson().apply { remove("toReleaseId") }
        val batch = DeviceEventsRequest("d1", emptyList(), "android", null, "0.0.0").toJson().put("events", JSONArray(listOf(event)))
        assertFalse(resolveSchemaIssues(batch).isEmpty())
    }

    /** The issues `DeviceEventsRequestSchema` of the installed protocol package finds in the batch, empty when it accepts it; Node runs the schema. */
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
