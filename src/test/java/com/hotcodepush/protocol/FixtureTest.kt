package com.hotcodepush.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        runtimeVersion = json.optNullableString("runtimeVersion"),
    )

    private fun load(path: String): JSONObject = JSONObject(File(fixturesDirectory, path).readText())

    @Test
    fun shouldMatchEveryEvaluationFixture() {
        val files = File(fixturesDirectory, "evaluation").listFiles { file -> file.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        assertTrue("no evaluation fixtures at $fixturesDirectory", files.isNotEmpty())
        var count = 0
        for (file in files) {
            val cases = JSONObject(file.readText()).getJSONArray("cases")
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val actual = Expected.of(Evaluator.evaluate(ChannelIndex.fromJson(case.getJSONObject("index")), deviceInfo(case.getJSONObject("device"))))
                assertEquals("${file.name}: ${case.getString("name")}", Expected.fromJson(case.getJSONObject("expected")), actual)
                count++
            }
        }
        assertTrue(count > 50)
    }

    @Test
    fun shouldMatchEveryVersionRangeFixture() {
        val cases = load("version-ranges.json").getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val version = VersionRange.parseVersion(case.getString("version"))!!
            val expected = if (case.isNull("satisfied")) null else case.getBoolean("satisfied")
            assertEquals("${case.getString("version")} in ${case.getString("range")}", expected, VersionRange.isVersionInRange(version, case.getString("range")))
        }
    }

    @Test
    fun shouldMatchEveryRolloutBucketFixture() {
        val cases = load("rollout-buckets.json").getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(case.toString(), case.getInt("bucket"), Hashing.rolloutBucket(case.getString("deviceId"), case.getString("releaseId")))
        }
    }

    @Test
    fun shouldReadEveryResourceFileFixture() {
        val cases = load("resource-files.json").getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val configuration = Configuration.fromJson(case.getJSONObject("resourceFile"))
            assertEquals(case.getString("name"), BundleManifest.fromJson(case.getJSONObject("embeddedBundleManifest")), configuration.embeddedBundleManifest)
        }
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
            assertEquals(entry.getString("sha256"), entries[index].sha256)
            assertEquals(entry.getString("content"), String(entries[index].body, Charsets.UTF_8))
        }
        assertTrue(PackWriter.pack(entries).contentEquals(pack))
    }

    @Test
    fun shouldRefuseEveryRefusedPackFixture() {
        val cases = load("packs.json").getJSONArray("refusedPacks")
        assertTrue(cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val pack = Base64.getDecoder().decode(case.getString("packBase64"))
            assertThrows(case.getString("name"), PackFormatException::class.java) { PackReader.entries(pack) }
        }
    }
}
