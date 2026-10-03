package com.hotcodepush.protocol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Timestamps travel as ISO 8601 in UTC and live as epoch milliseconds; `java.text`, since `java.time` sits above the SDK's API floor. */
object Iso8601 {
    private const val SECONDS_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
    private const val MILLISECONDS_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    private val timestamp = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z""")

    fun format(epochMillis: Long): String = formatter(MILLISECONDS_PATTERN).format(Date(epochMillis))

    /** The fraction is read to the millisecond whatever its length, which a `SimpleDateFormat` pattern cannot do. */
    fun parse(value: String): Long {
        val match = timestamp.matchEntire(value) ?: throw IllegalArgumentException("Not an ISO 8601 timestamp in UTC: $value")
        val (seconds, fraction) = match.destructured
        val parsed = formatter(SECONDS_PATTERN).parse(seconds) ?: throw IllegalArgumentException("Not an ISO 8601 timestamp in UTC: $value")
        return parsed.time + fraction.padEnd(3, '0').take(3).toLong()
    }

    fun parseOrNull(value: String?): Long? = value?.let { runCatching { parse(it) }.getOrNull() }

    /** A new formatter per call, since `SimpleDateFormat` is not thread-safe. */
    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
}
