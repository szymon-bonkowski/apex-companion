package io.github.szymonbonkowski.apexcompanion.network

import io.github.szymonbonkowski.apexcompanion.maps.ApexRotationParser
import io.github.szymonbonkowski.apexcompanion.maps.ProviderResult
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Deliberately opt-in local host test. Never compiled into the mobile application or run by public CI. */
class ProviderLiveContractTest {
    @Test fun authorizedLocalV2Contract() {
        assumeTrue("Local provider smoke test is opt-in", System.getenv("APEX_STATUS_LIVE_TEST") == "1")
        val path = Path.of(requireNotNull(System.getenv("APEX_STATUS_KEY_FILE")) { "Supply only an external private key-file path" })
        val project = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        require(!path.toAbsolutePath().normalize().startsWith(project)) { "Key file must be outside the project" }
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) { "A regular private key file is required" }
        val permissions = Files.getPosixFilePermissions(path)
        require(permissions.none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) { "Key file must have owner-only permissions" }
        val key = Files.readString(path).trim()
        require(key.isNotEmpty() && key.length <= 4096 && key.all { it.code in 33..126 }) { "Invalid private credential file" }
        val client = rotationHttpClient(OkHttp.create()).config {
            HttpResponseValidator {
                validateResponse { response -> println("Provider HTTP status: ${response.status.value}") }
            }
        }
        try {
            val result = runBlocking { ApexStatusRotationProvider(client, RotationAccess.AuthorizedDeveloper { key }).fetch() }
            require(result is ProviderResult.Success) { "Provider request failed: ${(result as? ProviderResult.Failure)?.error}" }
            require(!result.body.contains(key)) { "Provider echoed credential; response discarded" }
            val directory = Path.of(System.getProperty("user.home"), ".local", "state", "apex-companion", "provider-validation")
            Files.createDirectories(directory)
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val temporary = Files.createTempFile(directory, "response-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(temporary, result.body)
            Files.move(temporary, directory.resolve("maprotation-v2.private.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val cache = ApexRotationParser.parse(result.body, Clock.System.now().epochSeconds)
            assertTrue(cache.playlists.isNotEmpty(), "No validated playlists in authenticated response")
            cache.playlists.forEach { mode ->
                require(mode.id.matches(Regex("[A-Za-z0-9_]{1,80}"))) { "Unexpected playlist identifier" }
                println("Validated playlist ${mode.id}: ${mode.segments.size} source segments")
            }
            println("Authenticated v2 response validated; private evidence stored outside repository. No public distribution grant inferred.")
        } finally { client.close() }
    }
}
