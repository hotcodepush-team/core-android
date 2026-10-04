package com.hotcodepush.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The signature fixtures on a device, which the JVM tests cannot stand in for: the verifier runs on Android's runtime,
 * whatever its version and whatever its own providers hold, and answers the suite case for case within the allow-list.
 */
@RunWith(AndroidJUnit4::class)
class SignaturesOnDeviceTest {
    private val manifests: List<JSONObject> = InstrumentationRegistry.getInstrumentation().context.assets.open("signatures.json").bufferedReader().use { reader ->
        JSONObject(reader.readText()).getJSONArray("manifests").let { cases -> List(cases.length()) { cases.getJSONObject(it) } }
    }

    @Test
    fun shouldMatchEverySignatureFixtureWithinTheAllowList() {
        assertTrue(manifests.size > 5)
        var verified = 0
        for (case in manifests) {
            val isValid = verify(case) == null
            assertEquals(case.getString("name"), case.getBoolean("isValid") && resolveScheme(case) == SigningScheme.ED25519.wire, isValid)
            if (isValid) verified++
        }
        assertTrue(verified >= 2)
    }

    @Test
    fun shouldVerifyEd25519AndRefuseTheExpoBridgesRsaScheme() {
        val refusalsOfValidCases = manifests.filter { it.getBoolean("isValid") }.associate { resolveScheme(it) to verify(it) }
        assertEquals(null, refusalsOfValidCases[SigningScheme.ED25519.wire])
        assertEquals(SignatureRefusal.UNKNOWN_SCHEME, refusalsOfValidCases["rsa-v1_5-sha256"])
    }

    private fun verify(case: JSONObject): SignatureRefusal? {
        val envelope = case.getJSONObject("envelope")
        val signature = envelope.getNullableObject("signature")?.let(Signature::fromJson)
        return SignatureVerifier.verifyManifestSignature(envelope.getString("manifest"), signature, case.getJSONArray("publicKeys").toStringList())
    }

    private fun resolveScheme(case: JSONObject): String? = case.getJSONObject("envelope").getNullableObject("signature")?.getString("value")?.substringBefore(':')
}
