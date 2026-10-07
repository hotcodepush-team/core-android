package com.hotcodepush.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionRangeTest {
    @Test
    fun shouldSatisfyARangeWhoseComponentIsPastTheLargestInt() {
        assertEquals(true, VersionRange.isVersionInRange(listOf(2, 4, 1), ">=1.0.0 <2147483648"))
        assertEquals(false, VersionRange.isVersionInRange(listOf(2147483648, 0, 0), "<2147483648"))
    }

    @Test
    fun shouldSatisfyAnIntervalWhoseComponentIsTheLargestTheTypeScriptEvaluatorHolds() {
        assertEquals(true, VersionRange.isVersionInRange(listOf(VersionRange.COMPONENT_MAXIMUM, 1), VersionRange.COMPONENT_MAXIMUM.toString()))
    }

    @Test
    fun shouldNotParseAVersionOrARangeWhenAComponentIsPastTheLargestTheTypeScriptEvaluatorHolds() {
        val past = (VersionRange.COMPONENT_MAXIMUM + 1).toString()
        assertNull(VersionRange.parseVersion("1.$past"))
        assertNull(VersionRange.parseVersion("1.99999999999999999999"))
        assertNull(VersionRange.isVersionInRange(listOf(1), "<$past"))
        assertNull(VersionRange.isVersionInRange(listOf(1), "$past.x"))
    }
}
