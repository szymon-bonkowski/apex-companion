package io.github.szymonbonkowski.apexcompanion.matches

/** Only normalized predictions cross the platform boundary. Native image bytes never enter backups. */
data class MatchScreenshotDraft(
    val evidenceEventId: String,
    val parserVersion: String,
    val fields: Map<String, String> = emptyMap(),
    val confidence: Map<String, Double> = emptyMap(),
    val warning: String? = null,
)
interface MatchScreenshotPicker {
    suspend fun pick(): MatchScreenshotDraft?
}
