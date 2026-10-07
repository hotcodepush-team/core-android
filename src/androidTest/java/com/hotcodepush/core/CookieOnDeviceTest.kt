package com.hotcodepush.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.CookieManager
import java.net.CookiePolicy

/**
 * The OkHttp the core brings into an app, on the device: React Native's networking client keeps cookies in
 * okhttp-urlconnection's jar, which calls okhttp3.internal.Util, gone in OkHttp 5, so a host that sets a cookie must still work.
 */
@RunWith(AndroidJUnit4::class)
class CookieOnDeviceTest {
    @Test
    fun shouldSendTheCookieAHostSetThroughReactNativesCookieJar() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Set-Cookie", "session=s1; Path=/").setBody("first"))
            server.enqueue(MockResponse().setBody("second"))
            val client = OkHttpClientAdapter(OkHttpClient.Builder().cookieJar(JavaNetCookieJar(CookieManager(null, CookiePolicy.ACCEPT_ALL))).build())
            // An address, not localhost: Android 6's CookieManager matches a cookie of a host without a dot to `.local` alone.
            val url = "http://127.0.0.1:${server.port}/index.json"
            assertEquals("first", String(client.get(url, emptyMap()).body))
            assertEquals("second", String(client.get(url, emptyMap()).body))
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals("session=s1", server.takeRequest().getHeader("Cookie"))
        }
    }
}
