package com.hotcodepush.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The batch to the events endpoint as `@hotcodepush/protocol`'s `DeviceEventsRequestSchema` reads it: the endpoint answers
 * 400 for a required key that is absent, so a nullable key travels as `null` and only an optional one is left out.
 */
class DeviceEventsContractTest {
    private val events = listOf(
        DeviceEvent.checked("r2", SyncStatus.AVAILABLE),
        DeviceEvent.checked("r3", SyncStatus.SKIPPED, SkippedReason.DEVICE_INCOMPATIBLE, ConditionType.BINARY),
        DeviceEvent.checked("r4", SyncStatus.SKIPPED, SkippedReason.CONDITION_UNSUPPORTED),
        DeviceEvent.downloaded("r2", "b2", 2048, PackKind.STREAMED),
        DeviceEvent.applied("r2"),
        DeviceEvent.confirmed("r2"),
        DeviceEvent.failed("r2", FailedReason.SIGNATURE_INVALID.name),
        DeviceEvent.failed("r2", FailedReason.MANIFEST_INVALID.name),
        DeviceEvent.failed("r2", FailedReason.CONTENT_MISMATCHED.name),
        DeviceEvent.failed("r2", RollbackReason.APP_REQUESTED.name, "checkout broke"),
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
                setOf("type", "releaseId", "reason"),
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
        val report = DeviceReport(emptyMap(), "57", "2.4.1", "c1", ChannelSource.CONFIG, embeddedBundleId = null, fingerprint = null, osVersion = "14", releaseId = null).toJson()
        assertEquals(setOf("attributes", "binaryBuild", "binaryVersion", "channelId", "channelSource", "embeddedBundleId", "fingerprint", "osVersion", "releaseId"), keys(report))
        for (key in listOf("embeddedBundleId", "fingerprint", "releaseId")) assertTrue(key, report.isNull(key))
        val request = DeviceEventsRequest("d1", emptyList(), "android", null, "0.0.0").toJson()
        assertEquals(setOf("deviceId", "events", "platform", "report", "sdkVersion"), keys(request))
        assertTrue(request.isNull("report"))
    }

    @Test
    fun shouldEncodeEveryEventKindAndTheReportAsTheProtocolsSchemaAcceptsThem() {
        val report = DeviceReport(mapOf("plan" to "pro"), "57", "2.4.1", "c1", ChannelSource.RUNTIME, "embedded", "fp1:abc", "14", "r2")
        assertEquals("", resolveSchemaIssues(DeviceEventsRequest("d1", events, "android", report, "0.0.0").toJson()))
        val empty = DeviceReport(emptyMap(), "57", "2.4.1", "c1", ChannelSource.CONFIG, null, null, "14", null)
        assertEquals("", resolveSchemaIssues(DeviceEventsRequest("d1", emptyList(), "android", empty, "0.0.0").toJson()))
        assertEquals("", resolveSchemaIssues(DeviceEventsRequest("d1", emptyList(), "android", null, "0.0.0").toJson()))
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

    /**
     * Every batch the endpoint accepts, read into the core's types, is written back key for key, the events the endpoint skips
     * left out and the fields the core does not know dropped: a nullable key as `null`, an optional one left out, as the encoder must.
     */
    @Test
    fun shouldWriteEveryAcceptedBatchOfTheDeviceEventsFixtureBackKeyForKey() {
        val fixture = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/device-events.json").readText())
        val cases = fixture.getJSONArray("acceptedBatches").map { it }
        assertTrue(cases.size > 10)
        for (case in cases) {
            val batch = case.getJSONObject("batch")
            val skipped = case.getJSONArray("skippedEventIndexes").let { array -> List(array.length()) { array.getInt(it) } }
            val events = batch.getJSONArray("events").map { it }.filterIndexed { index, _ -> index !in skipped }
            val report = batch.getNullableObject("report")
            val written = DeviceEventsRequest(batch.getString("deviceId"), events.map(DeviceEvent::fromJson), batch.getString("platform"), report?.let(DeviceReport::fromJson), batch.getString("sdkVersion")).toJson()
            val expected = retainKeys(batch, BATCH_KEYS)
                .put("events", JSONArray(events.map { retainKeys(it, EVENT_KEYS) }))
                .put("report", report?.let { retainKeys(it, REPORT_KEYS) } ?: JSONObject.NULL)
            assertEquals(case.getString("name"), resolveComparable(expected), resolveComparable(written))
        }
    }

    /** No batch the endpoint refuses can be rebuilt into one the device would send: the core's types cannot hold it, or it is not readable, or it writes back otherwise. */
    @Test
    fun shouldNeverWriteABatchTheDeviceEventsFixtureRefuses() {
        val fixture = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/device-events.json").readText())
        val cases = fixture.getJSONArray("refusedBatches").map { it }
        assertTrue(cases.size > 10)
        for (case in cases) {
            val batch = case.getJSONObject("batch")
            val request = runCatching {
                DeviceEventsRequest(batch.getString("deviceId"), batch.getJSONArray("events").map(DeviceEvent::fromJson), batch.getString("platform"), batch.getNullableObject("report")?.let(DeviceReport::fromJson), batch.getString("sdkVersion"))
            }.getOrNull() ?: continue
            if (request.isReadable) assertFalse(case.getString("name"), resolveComparable(request.toJson()) == resolveComparable(batch))
        }
    }

    private fun retainKeys(json: JSONObject, keys: Set<String>): JSONObject = JSONObject(json.toString()).apply { json.keys().asSequence().filter { it !in keys }.toList().forEach(::remove) }

    /** A JSON value as plain values that compare by content: an object as a map, an array as a list, a whole number as a Long. */
    private fun resolveComparable(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { resolveComparable(value.get(it)) }
        is JSONArray -> List(value.length()) { resolveComparable(value.get(it)) }
        is Int -> value.toLong()
        else -> value
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

    private companion object {
        val BATCH_KEYS = setOf("deviceId", "events", "platform", "report", "sdkVersion")
        val EVENT_KEYS = setOf("type", "releaseId", "bundleId", "status", "reason", "condition", "bytes", "packKind", "fromReleaseId", "toReleaseId", "detail")
        val REPORT_KEYS = setOf("attributes", "binaryBuild", "binaryVersion", "channelId", "channelSource", "embeddedBundleId", "fingerprint", "osVersion", "releaseId")
    }
}
