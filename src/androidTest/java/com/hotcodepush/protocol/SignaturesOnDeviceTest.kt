package com.hotcodepush.protocol

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The signature fixtures against the device's own `java.security` providers, which the JVM tests cannot stand in for:
 * a device with Ed25519 verifies the suite case for case within the allow-list, one without refuses every Ed25519
 * signature, never accepts one, and the Expo bridge's RSA scheme verifies on neither.
 */
@RunWith(AndroidJUnit4::class)
class SignaturesOnDeviceTest {
    private val manifests: List<JSONObject> = InstrumentationRegistry.getInstrumentation().context.assets.open("signatures.json").bufferedReader().use { reader ->
        JSONObject(reader.readText()).getJSONArray("manifests").let { cases -> List(cases.length()) { cases.getJSONObject(it) } }
    }

    @Test
    fun shouldMatchEverySignatureFixtureWithinTheAllowListWhereTheDeviceVerifiesTheScheme() {
        assertTrue(manifests.size > 5)
        for (case in manifests) {
            val refusal = verify(case)
            if (refusal == SignatureRefusal.SCHEME_UNAVAILABLE) continue
            assertEquals(case.getString("name"), case.getBoolean("isValid") && resolveScheme(case) == SigningScheme.ED25519.wire, refusal == null)
        }
    }

    @Test
    fun shouldRefuseRsaOnEveryDeviceAndVerifyEd25519WhereThePlatformHasIt() {
        val refusalsOfValidCases = manifests.filter { it.getBoolean("isValid") }.associate { resolveScheme(it) to verify(it) }
        assertEquals(SignatureRefusal.UNKNOWN_SCHEME, refusalsOfValidCases["rsa-v1_5-sha256"])
        assertEquals(if (SignatureVerifier.isEd25519Available) null else SignatureRefusal.SCHEME_UNAVAILABLE, refusalsOfValidCases[SigningScheme.ED25519.wire])
    }

    /** Android 13 brought Ed25519 to the platform's providers; before Android 10 they were fixed at the release and had none; between the two a system update may have added it. */
    @Test
    fun shouldHaveEd25519FromAndroid13AndNoneBeforeAndroid10() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) assertTrue(SignatureVerifier.isEd25519Available)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) assertFalse(SignatureVerifier.isEd25519Available)
    }

    private fun verify(case: JSONObject): SignatureRefusal? {
        val envelope = case.getJSONObject("envelope")
        val signature = envelope.getNullableObject("signature")?.let(Signature::fromJson)
        return SignatureVerifier.verifyManifestSignature(envelope.getString("manifest"), signature, case.getJSONArray("publicKeys").toStringList())
    }

    private fun resolveScheme(case: JSONObject): String? = case.getJSONObject("envelope").getNullableObject("signature")?.getString("value")?.substringBefore(':')
}
