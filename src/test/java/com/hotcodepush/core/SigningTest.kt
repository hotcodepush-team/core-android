package com.hotcodepush.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Base64

class SigningTest {
    private val fixture = SignatureFixtures()
    private val manifest = Fixture.release(1, "b2", "<html>v2</html>".toByteArray()).envelope.manifest

    @Test
    fun shouldRefuseASignatureWhoseBase64IsNotCanonical() {
        val signed = fixture.sign(manifest, "rsa-4096-a")
        val unpadded = Signature(signed.keyId, signed.value.trimEnd('='))
        assertEquals(SignatureRefusal.UNKNOWN_SCHEME, SignatureVerifier.verifyManifestSignature(manifest, unpadded, listOf(fixture.devicePublicKey("rsa-4096-a"))))
    }

    @Test
    fun shouldRefuseAKeyTheSystemCannotImportAsAConfigurationError() {
        val signed = fixture.sign(manifest, "rsa-4096-a")
        val notAKey = DevicePublicKey(Base64.getEncoder().encodeToString(ByteArray(32)), signed.keyId)
        assertEquals(SignatureRefusal.UNIMPORTABLE_KEY, SignatureVerifier.verifyManifestSignature(manifest, signed, listOf(notAKey)))
    }
}
