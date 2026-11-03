package io.github.szymonbonkowski.apexcompanion

import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.util.AtomicFile
import io.github.szymonbonkowski.apexcompanion.platform.*
import io.github.szymonbonkowski.apexcompanion.matches.*
import io.github.szymonbonkowski.apexcompanion.history.HistoryIdentity
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.ranked.ApexPlatform
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MatchPlatformInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now get() = System.currentTimeMillis() / 1000
    @Test fun matchAtomicFileRestartsRecoversInterruptedWriteAndPreservesRankedBytes() = runBlocking {
        val directory = File(context.cacheDir, "match-store-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val ranked = File(directory, "ranked-history-v1.json").apply { writeText("unrelated-v0.3-bytes") }
            val file = File(directory, "matches-v1.json")
            val repository = MatchRepository(AndroidMatchStore(file), RotationClock { now })
            repository.initialize()
            assertTrue(repository.saveManual(MatchRecord("native-manual", timestampSeconds = now - 10, kills = 3)))
            val original = file.readBytes()
            val interrupted = AtomicFile(file).startWrite()
            interrupted.write("{\"schemaVersion\":99".encodeToByteArray()); interrupted.fd.sync(); interrupted.close()
            val restarted = MatchRepository(AndroidMatchStore(file), RotationClock { now })
            restarted.initialize()
            assertEquals(1, restarted.state.value.records.size)
            assertArrayEquals(original, file.readBytes())
            assertEquals("unrelated-v0.3-bytes", ranked.readText())
            assertTrue(restarted.deleteHistory())
            val afterDelete = MatchRepository(AndroidMatchStore(file), RotationClock { now }); afterDelete.initialize()
            assertTrue(afterDelete.state.value.records.isEmpty())
            assertEquals("unrelated-v0.3-bytes", ranked.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun pairingIsKeystoreEncryptedPersistsAndCanBeRevokedLocally() = runBlocking {
        val namespace = "test-match-pairing-${UUID.randomUUID()}"
        val file = File(context.noBackupFilesDir, "$namespace.enc")
        val platform = AndroidMatchSyncPlatform(context, namespace)
        try {
            val pairing = MatchPairing("https://192.168.1.20:45873", "a".repeat(64), "b".repeat(64), "synthetic-pc", null)
            platform.savePairing(pairing)
            val bytes = file.readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(pairing.token))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(pairing.endpoint))
            val restored = AndroidMatchSyncPlatform(context, namespace).loadPairing()!!
            assertEquals(pairing.endpoint, restored.endpoint); assertEquals(pairing.certificatePin, restored.certificatePin)
            assertEquals(pairing.token, restored.token); assertEquals(pairing.companionId, restored.companionId)
            platform.savePairing(null); assertNull(platform.loadPairing()); assertFalse(file.exists())
        } finally {
            platform.savePairing(null)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("apex-companion-$namespace") }
        }
    }
    @Test fun unreadableFutureAndOversizedJournalFailClosedWithoutOverwrite() = runBlocking {
        val directory = File(context.cacheDir, "match-corrupt-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            listOf("{\"schemaVersion\":99}", "not-json").forEachIndexed { index, corrupt ->
                val file = File(directory, "corrupt-$index.json").apply { writeText(corrupt) }
                val repo = MatchRepository(AndroidMatchStore(file), RotationClock { now }); repo.initialize()
                assertTrue(repo.state.value.readOnly)
                assertFalse(repo.saveManual(MatchRecord("cannot-save", timestampSeconds = now)))
                assertEquals(corrupt, file.readText())
            }
            val file = File(directory, "oversized.json").apply { writeText("x".repeat(1025)) }
            val repo = MatchRepository(AndroidMatchStore(file, 1024), RotationClock { now }); repo.initialize()
            assertTrue(repo.state.value.readOnly); assertEquals(1025L, file.length())
        } finally { directory.deleteRecursively() }
    }
    @Test fun actualSystemPhotoPickerCancellationReturnsNoDraft() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var picker: AndroidMatchScreenshots
            activity.onActivity { picker = it.matchScreenshots }
            val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            try {
                val result = scope.async { picker.pick() }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val appeared = device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile("com\\.(google\\.android\\.photopicker|google\\.android\\.providers\\.media\\.module|android\\.providers\\.media\\.module)"))), 5000)
                assertTrue("Actual Android system Photo Picker must open", appeared)
                device.pressBack()
                assertNull(withTimeout(5000) { result.await() })
            } finally { scope.cancel() }
        }
    }
    @Test fun actualPhotoPickerSelectedSyntheticImageProducesDigestOnlyDraft() = runBlocking {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val album = "ApexCompanionSyntheticV04"
        val bitmap = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply { drawColor(android.graphics.Color.DKGRAY); drawText("SYNTHETIC MATCH FIXTURE", 20f, 70f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f }) }
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val bytes = output.toByteArray()
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "synthetic-v04-${UUID.randomUUID()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
            })!!
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                lateinit var picker: AndroidMatchScreenshots
                activity.onActivity { picker = it.matchScreenshots }
                val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
                try {
                    val result = scope.async { picker.pick() }
                    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    val tabs = java.util.regex.Pattern.compile("Albums|Collections")
                    val albums = device.wait(Until.findObject(By.text(tabs)), 2500) ?: device.wait(Until.findObject(By.desc(tabs)), 2500)
                    if (albums == null) {
                        val folder = File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
                        device.dumpWindowHierarchy(File(folder, "v04-photo-selector-failure.xml"))
                    }
                    assertNotNull("Actual system picker album tab", albums); albums!!.click()
                    // Android17 groups local albums behind the explicit device collection.
                    device.wait(Until.findObject(By.text("From this device")), 2000)?.click()
                    val ownAlbum = device.wait(Until.findObject(By.text(album)), 2500) ?: device.wait(Until.findObject(By.descContains(album)), 2500)
                    if (ownAlbum == null) {
                        val folder = File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
                        device.dumpWindowHierarchy(File(folder, "v04-photo-album-failure.xml"))
                    }
                    assertNotNull("Only the test-owned synthetic album may be selected", ownAlbum); ownAlbum!!.click()
                    val image = device.wait(Until.findObject(By.desc(java.util.regex.Pattern.compile("(?i)Photo taken.*"))), 5000)
                    if (image == null) {
                        val folder = File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
                        device.dumpWindowHierarchy(File(folder, "v04-photo-thumbnail-failure.xml"))
                    }
                    assertNotNull("Synthetic image thumbnail", image); image!!.click()
                    device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)Add.*|Done|Select"))), 1500)?.click()
                    val draft = try { withTimeout(8000) { result.await() }!! }
                    catch (failure: Exception) {
                        val folder = File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
                        device.dumpWindowHierarchy(File(folder, "v04-photo-completion-failure.xml"))
                        throw failure
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    assertEquals("screenshot:$digest", draft.evidenceEventId)
                    assertTrue(draft.fields.isEmpty()); assertTrue(draft.confidence.isEmpty())
                    assertTrue(draft.warning!!.contains("OCR is not available"))
                } finally { scope.cancel(); deviceBackFromPicker() }
            }
        } finally { context.contentResolver.delete(uri, null, null); bytes.fill(0) }
    }
    private fun deviceBackFromPicker() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(4) { if (device.currentPackageName?.contains("photopicker") == true) { device.pressBack(); android.os.SystemClock.sleep(100) } }
    }
    /** Provision synthetic Linux mock credentials out of band; never a real PC pairing or provider key. */
    @Test fun linuxMockPinnedHttpsRejectsUnauthorizedAndImportsReplaySafely() = runBlocking {
        val fixture = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat /data/local/tmp/apex-v04-match-sync-fixture.json").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
                    val bytes = readBounded(input, 16_384)
                    bytes.decodeToString()
                }
            }
        assumeTrue("Opt-in Linux TLS mock fixture is not provisioned", fixture.trim().startsWith("{"))
        val config = Json.parseToJsonElement(fixture).jsonObject
        fun text(key: String) = config.getValue(key).jsonPrimitive.content
        val identity = config.getValue("identity").takeIf { it != JsonNull }?.jsonObject?.let {
            HistoryIdentity(it.getValue("uid").jsonPrimitive.content, ApexPlatform.valueOf(it.getValue("platform").jsonPrimitive.content))
        }
        val pairing = MatchPairing(text("endpoint"), text("certificatePin"), text("token"), text("companionId"), identity)
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.ACCESS_LOCAL_NETWORK").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        val namespace = "test-match-tls-${UUID.randomUUID()}"
        val platform = AndroidMatchSyncPlatform(context, namespace)
        val directory = File(context.cacheDir, "match-tls-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val badToken = MatchPairing(pairing.endpoint, pairing.certificatePin, "0".repeat(64), pairing.companionId, identity)
            var rejected = false
            try { platform.request(badToken, "/v1/matches?after=0") } catch (_: Exception) { rejected = true }
            assertTrue("Unauthorized device must reject", rejected)
            val badPin = MatchPairing(pairing.endpoint, "0".repeat(64), pairing.token, pairing.companionId, identity)
            rejected = false
            try { platform.request(badPin, "/v1/matches?after=0") } catch (_: Exception) { rejected = true }
            assertTrue("Wrong TLS pin must reject", rejected)
            val repository = MatchRepository(AndroidMatchStore(File(directory, "matches.json")), RotationClock { now })
            val controller = MatchSyncController(repository, platform, RotationClock { now })
            MatchCodec.decodeBatch(platform.request(pairing, "/v1/matches?after=0"), now)
            assertTrue("Actual pinned TLS pairing", controller.pair(pairing))
            assertTrue(controller.synchronize())
            val count = repository.state.value.records.size
            assertTrue("Mock must have a synthetic accepted event", count > 0)
            assertTrue(controller.synchronize()); assertEquals(count, repository.state.value.records.size)
            val restarted = MatchRepository(AndroidMatchStore(File(directory, "matches.json")), RotationClock { now }); restarted.initialize()
            assertEquals(count, restarted.state.value.records.size)
            assertTrue(restarted.cursor(pairing.companionId) > 0)
            assertTrue(restarted.deleteHistory())
            val deleted = MatchRepository(AndroidMatchStore(File(directory, "matches.json")), RotationClock { now }); deleted.initialize()
            val reconnect = MatchSyncController(deleted, platform, RotationClock { now })
            assertTrue(reconnect.synchronize()); assertTrue(deleted.state.value.records.isEmpty())
        } finally {
            platform.savePairing(null)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("apex-companion-$namespace") }
            directory.deleteRecursively()
        }
    }
}
