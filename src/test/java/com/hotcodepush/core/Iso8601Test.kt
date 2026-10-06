package com.hotcodepush.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Iso8601Test {
    private val tenOClock = 1_790_676_000_000L

    @Test
    fun shouldParseATimestampWithoutAFraction() {
        assertEquals(tenOClock, Iso8601.parse("2026-09-29T10:00:00Z"))
    }

    @Test
    fun shouldParseAFractionOfAnyLengthToTheMillisecond() {
        assertEquals(tenOClock + 500, Iso8601.parse("2026-09-29T10:00:00.5Z"))
        assertEquals(tenOClock + 123, Iso8601.parse("2026-09-29T10:00:00.123Z"))
        assertEquals(tenOClock + 123, Iso8601.parse("2026-09-29T10:00:00.123456Z"))
    }

    @Test
    fun shouldRefuseWhatIsNotAnIso8601TimestampInUtc() {
        for (value in listOf("2026-09-29T10:00:00+02:00", "2026-09-29T10:00:00", "2026-13-01T00:00:00Z", "2026-09-29T10:00:00ZZ", "yesterday")) {
            assertThrows(value, Exception::class.java) { Iso8601.parse(value) }
        }
    }

    @Test
    fun shouldFormatInUtcWithMilliseconds() {
        assertEquals("2026-09-29T10:00:00.000Z", Iso8601.format(tenOClock))
        assertEquals("2026-09-29T10:00:00.042Z", Iso8601.format(tenOClock + 42))
    }
}
