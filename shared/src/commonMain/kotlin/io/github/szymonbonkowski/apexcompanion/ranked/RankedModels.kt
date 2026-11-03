package io.github.szymonbonkowski.apexcompanion.ranked

import io.github.szymonbonkowski.apexcompanion.maps.DataState
import kotlinx.datetime.LocalDate

const val PROFILE_FRESH_SECONDS = 300L
const val PREDATOR_FRESH_SECONDS = 1800L
const val MAX_RP = 1_000_000_000L

enum class ApexPlatform(val code: String, val label: String) {
    PC("PC", "PC"), PLAYSTATION("PS4", "PlayStation"), XBOX("X1", "Xbox"), SWITCH("SWITCH", "Nintendo Switch")
}
data class PlayerIdentity(val uid: String, val platform: ApexPlatform, val name: String) {
    init { require(uid.length in 1..64 && uid.all(Char::isDigit)); require(name.length <= 100) }
}
data class ProfileLookup(val value: String, val platform: ApexPlatform, val byUid: Boolean) {
    init { require(value.isNotBlank() && value.length <= 100); if (byUid) require(value.length <= 64 && value.all(Char::isDigit)) }
}
data class RankedSnapshot(
    val identity: PlayerIdentity, val rp: Long?, val rankName: String?, val division: Int?,
    val observedAtSeconds: Long, val season: String? = null, val split: String? = null,
    val ladderPosition: Long? = null, val level: Int? = null,
) { init { require(rp == null || rp in 0..MAX_RP); require(division == null || division in 1..4) } }
data class PredatorThreshold(val platform: ApexPlatform, val rp: Long, val observedAtSeconds: Long, val masterPopulation: Long? = null) {
    init { require(rp in 0..MAX_RP); require(masterPopulation == null || masterPopulation >= 0) }
}
enum class RankedError { ACCESS_NOT_CONFIGURED, FORBIDDEN, NOT_FOUND, PRIVATE, UNSUPPORTED_PLATFORM, GONE, RATE_LIMITED, TIMEOUT, NETWORK, SERVER, SCHEMA, STORAGE }
sealed interface RankedResult<out T> {
    data class Success<T>(val value: T): RankedResult<T>
    data class Failure(val error: RankedError, val retryAfterSeconds: Long? = null): RankedResult<Nothing>
}
interface RankedProvider {
    suspend fun profile(lookup: ProfileLookup): RankedResult<RankedSnapshot>
    suspend fun predator(): RankedResult<List<PredatorThreshold>>
}
object UnconfiguredRankedProvider: RankedProvider {
    override suspend fun profile(lookup: ProfileLookup) = RankedResult.Failure(RankedError.ACCESS_NOT_CONFIGURED)
    override suspend fun predator() = RankedResult.Failure(RankedError.ACCESS_NOT_CONFIGURED)
}
data class RankGoal(val targetRp: Long, val baselineRp: Long, val deadline: LocalDate? = null, val completed: Boolean = false) {
    init { require(targetRp in 0..MAX_RP && baselineRp in 0..MAX_RP) }
}
enum class GoalStatus { ACTIVE, ACHIEVED, COMPLETED, EXPIRED, NO_CURRENT }
data class GoalProgress(val remainingRp: Long?, val rpPerDay: Long?, val fraction: Float, val daysRemaining: Int?, val status: GoalStatus)
data class RankedView(val snapshot: RankedSnapshot?, val currentRp: Long?, val state: DataState, val source: String, val observedAtSeconds: Long?, val goal: RankGoal?, val progress: GoalProgress?, val error: RankedError?)
