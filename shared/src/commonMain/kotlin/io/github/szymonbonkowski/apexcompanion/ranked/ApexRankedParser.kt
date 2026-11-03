package io.github.szymonbonkowski.apexcompanion.ranked

import kotlinx.serialization.json.*

/** Normalizes the observed /bridge version 5 contract. Raw tracker/history/art fields are discarded. */
object ApexRankedParser {
    fun profile(body: String, lookup: ProfileLookup, observedAtSeconds: Long): RankedSnapshot {
        require(body.length <= 524_288 && observedAtSeconds >= 0)
        val global = Json.parseToJsonElement(body).jsonObject["global"] as? JsonObject
            ?: throw IllegalArgumentException("Missing player identity")
        val uid = global.text("uid", 64)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: throw IllegalArgumentException("Invalid player identity")
        val platform = global.text("platform", 16)?.let { code -> ApexPlatform.entries.firstOrNull { it.code == code } }
            ?: throw IllegalArgumentException("Invalid player platform")
        require(platform == lookup.platform && (!lookup.byUid || lookup.value == uid)) { "Mismatched player identity" }
        val rank = global["rank"] as? JsonObject
        return RankedSnapshot(
            identity = PlayerIdentity(uid, platform, global.text("name", 100).orEmpty()),
            rp = rank?.integer("rankScore")?.takeIf { it in 0..MAX_RP },
            rankName = rank?.text("rankName", 60)?.takeIf { it.isNotBlank() },
            division = rank?.integer("rankDiv")?.takeIf { it in 1..4 }?.toInt(),
            observedAtSeconds = observedAtSeconds,
            season = rank?.text("rankedSeason", 100)?.takeIf { it.isNotBlank() },
            // Do not infer split identity from dates or from an undocumented season string.
            split = null,
            ladderPosition = rank?.integer("ladderPosPlatform")?.takeIf { it > 0 },
            level = global.integer("level")?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
        )
    }

    /** Only BR RP cutoff; legacy AP/arena data and upstream account IDs never enter local models. */
    fun predator(body: String, nowSeconds: Long): List<PredatorThreshold> {
        require(body.length <= 524_288 && nowSeconds >= 0)
        val platforms = Json.parseToJsonElement(body).jsonObject["RP"] as? JsonObject
            ?: throw IllegalArgumentException("Missing Predator RP data")
        val values = ApexPlatform.entries.mapNotNull { platform ->
            val record = platforms[platform.code] as? JsonObject ?: return@mapNotNull null
            val rp = record.integer("val")?.takeIf { it in 0..MAX_RP } ?: return@mapNotNull null
            val timestamp = record.integer("updateTimestamp")?.takeIf { it > 0 && it <= nowSeconds }
                ?: return@mapNotNull null
            val population = record.integer("totalMastersAndPreds")?.takeIf { it >= 0 }
            PredatorThreshold(platform, rp, timestamp, population)
        }
        require(values.isNotEmpty()) { "No valid Predator RP data" }
        return values
    }
}

private fun JsonObject.text(key: String, limit: Int): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.length <= limit && it.none(Char::isISOControl) }
private fun JsonObject.integer(key: String): Long? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it.isString }?.longOrNull
