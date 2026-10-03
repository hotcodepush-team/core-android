package com.hotcodepush.protocol

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireTypesTest {
    private val sha256 = Hashing.sha256Hex("content")

    @Test
    fun shouldRefuseAManifestWhoseBundleIdIsNotAnIdentifier() {
        for (bundleId in listOf("../..", "", "bundles/b1", "b".repeat(65), "bündle")) {
            assertThrows(bundleId, JSONException::class.java) { decodeManifest(bundleId, "index.html", sha256) }
        }
    }

    @Test
    fun shouldRefuseAManifestPathThatClimbsOutOfItsDirectory() {
        for (path in listOf("../escape.html", "assets/../../escape.html", "/etc/hosts", "assets//app.js", "./index.html", "assets\\..\\escape.html", "index\u0000.html", "")) {
            assertThrows(path, JSONException::class.java) { decodeManifest("b1", path, sha256) }
        }
    }

    @Test
    fun shouldRefuseAManifestFileHashThatIsNotALowercaseSha256() {
        for (hash in listOf("../../files/escape", sha256.uppercase(), sha256.dropLast(1))) {
            assertThrows(hash, JSONException::class.java) { decodeManifest("b1", "index.html", hash) }
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
    fun shouldRefuseAnIndexReleaseMissingAFieldTheSwiftCoreRequires() {
        val json = decodeIndexRelease("r1", "b1").toJson()
        for (key in listOf("isMandatory", "rollout", "conditions", "sizeBytes")) {
            val incomplete = JSONObject(json.toString()).also { it.remove(key) }
            assertThrows(key, JSONException::class.java) { IndexRelease.fromJson(incomplete) }
        }
    }

    @Test
    fun shouldAcceptTheIdsAndPathsTheApiWrites() {
        val bundleId = "0f8fad5b-d9cb-469f-a165-70867728950e"
        for (path in listOf("index.html", "assets/index-a1b2c3.js", ".well-known/assetlinks.json", "assets/..hidden")) {
            assertEquals(path, decodeManifest(bundleId, path, sha256).files.single().path)
        }
        assertEquals(bundleId, decodeIndexRelease("1c6e2a3b-7f4d-4e1a-9b2c-3d4e5f6a7b8c", bundleId).bundleId)
    }

    private fun decodeManifest(bundleId: String, path: String, sha256: String): BundleManifest {
        val manifest = BundleManifest(bundleId, Fixture.APP_ID, "1.0.0", Fixture.BUILT_AT, listOf(BundleManifest.File(path, sha256, 7)), null, emptyList())
        return BundleManifest.fromJson(manifest.toJson())
    }

    private fun decodeIndexRelease(id: String, bundleId: String, manifestSha256: String = sha256): IndexRelease {
        val release = IndexRelease(id, 1, Fixture.BUILT_AT, false, null, 100, emptyList(), bundleId, "1.0.0", "${Fixture.FILES_BASE_URL}/manifest.json", manifestSha256, 7)
        return IndexRelease.fromJson(release.toJson())
    }
}
