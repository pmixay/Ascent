package com.pmixay.ascent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ModelRetryAfterTest {
    @Test fun retryAfterAcceptsDeltaSecondsOrHttpDateAndBoundsUntrustedValues() {
        val now = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
        assertEquals(2_500L * 1000, retryAfterMillis("2500", now))
        assertEquals(90_000L, retryAfterMillis("Sat, 24 Oct 2026 12:01:30 GMT", now))
        assertEquals(0L, retryAfterMillis("Sat, 24 Oct 2026 11:59:00 GMT", now))
        assertEquals(86_400_000L, retryAfterMillis(Long.MAX_VALUE.toString(), now))
        assertEquals(0L, retryAfterMillis("-1", now))
        assertEquals(0L, retryAfterMillis("not a date", now))
        assertEquals(0L, retryAfterMillis(null, now))
    }
}
