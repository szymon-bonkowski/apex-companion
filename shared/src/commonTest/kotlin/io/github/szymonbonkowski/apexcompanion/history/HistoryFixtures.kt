package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.*

internal const val TEST_NOW = 1_700_000_000L
internal val testIdentity = HistoryIdentity("700001", ApexPlatform.PC)
internal class HistoryMemoryStore(initial: String? = null): RotationStore {
    var value = initial
    var failWrites = false
    var writes = 0
    override suspend fun read() = value
    override suspend fun write(value: String) {
        check(!failWrites) { "Injected commit failure" }
        writes++; this.value = value
    }
}
internal class HistoryClock(var now: Long = TEST_NOW): RotationClock {
    override fun nowSeconds() = now
}
internal class HistoryFixtureProvider(private val clock: HistoryClock): RankedProvider {
    var calls = 0
    var result: RankedResult<RankedSnapshot>? = null
    override suspend fun profile(lookup: ProfileLookup): RankedResult<RankedSnapshot> {
        calls++
        return result ?: RankedResult.Success(testSnapshot(clock.now))
    }
    override suspend fun predator(): RankedResult<List<PredatorThreshold>> = RankedResult.Failure(RankedError.ACCESS_NOT_CONFIGURED)
}
internal fun testSnapshot(at: Long = TEST_NOW, rp: Long? = 13000, identity: HistoryIdentity = testIdentity,
    season: String? = "S-test", split: String? = null) = RankedSnapshot(
    PlayerIdentity(identity.uid, identity.platform, "Synthetic player"), rp, "Synthetic rank", null, at, season, split)
internal fun testObservation(id: String = "observation-1", rp: Long = 13000, at: Long = TEST_NOW - 60,
    identity: HistoryIdentity? = testIdentity, source: ObservationSource = ObservationSource.PROVIDER,
    season: String? = "S-test", split: String? = "split-test", context: String? = "upstream:S-test:split-test",
    verification: ContextVerification = ContextVerification.UPSTREAM,
    quality: ObservationQuality = ObservationQuality.VALID) = RpObservation(id, identity, source, rp, at,
    season, split, context, verification, quality)
internal fun testRecord(observations: List<RpObservation> = listOf(testObservation()),
    sessions: List<RankedSession> = emptyList()) = HistoryRecord(settings = PortableRankedSettings(
        goal = RankGoal(16000, 13000), manualRp = 12000, manualUpdatedAtSeconds = TEST_NOW - 100),
    observations = observations, sessions = sessions)
internal fun testBackup(record: HistoryRecord = testRecord(), id: String = "fixture-batch") =
    HistoryCodec.export(HistoryBackup(id, TEST_NOW, record))
