package com.hotcodepush.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionRangeTest {
    @Test
    fun shouldSatisfyARangeWhoseComponentIsPastTheLargestInt() {
        assertEquals(true, VersionRange.isVersionInRange(listOf(2, 4, 1), ">=1.0.0 <2147483648"))
        assertEquals(false, VersionRange.isVersionInRange(listOf(2147483648, 0, 0), "<2147483648"))
    }
}
