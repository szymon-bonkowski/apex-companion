package io.github.szymonbonkowski.apexcompanion.network

import io.github.szymonbonkowski.apexcompanion.ranked.*
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Private opt-in input supplies the player's chosen name/platform; no real identity is a fixture. */
class RankedLiveContractTest {
    @Test fun authorizedLocalV5ProfileAndPredatorContract() {
        assumeTrue("Local ranked contract test is opt-in", System.getenv("APEX_STATUS_RANKED_LIVE_TEST") == "1")
        val project = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        fun privateInput(variable: String): String {
            val path = Path.of(requireNotNull(System.getenv(variable)) { "External private input file is required" }).toAbsolutePath().normalize()
            require(!path.startsWith(project) && Files.isRegularFile(path) && !Files.isSymbolicLink(path))
            require(Files.getPosixFilePermissions(path).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
            require(Files.size(path) in 1..4096)
            return Files.readString(path).trim()
        }
        val key = privateInput("APEX_STATUS_KEY_FILE")
        require(key.all { it.code in 33..126 })
        val input = Json.parseToJsonElement(privateInput("APEX_STATUS_PLAYER_FILE")).jsonObject
        val lookup = ProfileLookup(input.getValue("name").jsonPrimitive.content,
            ApexPlatform.entries.first { it.code == input.getValue("platform").jsonPrimitive.content }, false)
        val client = rotationHttpClient(OkHttp.create())
        try {
            runBlocking {
                val provider = ApexStatusRankedProvider(client, RotationAccess.AuthorizedDeveloper { key })
                val named = assertIs<RankedResult.Success<RankedSnapshot>>(provider.profile(lookup), "Name contract failed").value
                assertTrue(named.identity.uid.isNotEmpty(), "Stable UID was not resolved")
                delay(1000)
                val direct = assertIs<RankedResult.Success<RankedSnapshot>>(provider.profile(
                    ProfileLookup(named.identity.uid, named.identity.platform, true)), "UID contract failed").value
                assertEquals(named.identity.uid == direct.identity.uid, true, "UID identity mismatch")
                assertTrue(direct.rp == null || direct.rp in 0..MAX_RP)
                delay(1000)
                val predator = assertIs<RankedResult.Success<List<PredatorThreshold>>>(provider.predator(), "Predator contract failed").value
                assertTrue(predator.isNotEmpty())
                println("Authorized bridge v5 name-to-UID and direct-UID contracts passed; Predator normalized. No identity, raw response or credential persisted.")
            }
        } finally { client.close() }
    }
}
