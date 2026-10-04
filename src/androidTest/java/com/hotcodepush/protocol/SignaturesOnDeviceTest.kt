package com.hotcodepush.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The signature fixtures against a device's own `java.security` providers, which the JVM tests cannot stand in for:
 * every Android version imports the SPKI key a resource file carries and verifies the suite case for case.
 */
@RunWith(AndroidJUnit4::class)
class SignaturesOnDeviceTest {
    private val manifests: List<JSONObject> = InstrumentationRegistry.getInstrumentation().context.assets.open("signatures.json").bufferedReader().use { reader ->
        JSONObject(reader.readText()).getJSONArray("manifests").map { it }
    }

    @Test
    fun shouldMatchEverySignatureFixture() {
        assertTrue(manifests.size > 5)
        var verified = 0
        for (case in manifests) {
            val isValid = verify(case) == null
            assertEquals(case.getString("name"), case.getBoolean("isValid"), isValid)
            if (isValid) verified++
        }
        assertTrue(verified >= 3)
    }

    @Test
    fun shouldRefuseAKeyUnderTheMinimumSizeAndAnEd25519Signature() {
        val refusals = manifests.associate { it.getString("name") to verify(it) }
        assertEquals(SignatureRefusal.UNUSABLE_KEY, refusals["should refuse a signature by a key under 2048 bits"])
        assertEquals(SignatureRefusal.UNKNOWN_SCHEME, refusals["should refuse a scheme outside the allow-list, ed25519"])
    }

    private fun verify(case: JSONObject): SignatureRefusal? {
        val envelope = case.getJSONObject("envelope")
        val signature = envelope.getNullableObject("signature")?.let(Signature::fromJson)
        val publicKeys = case.getJSONObject("devicePublicKeys").getJSONArray("android").map(DevicePublicKey::fromJson)
        return SignatureVerifier.verifyManifestSignature(envelope.getString("manifest"), signature, publicKeys)
    }
}
