package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlin.test.*

class HistoryIntervalTest {
    @Test fun t005SameVerifiedSplitYields150RpOnly() {
        val old = testObservation()
        val new = testObservation("observation-2", 13150, TEST_NOW)
        val delta = rpInterval(old, new)
        assertEquals(150L, delta.deltaRp)
        assertEquals(60L, delta.durationSeconds)
        assertEquals(IntervalQuality.VERIFIED, delta.quality)
        assertEquals(old.id, delta.fromId); assertEquals(new.id, delta.toId)
    }
    @Test fun t006EqualLaterRpIsAValidZeroAndUnknownSplitIsNull() {
        val old = testObservation()
        assertEquals(0L, rpInterval(old, testObservation("observation-2", at = TEST_NOW)).deltaRp)
        val unknown = testObservation("unknown", at = TEST_NOW, split = null, context = null,
            verification = ContextVerification.UNKNOWN, quality = ObservationQuality.UNKNOWN_CONTEXT)
        assertNull(rpInterval(old, unknown).deltaRp)
        assertEquals(IntervalQuality.UNKNOWN_CONTEXT, rpInterval(old, unknown).quality)
    }
    @Test fun legitimateNegativeRpDoesNotBecomeReset() {
        val interval = rpInterval(testObservation(rp = 13150), testObservation("later", 13050, TEST_NOW))
        assertEquals(-100L, interval.deltaRp)
        assertEquals(IntervalQuality.VERIFIED, interval.quality)
    }
    @Test fun t007NewSplitIsBoundaryAndDirectOutOfOrderIsExplicit() {
        val old = testObservation(rp = 13150)
        val new = testObservation("new-split", 12600, TEST_NOW, split = "split-new", context = "upstream:S-test:split-new")
        assertEquals(IntervalQuality.CONTEXT_BOUNDARY, rpInterval(old, new).quality)
        assertNull(rpInterval(old, new).deltaRp)
        assertEquals(IntervalQuality.ORDERING, rpInterval(new, old).quality)
        assertEquals(IntervalQuality.ORDERING, rpInterval(old, old.copy(id = "same-time")).quality)
    }
    @Test fun missingSeasonCannotBecomeConfirmedObservation() {
        assertFailsWith<IllegalArgumentException> { testObservation(season = null) }
        val unknown = testObservation(season = null, split = null, context = null,
            verification = ContextVerification.UNKNOWN, quality = ObservationQuality.UNKNOWN_CONTEXT)
        assertNull(rpInterval(unknown, unknown.copy(id = "later", observedAtSeconds = TEST_NOW)).deltaRp)
    }
    @Test fun differentUidAndPlatformNeverCrossLink() {
        val old = testObservation()
        for (identity in listOf(HistoryIdentity("700002", ApexPlatform.PC), HistoryIdentity("700001", ApexPlatform.XBOX))) {
            val interval = rpInterval(old, testObservation("foreign", 13150, TEST_NOW, identity))
            assertNull(interval.deltaRp); assertEquals(IntervalQuality.IDENTITY_BOUNDARY, interval.quality)
        }
    }
    @Test fun manualAndProviderStaySeparateEvenWithMatchingNumericRp() {
        val manual = testObservation("manual", 13150, TEST_NOW, null, ObservationSource.USER_RECORDED,
            verification = ContextVerification.USER_CONFIRMED)
        assertEquals(IntervalQuality.IDENTITY_BOUNDARY, rpInterval(testObservation(), manual).quality)
        assertNull(rpInterval(testObservation(), manual).deltaRp)
    }
    @Test fun userConfirmationIsNeverUpstreamVerification() {
        val old = testObservation(verification = ContextVerification.USER_CONFIRMED)
        val new = old.copy(id = "later", rp = 13150, observedAtSeconds = TEST_NOW)
        assertEquals(IntervalQuality.USER_CONFIRMED, rpInterval(old, new).quality)
    }
    @Test fun suspiciousDiscontinuityIsQuarantinedWithoutChangingRp() {
        val old = testObservation(rp = 13000)
        val new = testObservation("later", 11000, TEST_NOW)
        assertNull(rpInterval(old, new).deltaRp)
        assertEquals(IntervalQuality.QUARANTINED, rpInterval(old, new).quality)
        assertEquals(11000L, new.rp)
        assertNull(rpInterval(old, new.copy(rp = 15000)).deltaRp)
    }
    @Test fun longGapRetainsHonestWindowAndValidArithmetic() {
        val old = testObservation(at = TEST_NOW - LONG_GAP_SECONDS - 100)
        val interval = rpInterval(old, testObservation("later", 13150, TEST_NOW))
        assertEquals(150L, interval.deltaRp)
        assertEquals(LONG_GAP_SECONDS + 100, interval.durationSeconds)
        assertEquals(IntervalQuality.LONG_GAP, interval.quality)
    }
    @Test fun arbitraryInputOrderProducesSameIntervals() {
        val a = testObservation("a", 13000, TEST_NOW - 120)
        val b = testObservation("b", 13150, TEST_NOW - 60)
        val c = testObservation("c", 13050, TEST_NOW)
        val expected = rpIntervals(listOf(a, b, c))
        for (input in listOf(listOf(c, a, b), listOf(b, c, a), listOf(c, b, a))) assertEquals(expected, rpIntervals(input))
        assertEquals(listOf(150L, -100L), expected.map { it.deltaRp })
    }
    @Test fun unknownMiddlePointIsNeverSkippedToCreateContinuousTrend() {
        val a = testObservation("a", at = TEST_NOW - 120)
        val b = testObservation("b", at = TEST_NOW - 60, split = null, context = null,
            verification = ContextVerification.UNKNOWN, quality = ObservationQuality.UNKNOWN_CONTEXT)
        val c = testObservation("c", 13150, TEST_NOW)
        assertEquals(listOf(null, null), rpIntervals(listOf(a, b, c)).map { it.deltaRp })
    }
    @Test fun invalidRpIdentityTimestampAndUnsupportedModeAreRejected() {
        for (rp in listOf(-1L, MAX_RP + 1, Long.MAX_VALUE)) assertFails { testObservation(rp = rp) }
        for (time in listOf(0L, -1L, MAX_HISTORY_TIME + 1)) assertFails { testObservation(at = time) }
        assertFails { HistoryIdentity("not-a-uid", ApexPlatform.PC) }
        assertFails { testObservation(id = "path/unsafe") }
        assertFails { RankedMode.valueOf("ARENAS") }
    }
    @Test fun fullRangeRpUsesLongArithmeticWithoutOverflow() {
        val low = testObservation(rp = 0)
        val high = testObservation("high", MAX_RP, TEST_NOW)
        assertNull(rpInterval(low, high).deltaRp) // Suspicious, not integer overflow.
        assertEquals(MAX_RP, high.rp - low.rp)
    }
}
