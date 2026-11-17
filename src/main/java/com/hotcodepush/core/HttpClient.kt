package com.hotcodepush.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** The four HTTP shapes the core needs: a small GET, a small POST, a GET whose answer is not read and a large download that resumes. */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse

    suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse

    /** Sends one GET that follows no redirect, reads no body and writes no file. A network failure throws, as for `get`. */
    suspend fun send(url: String)

    /**
     * Downloads to the file, resuming from its current size with a `Range` request; what arrived stays for the next attempt.
     * Past `maximumBytes` it stops, deletes the file and refuses the body as wrong content with `DownloadFailure.ContentMismatched`.
     * On a status other than 200 or 206, a redirect included, it deletes
     * the file and throws `HttpStatusException`. A failed write of the file throws `DownloadFailure.DownloadFailed`, never an `IOException`.
     */
    @Suppress("LongParameterList") // Public API: three arguments and the trailing progress callback, as core-ios's download(_:to:maximumBytes:progress:).
    suspend fun download(url: String, file: File, maximumBytes: Long, progress: (Long, Long) -> Unit)
}

/** A status the fetch does not take: for a download any but 200 or 206, a redirect among them, since a download follows none. */
class HttpStatusException(val status: Int) : Exception("HTTP $status")

class OkHttpClientAdapter(private val client: OkHttpClient = sharedClient) : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = fetchResponse(request(url, headers).build())

    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse = fetchResponse(request(url, headers).post(body.toRequestBody()).build())

    // OkHttp reads a body only when asked, so closing the response at once reads none.
    override suspend fun send(url: String) {
        downloadClient.newCall(Request.Builder().url(url).build()).execute().close()
    }

    private fun fetchResponse(request: Request): HttpResponse = client.newCall(request).execute().use { response ->
        val responseHeaders = response.headers.names().associateWith { response.headers[it] ?: "" }
        HttpResponse(response.code, responseHeaders, response.body?.bytes() ?: ByteArray(0))
    }

    private fun request(url: String, headers: Map<String, String>) = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }

    override suspend fun download(url: String, file: File, maximumBytes: Long, progress: (Long, Long) -> Unit) {
        val existing = if (file.isFile) file.length() else 0L
        val request = Request.Builder().url(url).apply { if (existing > 0) header("Range", "bytes=$existing-") }.build()
        downloadClient.newCall(request).execute().use { response ->
            verifyDownloadStatus(response, file)
            file.parentFile?.mkdirs()
            val append = response.code == 206 && existing > 0
            var written = if (append) existing else 0L
            StorageOutputStream(writeToStorage { FileOutputStream(file, append) }).use { output ->
                (response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maximumBytes) {
                            file.delete()
                            throw DownloadFailure.ContentMismatched("${url.substringAfterLast('/')} is larger than its $maximumBytes bytes")
                        }
                        output.write(buffer, 0, read)
                        progress(written, maximumBytes)
                    }
                }
            }
        }
    }

    private fun verifyDownloadStatus(response: Response, file: File) {
        if (response.code == 200 || response.code == 206) return
        file.delete()
        throw HttpStatusException(response.code)
    }

    // A download and a send follow no redirect, so they stay on the URL the core pinned.
    private val downloadClient: OkHttpClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    companion object {
        // OkHttp asks for one client per process, since its pools and threads outlive every activity.
        private val sharedClient: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    }
}

private class StorageOutputStream(private val output: OutputStream) : OutputStream() {
    override fun write(byte: Int) = writeToStorage { output.write(byte) }

    override fun write(buffer: ByteArray, offset: Int, length: Int) = writeToStorage { output.write(buffer, offset, length) }

    override fun close() = writeToStorage { output.close() }
}

// The network and the device's own storage both fail with an IOException, so a failed write, close or open is told apart here.
private inline fun <T> writeToStorage(write: () -> T): T = try {
    write()
} catch (exception: IOException) {
    throw DownloadFailure.DownloadFailed("The pack could not be written to the device's storage: ${exception.message}")
}
