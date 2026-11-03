package io.github.szymonbonkowski.apexcompanion.ranked

import io.github.szymonbonkowski.apexcompanion.maps.DataState
import kotlinx.datetime.*
import kotlin.test.*

class RankedWidgetTest {
    private val today = LocalDate(2026, 11, 2)
    private val zone = TimeZone.of("Europe/Warsaw")
    private val now = today.atStartOfDayIn(zone).epochSeconds + 23 * 3600
    private val profile = RankedSnapshot(PlayerIdentity("123456789", ApexPlatform.PC, "Synthetic private name"), 13460, "Diamond", 3, now)

    @Test fun projectionOmitsIdentityAndPreservesGoalSourceAndMath() {
        val state = RankedRepositoryState(profile = profile, goal = RankGoal(16000, 13000, today.plus(22, DateTimeUnit.DAY)))
        val wire = RankedWidgetCodec.encode(state, now, today, zone)
        assertFalse(wire.contains("123456789")); assertFalse(wire.contains("Synthetic private name"))
        val view = RankedWidgetCodec.decode(wire, now)
        assertEquals(DataState.LIVE, view.state)
        assertEquals(2540L, view.remainingRp)
        assertEquals(116L, view.rpPerDay)
        assertEquals("Diamond 3", view.rankLabel)
        assertEquals(13460L, view.currentRp)
    }
    @Test fun cachedProjectionAdvancesFreshnessAndMidnightWithoutProvider() {
        val state = RankedRepositoryState(profile = profile, goal = RankGoal(16000, 13000, today.plus(2, DateTimeUnit.DAY)))
        val wire = RankedWidgetCodec.encode(state, now, today, zone)
        assertEquals(DataState.STALE, RankedWidgetCodec.decode(wire, now + PROFILE_FRESH_SECONDS).state)
        val midnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
        assertEquals(2540L, RankedWidgetCodec.decode(wire, midnight).rpPerDay)
        val deadline = today.plus(2, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds
        assertEquals(null, RankedWidgetCodec.decode(wire, deadline).rpPerDay)
        assertEquals("EXPIRED", RankedWidgetCodec.decode(wire, deadline).goalStatus)
    }
    @Test fun manualAndDisconnectedDataCannotLeakPreviousIdentity() {
        val manual = RankedRepositoryState(manualRp = 100, useManual = true, manualUpdatedAtSeconds = now, goal = RankGoal(200,100))
        val view = RankedWidgetCodec.decode(RankedWidgetCodec.encode(manual, now, today,zone), now)
        assertEquals(DataState.MANUAL, view.state); assertEquals("Manual RP", view.rankLabel)
        assertEquals(100L, view.currentRp); assertEquals(null, view.platform)
        val disconnected = RankedWidgetCodec.decode(RankedWidgetCodec.encode(RankedRepositoryState(), now,today,zone),now)
        assertEquals(DataState.UNAVAILABLE, disconnected.state); assertNull(disconnected.currentRp)
    }
    @Test fun predatorPlatformAndFreshnessAreIndependentOfManualSource() {
        val state = RankedRepositoryState(manualRp = 1000, useManual = true,
            predator = listOf(PredatorThreshold(ApexPlatform.PC,12000,now,1234), PredatorThreshold(ApexPlatform.XBOX,10000,now)))
        val wire = RankedWidgetCodec.encode(state,now,today,zone)
        val before = RankedWidgetCodec.decode(wire,now)
        assertEquals(listOf(null,null),before.predator.map { it.remainingRp })
        assertTrue(before.predator.all { it.state == DataState.LIVE })
        val after = RankedWidgetCodec.decode(wire,now+PREDATOR_FRESH_SECONDS)
        assertEquals(DataState.MANUAL,after.state); assertTrue(after.predator.all { it.state == DataState.STALE })
    }
    @Test fun predatorGapRequiresTheSameLinkedPlatform() {
        val state = RankedRepositoryState(profile = profile,
            predator = listOf(PredatorThreshold(ApexPlatform.PC,20000,now), PredatorThreshold(ApexPlatform.XBOX,10000,now)))
        val view = RankedWidgetCodec.decode(RankedWidgetCodec.encode(state,now,today,zone),now)
        assertEquals(6540L,view.predator[0].remainingRp)
        assertNull(view.predator[1].remainingRp)
    }
    @Test fun corruptedFutureAndOveragedCalculationsFailSafely() {
        val wire = RankedWidgetCodec.encode(RankedRepositoryState(manualRp=100,useManual=true,goal=RankGoal(200,100,today.plus(2,DateTimeUnit.DAY))),now,today,zone)
        assertEquals(DataState.UNAVAILABLE,RankedWidgetCodec.decode(wire.replace("\"schemaVersion\":1","\"schemaVersion\":9"),now).state)
        assertEquals(DataState.UNAVAILABLE,RankedWidgetCodec.decode(wire,now-1).state)
        assertEquals(DataState.UNAVAILABLE,RankedWidgetCodec.decode("{broken",now).state)
        val aged = RankedWidgetCodec.decode(wire,now+10*86400)
        assertTrue(aged.expiredCalculation); assertNull(aged.rpPerDay)
    }
    @Test fun localMidnightProjectionHandlesDstTransition() {
        val day = LocalDate(2026,10,24)
        val at = day.atStartOfDayIn(zone).epochSeconds+23*3600
        val state = RankedRepositoryState(manualRp=100,useManual=true,goal=RankGoal(300,100,LocalDate(2026,10,26)))
        val wire = RankedWidgetCodec.encode(state,at,day,zone)
        val nextMidnight = LocalDate(2026,10,25).atStartOfDayIn(zone).epochSeconds
        val endMidnight = LocalDate(2026,10,26).atStartOfDayIn(zone).epochSeconds
        assertEquals(25*3600L,endMidnight-nextMidnight)
        assertEquals(200L,RankedWidgetCodec.decode(wire,nextMidnight).rpPerDay)
        assertEquals("EXPIRED",RankedWidgetCodec.decode(wire,endMidnight).goalStatus)
    }
}
