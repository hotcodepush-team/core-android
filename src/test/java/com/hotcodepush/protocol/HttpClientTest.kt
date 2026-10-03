package com.hotcodepush.protocol

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class HttpClientTest {
    private val url = "https://files.test/apps/a/bundles/b2/pack"
    private val file = File(Files.createTempDirectory("hotcodepush-tests").toFile(), "b2.pack")

    @Test
    fun shouldResumeAPartialDownloadWithARangeRequestAndAppendTheRest() {
        file.writeText("abc")
        val ranges = mutableListOf<String?>()
        val client = client { request -> ranges += request.header("Range"); response(request, 206, "defgh".toByteArray().toResponseBody()) }
        assertNull(download(client, 8))
        assertEquals(listOf("bytes=3-"), ranges)
        assertEquals("abcdefgh", file.readText())
    }

    @Test
    fun shouldStartOverWhenARangeRequestIsAnsweredWithTheWholeBody() {
        file.writeText("abc")
        val client = client { request -> response(request, 200, "abcdefgh".toByteArray().toResponseBody()) }
        assertNull(download(client, 8))
        assertEquals("abcdefgh", file.readText())
    }

    @Test
    fun shouldDeleteThePartialFileOnAnotherStatus() {
        file.writeText("abc")
        val client = client { request -> response(request, 416, ByteArray(0).toResponseBody()) }
        assertEquals(FailedReason.DOWNLOAD_FAILED, (download(client, 8) as? DownloadFailure)?.reason)
        assertFalse(file.exists())
    }

    @Test
    fun shouldKeepWhatArrivedWhenTheConnectionDropsAndResumeFromIt() {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val ranges = mutableListOf<String?>()
        val client = client { request ->
            val range = request.header("Range")
            ranges += range
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            if (start == null) response(request, 200, droppedBody(body.copyOf(150_000))) else response(request, 206, body.copyOfRange(start, body.size).toResponseBody())
        }
        assertTrue(download(client, body.size.toLong()) is IOException)
        assertArrayEquals(body.copyOf(150_000), file.readBytes())
        assertNull(download(client, body.size.toLong()))
        assertEquals(listOf(null, "bytes=150000-"), ranges)
        assertArrayEquals(body, file.readBytes())
    }

    @Test
    fun shouldStopADownloadPastItsMaximumAndDeleteTheFile() {
        val client = client { request -> response(request, 200, ByteArray(200_000).toResponseBody()) }
        assertEquals(FailedReason.DOWNLOAD_FAILED, (download(client, 100_000) as? DownloadFailure)?.reason)
        assertFalse(file.exists())
    }

    private fun download(client: HttpClient, maximumBytes: Long): Exception? = runBlocking {
        try {
            client.download(url, file, maximumBytes) { _, _ -> }
            null
        } catch (exception: Exception) {
            exception
        }
    }

    /** The real OkHttp client with the network replaced by the reply. */
    private fun client(reply: (Request) -> Response) = OkHttpClientAdapter(OkHttpClient.Builder().addInterceptor { chain -> reply(chain.request()) }.build())

    private fun response(request: Request, code: Int, body: ResponseBody) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("").body(body).build()

    /** A body whose connection drops once what it delivered has been read. */
    private fun droppedBody(delivered: ByteArray): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = object : Source {
            private var isDelivered = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (isDelivered) throw IOException("Connection lost")
                isDelivered = true
                sink.write(delivered)
                return delivered.size.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {}
        }.buffer()
    }
}
