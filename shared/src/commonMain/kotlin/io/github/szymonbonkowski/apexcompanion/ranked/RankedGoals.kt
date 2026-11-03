package io.github.szymonbonkowski.apexcompanion.ranked

import io.github.szymonbonkowski.apexcompanion.maps.DataState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/** Calendar dates, not 24-hour durations: DST and the user's local midnight are respected. */
fun calculateGoal(goal: RankGoal, currentRp: Long?, today: LocalDate): GoalProgress {
    val days = goal.deadline?.let { today.daysUntil(it) }
    if (currentRp == null) return GoalProgress(null, null, 0f, days, if (goal.completed) GoalStatus.COMPLETED else GoalStatus.NO_CURRENT)
    require(currentRp in 0..MAX_RP)
    val remaining = (goal.targetRp - currentRp).coerceAtLeast(0)
    val span = goal.targetRp - goal.baselineRp
    val fraction = if (remaining == 0L) 1f else if (span <= 0L) 0f else
        ((currentRp - goal.baselineRp).toDouble() / span).coerceIn(0.0, 1.0).toFloat()
    val status = when {
        goal.completed -> GoalStatus.COMPLETED
        remaining == 0L -> GoalStatus.ACHIEVED
        days != null && days <= 0 -> GoalStatus.EXPIRED
        else -> GoalStatus.ACTIVE
    }
    val daily = when {
        goal.completed -> null
        remaining == 0L -> 0L
        days != null && days > 0 -> (remaining + days - 1) / days
        else -> null
    }
    return GoalProgress(remaining, daily, fraction, days, status)
}

fun rankedView(state: RankedRepositoryState, nowSeconds: Long, today: LocalDate): RankedView {
    val snapshot = state.profile
    val manual = state.useManual || snapshot == null
    val rp = if (manual) state.manualRp else snapshot?.rp
    val dataState = when {
        manual && rp != null -> DataState.MANUAL
        snapshot == null || rp == null -> DataState.UNAVAILABLE
        state.error != null || nowSeconds < snapshot.observedAtSeconds || nowSeconds >= snapshot.observedAtSeconds + PROFILE_FRESH_SECONDS -> DataState.STALE
        else -> DataState.LIVE
    }
    return RankedView(snapshot, rp, dataState, if (manual) "User entered" else "Apex Legends Status",
        if (manual) state.manualUpdatedAtSeconds else snapshot?.observedAtSeconds,
        state.goal, state.goal?.let { calculateGoal(it, rp, today) }, state.error)
}
