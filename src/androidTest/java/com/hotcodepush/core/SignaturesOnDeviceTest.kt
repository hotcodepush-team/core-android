package com.hotcodepush.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

// Each Android version brings its own `java.security` providers, which the JVM tests cannot stand in for.
@RunWith(AndroidJUnit4::class)
class SignaturesOnDeviceTest {
    private val fixture = SignatureFixtures(InstrumentationRegistry.getInstrumentation().context.assets.open("signatures.json").bufferedReader().use { JSONObject(it.readText()) })

    @Test
    fun shouldNameTheRefusalOfEverySignatureFixture() = fixture.assertTheRefusalOfEveryManifest()
}
