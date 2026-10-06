package com.hotcodepush.core

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/** The protocol's fixture suite, read from the installed `@hotcodepush/protocol` package: the same cases every core runs. */
class FixtureTest {
    private val fixturesDirectory = File("node_modules/@hotcodepush/protocol/fixtures").canonicalFile

    private data class Expected(val status: String, val releaseId: String?, val isMandatory: Boolean?, val reason: String?, val condition: String?) {
        companion object {
            fun of(evaluation: Evaluation) = when (evaluation) {
                is Evaluation.UpToDate -> Expected("UP_TO_DATE", evaluation.release?.id, null, null, null)
                is Evaluation.Available -> Expected("AVAILABLE", evaluation.release.id, evaluation.isMandatory, null, null)
                is Evaluation.Skipped -> Expected("SKIPPED", evaluation.release?.id, null, evaluation.reason.name, evaluation.condition?.wire)
            }

            fun fromJson(json: JSONObject) = Expected(
                json.getString("status"),
                json.optNullableString("releaseId"),
                if (json.has("isMandatory")) json.getBoolean("isMandatory") else null,
                json.optNullableString("reason"),
                json.optNullableString("condition"),
            )
        }
    }

    private data class ExpectedVerdict(val releaseId: String, val isEligible: Boolean, val reason: String?, val condition: String?) {
        companion object {
            fun of(verdict: ReleaseVerdict) = ExpectedVerdict(verdict.release.id, verdict.isEligible, verdict.reason?.name, verdict.condition?.wire)

            fun fromJson(json: JSONObject) = ExpectedVerdict(json.getString("releaseId"), json.getBoolean("isEligible"), json.optNullableString("reason"), json.optNullableString("condition"))
        }
    }

    private fun deviceInfo(json: JSONObject) = DeviceInfo(
        appliedIndexSequence = if (json.isNull("appliedIndexSequence")) null else json.getInt("appliedIndexSequence"),
        attributes = json.getJSONObject("attributes").let { attributes -> attributes.keys().asSequence().associateWith { attributes.getString(it) } },
        binaryBuild = json.getString("binaryBuild"),
        binaryVersion = json.getString("binaryVersion"),
        builtAt = Iso8601.parse(json.getString("builtAt")),
        currentRelease = json.optJSONObject("currentRelease")?.let { Release(it.getString("id"), it.getInt("number"), "", "", false) },
        deviceId = json.getString("deviceId"),
        failedBundleIds = json.getJSONArray("failedBundleIds").toStringList(),
        fingerprint = json.optNullableString("fingerprint"),
        osVersion = json.getString("osVersion"),
        reportedAt = json.optNullableString("reportedAt")?.let(Iso8601::parse),
    )

    private fun load(path: String): JSONObject = JSONObject(File(fixturesDirectory, path).readText())

    private fun cases(path: String, key: String): List<JSONObject> {
        val cases = load(path).getJSONArray(key)
        assertTrue(key, cases.length() > 0)
        return List(cases.length()) { cases.getJSONObject(it) }
    }

    private fun assertAccepted(path: String, key: String, document: String, decode: (JSONObject) -> Any) {
        for (case in cases(path, key)) {
            try {
                decode(case.getJSONObject(document))
            } catch (exception: Exception) {
                fail("$key: ${case.getString("name")}: $exception")
            }
        }
    }

    private fun assertRefused(path: String, key: String, document: String, decode: (JSONObject) -> Any) {
        for (case in cases(path, key)) {
            assertThrows("$key: ${case.getString("name")}", JSONException::class.java) { decode(case.getJSONObject(document)) }
        }
    }

    @Test
    fun shouldMatchEveryEvaluationFixture() {
        val files = File(fixturesDirectory, "evaluation").listFiles { file -> file.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        assertTrue("no evaluation fixtures at $fixturesDirectory", files.isNotEmpty())
        var count = 0
        var verdictCount = 0
        for (file in files) {
            val cases = JSONObject(file.readText()).getJSONArray("cases")
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = "${file.name}: ${case.getString("name")}"
                val evaluation = Evaluator.evaluation(ChannelIndex.fromJson(case.getJSONObject("index")), deviceInfo(case.getJSONObject("device")))
                assertEquals(name, Expected.fromJson(case.getJSONObject("expected")), Expected.of(evaluation.outcome))
                count++
                if (case.has("verdicts")) {
                    val verdicts = case.getJSONArray("verdicts").let { array -> List(array.length()) { ExpectedVerdict.fromJson(array.getJSONObject(it)) } }
                    assertEquals(name, verdicts, evaluation.verdicts.map(ExpectedVerdict::of))
                    verdictCount++
                }
            }
        }
        assertTrue(count > 50)
        assertTrue(verdictCount >= 16)
    }

    @Test
    fun shouldAcceptEveryAcceptedWireRulesFixture() {
        assertAccepted("wire-rules.json", "acceptedIndexes", "index", ChannelIndex::fromJson)
        assertAccepted("wire-rules.json", "acceptedManifests", "manifest", BundleManifest::fromJson)
        assertAccepted("wire-rules.json", "acceptedEnvelopes", "envelope", ManifestEnvelope::fromJson)
    }

    @Test
    fun shouldRefuseEveryRefusedWireRulesFixture() {
        assertRefused("wire-rules.json", "refusedIndexes", "index", ChannelIndex::fromJson)
        assertRefused("wire-rules.json", "refusedManifests", "manifest", BundleManifest::fromJson)
        assertRefused("wire-rules.json", "refusedEnvelopes", "envelope", ManifestEnvelope::fromJson)
    }

    @Test
    fun shouldMatchEveryVersionRangeFixture() {
        for (case in cases("version-ranges.json", "cases")) {
            val version = VersionRange.parseVersion(case.getString("version"))!!
            val expected = if (case.isNull("satisfied")) null else case.getBoolean("satisfied")
            assertEquals("${case.getString("version")} in ${case.getString("range")}", expected, VersionRange.isVersionInRange(version, case.getString("range")))
        }
    }

    @Test
    fun shouldMatchEveryRolloutBucketFixture() {
        for (case in cases("rollout-buckets.json", "cases")) {
            assertEquals(case.toString(), case.getInt("bucket"), Hashing.rolloutBucket(case.getString("deviceId"), case.getString("releaseId")))
        }
    }

    @Test
    fun shouldReadEveryResourceFileFixture() {
        for (case in cases("resource-files.json", "cases")) {
            val configuration = Configuration.fromJson(case.getJSONObject("resourceFile"))
            assertEquals(case.getString("name"), case.getNullableObject("embeddedBundleManifest")?.let(EmbeddedBundleManifest::fromJson), configuration.embeddedBundleManifest)
        }
    }

    /** Every signed manifest of the suite is a manifest this reader decodes, its signature in the wire's form; `SigningTest` verifies them. */
    @Test
    fun shouldDecodeTheManifestOfEverySignatureFixture() {
        val cases = cases("signatures.json", "manifests")
        assertTrue(cases.size > 5)
        for (case in cases) {
            val envelope = case.getJSONObject("envelope")
            BundleManifest.fromJson(JSONObject(envelope.getString("manifest")))
            envelope.getNullableObject("signature")?.let(Signature::fromJson)
        }
    }

    /** The bounds are the writer's: a reader takes a release with more conditions, and a device condition with more ids, than the API accepts. */
    @Test
    fun shouldReadPastTheWriterBoundsFixture() {
        val bounds = load("bounds.json")
        val hashedIds = (0..bounds.getInt("deviceConditionMaxHashedIds")).map { Hashing.sha256Hex("device-$it") }
        val conditions = (0..bounds.getInt("releaseMaxConditions")).map { Condition.Device(hashedIds) }
        val release = IndexRelease("r1", 1, Fixture.BUILT_AT, false, null, 100, conditions, "b1", "1.0.0", "${Fixture.FILES_BASE_URL}/manifest.json", Hashing.sha256Hex("manifest"), 1)
        val index = Fixture.index(1, listOf(release))
        assertEquals(index, ChannelIndex.fromJson(index.toJson()))
    }

    @Test
    fun shouldReadAndWriteThePackFixture() {
        val fixture = load("packs.json")
        val pack = Base64.getDecoder().decode(fixture.getString("packBase64"))
        assertEquals(fixture.getString("packSha256"), Hashing.sha256Hex(pack))
        val entries = PackReader.entries(pack)
        val expected = fixture.getJSONArray("entries")
        assertEquals(expected.length(), entries.size)
        for (index in entries.indices) {
            val entry = expected.getJSONObject(index)
            assertEquals(entry.getString("sha256"), (entries[index] as PackEntry.File).sha256)
            assertEquals(entry.getString("content"), String(entries[index].body, Charsets.UTF_8))
        }
        assertTrue(PackWriter.pack(entries).contentEquals(pack))
    }

    @Test
    fun shouldReadAndWriteTheDeltaPackOfThePackEntriesFixture() {
        val fixture = load("pack-entries.json").getJSONObject("deltaPack")
        val pack = Base64.getDecoder().decode(fixture.getString("packBase64"))
        assertEquals(fixture.getString("packSha256"), Hashing.sha256Hex(pack))
        val entries = PackReader.entries(pack)
        assertEquals(fixture.getJSONArray("entries").map(::describeFixtureEntry), entries.map(::describeEntry))
        assertTrue(PackWriter.pack(entries).contentEquals(pack))
    }

    @Test
    fun shouldSkipTheUnknownEntryOfThePackEntriesFixture() {
        val fixture = load("pack-entries.json").getJSONObject("skippedEntryPack")
        val pack = Base64.getDecoder().decode(fixture.getString("packBase64"))
        assertEquals(fixture.getJSONArray("entries").map(::describeFixtureEntry), PackReader.entries(pack).map(::describeEntry))
    }

    /** An entry as the values a test compares, its body by content. */
    private fun describeEntry(entry: PackEntry): List<String> = when (entry) {
        is PackEntry.File -> listOf("file", entry.sha256, Base64.getEncoder().encodeToString(entry.body))
        is PackEntry.Patch -> listOf("patch", entry.fromSha256, entry.toSha256, Base64.getEncoder().encodeToString(entry.body))
    }

    private fun describeFixtureEntry(json: JSONObject): List<String> = when (json.getString("type")) {
        "file" -> listOf("file", json.getString("sha256"), json.getString("bodyBase64"))
        else -> listOf(json.getString("type"), json.getString("fromSha256"), json.getString("toSha256"), json.getString("bodyBase64"))
    }

    @Test
    fun shouldRefuseEveryRefusedPackFixture() {
        for (case in cases("packs.json", "refusedPacks")) {
            val pack = Base64.getDecoder().decode(case.getString("packBase64"))
            assertThrows(case.getString("name"), PackFormatException::class.java) { PackReader.entries(pack) }
        }
    }
}
