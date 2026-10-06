package com.hotcodepush.core

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The patch cases of the protocol's pack-entries fixture through the downloader and the native bspatch of the device's ABI:
 * each case's patch entry in a delta pack against the running bundle, the held files in the file store or the embedded
 * bundle, and every file of the manifest on the files host. Applied writes the file without fetching it, fallback fetches
 * it, ignored does neither.
 */
@RunWith(AndroidJUnit4::class)
class PackEntriesOnDeviceTest {
    private val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("pack-entries.json").bufferedReader().use { JSONObject(it.readText()) }
    private val contents = fixture.getJSONArray("files").map { it.getString("sha256") to Base64.decode(it.getString("contentBase64"), Base64.DEFAULT) }.toMap()

    @Test
    fun shouldMatchEveryPatchCaseWhenTheBaseIsInTheEmbeddedBundle() {
        assertEveryPatchCase(isBaseEmbedded = true)
    }

    @Test
    fun shouldMatchEveryPatchCaseWhenTheBaseIsInTheFileStore() {
        assertEveryPatchCase(isBaseEmbedded = false)
    }

    private fun assertEveryPatchCase(isBaseEmbedded: Boolean) {
        val cases = fixture.getJSONArray("patchCases").map { it }
        assertTrue(cases.size >= 6)
        for (case in cases) {
            val name = case.getString("name")
            val harness = DownloaderHarness()
            for (sha256 in case.getJSONArray("heldSha256s").toStringList()) {
                val content = contents.getValue(sha256)
                if (isBaseEmbedded) harness.embedded.files[sha256] = content else harness.files.writeFile(content, sha256)
            }
            val manifestFiles = case.getJSONArray("manifestFiles").map(BundleManifest.File::fromJson)
            for (file in manifestFiles) harness.http.stub(DownloaderHarness.fileUrl(file.sha256), body = contents.getValue(file.sha256))
            val patchEntry = case.getJSONObject("patchEntry")
            val toSha256 = patchEntry.getString("toSha256")
            val patch = PackEntry.Patch(patchEntry.getString("fromSha256"), toSha256, Base64.decode(patchEntry.getString("bodyBase64"), Base64.DEFAULT))
            harness.download(harness.publish(DownloaderHarness.manifest(manifestFiles), deltas = mapOf("b1" to PackWriter.pack(listOf(patch)))), "b1")
            val isFetched = harness.http.requests.any { it.first == DownloaderHarness.fileUrl(toSha256) }
            when (case.getString("outcome")) {
                "applied" -> assertTrue(name, harness.files.hasFile(toSha256) && !isFetched)
                "fallback" -> assertTrue(name, harness.files.hasFile(toSha256) && isFetched)
                "ignored" -> assertFalse(name, harness.files.hasFile(toSha256) || isFetched)
                else -> fail("$name: the outcome ${case.getString("outcome")} is unknown")
            }
            val manifest = checkNotNull(harness.files.readManifest(DownloaderHarness.BUNDLE_ID))
            assertTrue(name, harness.files.isComplete(manifest, harness.embedded))
            harness.root.deleteRecursively()
        }
    }
}
