package com.hotcodepush.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServedBundleTest {
    private val projections = Files.createTempDirectory("www").toFile()

    @Test
    fun shouldNameTheBundleLaidOutUnderTheProjectionsDirectory() {
        File(projections, "b2").mkdirs()
        assertEquals("b2", ServedBundle.resolveBundleId(File(projections, "b2").path, projections))
    }

    @Test
    fun shouldBeTheEmbeddedBundleForAnEmptyPathAMissingDirectoryOrAForeignPath() {
        assertNull(ServedBundle.resolveBundleId(null, projections))
        assertNull(ServedBundle.resolveBundleId("", projections))
        assertNull(ServedBundle.resolveBundleId(File(projections, "gone").path, projections))
        assertNull(ServedBundle.resolveBundleId("public", projections))
    }
}

class WebViewGateTest {
    @Test
    fun shouldHoldASwitchUntilTheWebViewLoadedAndRunLaterOnesAtOnce() {
        val gate = WebViewGate()
        val runs = mutableListOf<String>()
        gate.runWhenLoaded { runs += "at start" }
        assertEquals(emptyList<String>(), runs)
        gate.markLoaded()
        assertEquals(listOf("at start"), runs)
        gate.runWhenLoaded { runs += "later" }
        assertEquals(listOf("at start", "later"), runs)
    }

    @Test
    fun shouldKeepOnlyTheLastSwitchRequestedBeforeTheWebViewLoaded() {
        val gate = WebViewGate()
        val runs = mutableListOf<String>()
        gate.runWhenLoaded { runs += "first" }
        gate.runWhenLoaded { runs += "second" }
        gate.markLoaded()
        assertEquals(listOf("second"), runs)
    }

    @Test
    fun shouldDropTheHeldSwitchWhenClosed() {
        val gate = WebViewGate()
        val runs = mutableListOf<String>()
        gate.runWhenLoaded { runs += "at start" }
        gate.close()
        gate.markLoaded()
        assertEquals(emptyList<String>(), runs)
    }
}
