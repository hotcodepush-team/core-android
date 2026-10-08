package com.hotcodepush.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class WireTypesTest {
    private val sha256 = Hashing.sha256Hex("content")

    @Test
    fun shouldReadAManifestStoredWhileBundlesCarriedPatches() {
        val stored = """{"appId":"a1","bundleVersion":"1.0.0","files":[{"path":"index.html","sha256":"$sha256","sizeBytes":7}],"fingerprint":null,"keyId":null,"patches":[],"platforms":["android"]}"""
        val manifest = BundleManifest(appId = "a1", bundleVersion = "1.0.0", files = listOf(BundleManifest.File("index.html", sha256, 7)), platforms = listOf("android"))
        assertEquals(manifest, BundleManifest.fromJson(JSONObject(stored)))
    }

    @Test
    fun shouldRefuseAnEnvelopeWhoseBundleIdIsNotAnIdentifier() {
        for (bundleId in listOf("../..", "", "bundles/b1", "b".repeat(65), "bündle")) {
            assertThrows(bundleId, JSONException::class.java) { decodeEnvelope(bundleId) }
        }
    }

    @Test
    fun shouldRefuseAManifestPathThatClimbsOutOfItsDirectory() {
        for (path in listOf("../escape.html", "assets/../../escape.html", "/etc/hosts", "assets//app.js", "./index.html", "assets\\..\\escape.html", "index\u0000.html", "", "../́escape.html")) {
            assertThrows(path, JSONException::class.java) { decodeManifest(path, sha256) }
        }
    }

    @Test
    fun shouldRefuseAManifestFileHashThatIsNotALowercaseSha256() {
        for (hash in listOf("../../files/escape", sha256.uppercase(), sha256.dropLast(1))) {
            assertThrows(hash, JSONException::class.java) { decodeManifest("index.html", hash) }
        }
    }

    @Test
    fun shouldRefuseAnIndexReleaseWhoseIdsAreNotIdentifiers() {
        assertThrows(JSONException::class.java) { decodeIndexRelease("../r1", "b1") }
        assertThrows(JSONException::class.java) { decodeIndexRelease("r1", "../..") }
    }

    @Test
    fun shouldRefuseAnIndexReleaseWhoseManifestHashIsNotALowercaseSha256() {
        assertThrows(JSONException::class.java) { decodeIndexRelease("r1", "b1", manifestSha256 = sha256.uppercase()) }
        assertThrows(JSONException::class.java) { decodeIndexRelease("r1", "b1", manifestSha256 = "../escape") }
    }

    @Test
    fun shouldRefuseAnIndexReleaseMissingAField() {
        val json = decodeIndexRelease("r1", "b1").toJson()
        for (key in listOf("isMandatory", "rollout", "conditions", "sizeBytes", "notes")) {
            val incomplete = JSONObject(json.toString()).also { it.remove(key) }
            assertThrows(key, JSONException::class.java) { IndexRelease.fromJson(incomplete) }
        }
    }

    @Test
    fun shouldRefuseAValueCoercedFromAnotherType() {
        val json = decodeIndexRelease("r1", "b1").toJson()
        assertThrows(JSONException::class.java) { IndexRelease.fromJson(JSONObject(json.toString()).put("id", 2)) }
        assertThrows(JSONException::class.java) { IndexRelease.fromJson(JSONObject(json.toString()).put("isMandatory", "true")) }
        assertThrows(JSONException::class.java) { IndexRelease.fromJson(JSONObject(json.toString()).put("rollout", 50.5)) }
        assertThrows(JSONException::class.java) { IndexRelease.fromJson(JSONObject(json.toString()).put("sizeBytes", "7")) }
    }

    @Test
    fun shouldAcceptTheIdsAndPathsTheApiWrites() {
        val bundleId = "0f8fad5b-d9cb-469f-a165-70867728950e"
        for (path in listOf("index.html", "assets/index-a1b2c3.js", ".well-known/assetlinks.json", "assets/..hidden")) {
            assertEquals(path, decodeManifest(path, sha256).files.single().path)
        }
        assertEquals(bundleId, decodeEnvelope(bundleId).bundleId)
        assertEquals(bundleId, decodeIndexRelease("1c6e2a3b-7f4d-4e1a-9b2c-3d4e5f6a7b8c", bundleId).bundleId)
    }

    @Test
    fun shouldRefuseAResourceFileWhoseHostIsNotAnHttpOrHttpsUrl() {
        for (url in listOf("ftp://files.test", "file:///files", "files.test")) {
            assertThrows(url, JSONException::class.java) { Configuration.fromJson(resourceFile().put("filesBaseUrl", url)) }
            assertThrows(url, JSONException::class.java) { Configuration.fromJson(resourceFile().put("updatesBaseUrl", url)) }
        }
    }

    @Test
    fun shouldRefuseAResourceFileWhoseAppIdIsNotAnIdentifier() {
        for (appId in listOf("../other-app", "app/channels", "app?query", "")) {
            assertThrows(appId, JSONException::class.java) { Configuration.fromJson(resourceFile().put("appId", appId)) }
        }
    }

    @Test
    fun shouldRefuseAResourceFileWhoseReadyTimeoutSecondsIsBelowOneInsteadOfRaisingIt() {
        for (seconds in listOf(0, 0.5)) {
            assertThrows(seconds.toString(), JSONException::class.java) { Configuration.fromJson(resourceFile().put("readyTimeoutSeconds", seconds)) }
        }
        assertEquals(1.0, Configuration.fromJson(resourceFile().put("readyTimeoutSeconds", 1)).readyTimeoutSeconds, 0.0)
    }

    @Test
    fun shouldRefuseAResourceFileWithANegativeDuration() {
        for (key in listOf("applyOnResumeAfterSeconds", "checkIntervalSeconds", "readyTimeoutSeconds")) {
            assertThrows(key, JSONException::class.java) { Configuration.fromJson(resourceFile().put(key, -1)) }
        }
        assertEquals(0.0, Configuration.fromJson(resourceFile().put("applyOnResumeAfterSeconds", 0)).applyOnResumeAfterSeconds, 0.0)
    }

    @Test
    fun shouldRefuseAResourceFileWhoseDurationIsNotANumber() {
        for (value in listOf<Any>("900", true)) {
            assertThrows(value.toString(), JSONException::class.java) { Configuration.fromJson(resourceFile().put("checkIntervalSeconds", value)) }
        }
    }

    @Test
    fun shouldReadADurationOfNullAsTheDefault() {
        assertEquals(900.0, Configuration.fromJson(resourceFile().put("checkIntervalSeconds", JSONObject.NULL)).checkIntervalSeconds, 0.0)
    }

    @Test
    fun shouldRefuseAChannelsIndexOfAnotherSchemaOrWithAChannelIdThatIsNotAUuid() {
        val entry = JSONObject().put("id", Fixture.CHANNEL_ID).put("name", "beta")
        assertEquals(listOf(ChannelsIndex.Entry(Fixture.CHANNEL_ID, "beta")), ChannelsIndex.fromJson(JSONObject().put("schema", 1).put("channels", JSONArray().put(entry))).channels)
        assertThrows(JSONException::class.java) { ChannelsIndex.fromJson(JSONObject().put("schema", 2).put("channels", JSONArray().put(entry))) }
        for (id in listOf("../../other-app/channels/c1", "c1", "${Fixture.CHANNEL_ID}?x=1")) {
            assertThrows(id, JSONException::class.java) { ChannelsIndex.fromJson(JSONObject().put("schema", 1).put("channels", JSONArray().put(JSONObject(entry.toString()).put("id", id)))) }
        }
    }

    private fun decodeManifest(path: String, sha256: String): BundleManifest {
        val manifest = BundleManifest(appId = Fixture.APP_ID, bundleVersion = "1.0.0", files = listOf(BundleManifest.File(path, sha256, 7)), platforms = listOf("android"))
        return BundleManifest.fromJson(manifest.toJson())
    }

    private fun decodeEnvelope(bundleId: String): ManifestEnvelope {
        val envelope = ManifestEnvelope(bundleId, Fixture.BUILT_AT, "{}", null, ManifestEnvelope.Pack("${Fixture.FILES_BASE_URL}/pack", 1), emptyList())
        return ManifestEnvelope.fromJson(envelope.toJson())
    }

    private fun decodeIndexRelease(id: String, bundleId: String, manifestSha256: String = sha256): IndexRelease {
        val release = IndexRelease(id, 1, Fixture.BUILT_AT, false, null, 100, emptyList(), bundleId, "1.0.0", "${Fixture.FILES_BASE_URL}/manifest.json", manifestSha256, 7)
        return IndexRelease.fromJson(release.toJson())
    }

    private fun resourceFile(): JSONObject = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/resource-files.json").readText()).getJSONArray("cases").getJSONObject(0).getJSONObject("resourceFile")
}
