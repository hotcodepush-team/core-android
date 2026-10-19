package com.hotcodepush.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/** What every SDK on Android reads from the app's context through the core: the device facts, the resource file, the store, the embedded bundle and the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContextWiringTest {
    private val application: Context = RuntimeEnvironment.getApplication()
    private val resourceFile = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/resource-files.json").readText()).getJSONArray("cases").getJSONObject(0).getJSONObject("resourceFile")

    @Test
    fun shouldReadTheDeviceFactsFromThePackageManagerAndTheOs() {
        val facts = DeviceFacts.current(contextWithPackage(isDebuggable = false), "1.2.3")

        assertEquals(DeviceFacts("android", "2.4.1", "57", Build.VERSION.RELEASE, "1.2.3", false), facts)
    }

    @Test
    fun shouldReadADebugBuildWhenTheAppIsDebuggable() {
        assertTrue(DeviceFacts.current(contextWithPackage(isDebuggable = true), "1.2.3").isDebugBuild)
    }

    @Test
    fun shouldReadNoConfigurationWhenTheAssetsLackTheResourceFile() {
        assertNull(Configuration.read(application))
    }

    @Test
    fun shouldReadTheConfigurationAtTheAssetPathGiven() {
        val context = contextWithAssets(mapOf("www/hotcodepush.json" to resourceFile.toString().toByteArray()))

        assertEquals(Configuration.decode(resourceFile.toString()), Configuration.read(context, "www/hotcodepush.json"))
        assertNull(Configuration.read(context))
    }

    @Test
    fun shouldThrowTheReadersErrorWhenTheReaderRefusesTheResourceFile() {
        val refused = JSONObject(resourceFile.toString()).put("checkIntervalSeconds", 30)
        val context = contextWithAssets(mapOf("hotcodepush.json" to refused.toString().toByteArray()))

        val refusal = assertThrows(JSONException::class.java) { Configuration.read(context) }

        assertEquals("checkIntervalSeconds is below its floor of 60.0 seconds: 30.0", refusal.message)
        assertEquals(
            "HotCodePush is not configured: the app's hotcodepush.json was refused: checkIntervalSeconds is below its floor of 60.0 seconds: 30.0. Check the project's hotcodepush.json and build the app again.",
            Configuration.resolveRefusedMessage(refusal),
        )
    }

    @Test
    fun shouldKeepTheStoreInTheAppsDefaultPreferences() {
        SharedPreferencesStore(application).putString("hotcodepush.deviceId", "d1")

        assertEquals("d1", application.getSharedPreferences("${application.packageName}_preferences", Context.MODE_PRIVATE).getString("hotcodepush.deviceId", null))
    }

    @Test
    fun shouldKeepTheFilesUnderTheAppsPrivateFiles() {
        assertEquals(File(application.filesDir, "hotcodepush"), FileStore.defaultRootDirectory(application))
    }

    @Test
    fun shouldCopyAnEmbeddedFileFromItsAssetDirectoryByHash() {
        val embedded = AssetsEmbeddedBundle(contextWithAssets(mapOf("public/index.html" to Fixture.embeddedIndexHtml)), Fixture.embeddedManifest(), "public")
        val sha256 = Hashing.sha256Hex(Fixture.embeddedIndexHtml)
        val destination = File(Files.createTempDirectory("hotcodepush-tests").toFile(), sha256)

        assertTrue(embedded.has(sha256))
        embedded.copyFile(sha256, destination)

        assertArrayEquals(Fixture.embeddedIndexHtml, destination.readBytes())
    }

    @Test
    fun shouldHaveNoEmbeddedFileWhenItsAssetIsMissing() {
        val embedded = AssetsEmbeddedBundle(contextWithAssets(mapOf("www/index.html" to Fixture.embeddedIndexHtml)), Fixture.embeddedManifest(), "public")

        assertFalse(embedded.has(Hashing.sha256Hex(Fixture.embeddedIndexHtml)))
    }

    @Test
    fun shouldHaveNoEmbeddedFileWhenTheBuildEmbedsNoManifest() {
        val embedded = AssetsEmbeddedBundle(contextWithAssets(mapOf("public/index.html" to Fixture.embeddedIndexHtml)), null, "public")
        val sha256 = Hashing.sha256Hex(Fixture.embeddedIndexHtml)

        assertFalse(embedded.has(sha256))
        assertThrows(PlainException::class.java) { embedded.copyFile(sha256, File(Files.createTempDirectory("hotcodepush-tests").toFile(), sha256)) }
    }

    @Test
    fun shouldReadTheConnectionAsTheConnectivityManagerReportsIt() {
        for (isMetered in listOf(true, false)) {
            val connectivity = mock(ConnectivityManager::class.java).also { `when`(it.isActiveNetworkMetered).thenReturn(isMetered) }

            assertEquals(isMetered, NetworkMonitor(contextWithConnectivity(connectivity)).isConnectionMetered())
        }
    }

    @Test
    fun shouldReadTheConnectionAsUnmeteredWhenThereIsNoConnectivityManager() {
        assertFalse(NetworkMonitor(contextWithConnectivity(null)).isConnectionMetered())
    }

    @Test
    fun shouldKeepTheCoresStateInTheAppsDefaultPreferencesWhenWiredFromTheContext() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val root = Files.createTempDirectory("hotcodepush-tests").toFile()
        val core = Core(application, Fixture.configuration(), DeviceFacts("android", "2.4.1", "57", "14", "0.0.0", false), InMemoryEmbeddedBundle(), FakeLoader(root), FakeListener(), scope, ManualScheduler())

        val deviceId = core.deviceResult().id

        assertEquals(deviceId, application.getSharedPreferences("${application.packageName}_preferences", Context.MODE_PRIVATE).getString("hotcodepush.deviceId", null))
        scope.cancel()
    }

    /** The app's package as the package manager reports it: version 2.4.1, build 57, debuggable or not. */
    private fun contextWithPackage(isDebuggable: Boolean): Context {
        val packageInfo = PackageInfo().apply {
            versionName = "2.4.1"
            longVersionCode = 57
        }
        val packageManager = mock(PackageManager::class.java).also { `when`(it.getPackageInfo(application.packageName, 0)).thenReturn(packageInfo) }
        val applicationInfo = ApplicationInfo(application.applicationInfo).apply { flags = if (isDebuggable) flags or ApplicationInfo.FLAG_DEBUGGABLE else flags and ApplicationInfo.FLAG_DEBUGGABLE.inv() }
        return object : ContextWrapper(application) {
            override fun getPackageManager(): PackageManager = packageManager

            override fun getApplicationInfo(): ApplicationInfo = applicationInfo
        }
    }

    /** The APK's assets holding exactly these files; any other path is missing, as `AssetManager.open` reports it. */
    private fun contextWithAssets(files: Map<String, ByteArray>): Context {
        val assets = mock(AssetManager::class.java)
        `when`(assets.open(anyString())).thenAnswer { call -> files[call.getArgument(0)]?.inputStream() ?: throw FileNotFoundException(call.getArgument(0)) }
        return object : ContextWrapper(application) {
            override fun getAssets(): AssetManager = assets
        }
    }

    private fun contextWithConnectivity(connectivity: ConnectivityManager?): Context = object : ContextWrapper(application) {
        override fun getSystemService(name: String): Any? = if (name == Context.CONNECTIVITY_SERVICE) connectivity else super.getSystemService(name)
    }
}
