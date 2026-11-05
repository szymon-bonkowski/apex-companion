package io.github.szymonbonkowski.apexcompanion

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.szymonbonkowski.apexcompanion.appearance.*
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.platform.AndroidRotationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native AtomicFile evidence. Only isolated cache files and synthetic settings are used. */
@RunWith(AndroidJUnit4::class)
class AppearancePersistenceInstrumentedTest {
    private fun directory(name: String) = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "appearance-native-$name"
    ).apply { deleteRecursively(); mkdirs() }

    @Test fun allDesignsAndDashboardSurviveNativeStoreReopenWithoutChangingGameFiles() = runBlocking {
        val directory = directory("restart")
        try {
            val gameFiles = listOf("ranked-history-v1.json", "matches-v1.json", "rotation-cache-v1.json")
                .map { File(directory, it).apply { writeText("synthetic unrelated record: $name") } }
            val before = gameFiles.associate { it.name to it.readBytes() }
            val file = File(directory, "appearance.json")
            for (style in DesignStyle.entries) {
                val repository = AppearanceRepository(AndroidRotationStore(file))
                repository.initialize()
                val preferences = AppearancePreferences(
                    style = style, mode = AppearanceMode.LIGHT, accentHex = "7157b8",
                    density = LayoutDensity.SPACIOUS, corners = CornerScale.LARGE,
                    cards = CardTreatment.OUTLINED, motion = MotionPreference.OFF,
                    textScalePercent = 120, reducedTransparency = true
                )
                assertTrue(repository.savePreferences(preferences))
                val defaults = defaultDashboard(style)
                val layout = defaults.move(defaults.items.first().module, 1).copy(
                    hideUnavailable = true,
                    items = defaults.move(defaults.items.first().module, 1).items.mapIndexed { index, item ->
                        item.copy(visible = index != 0, size = ModuleSize.COMPACT)
                    }
                )
                assertTrue(repository.saveDashboard(layout))
                val reopened = AppearanceRepository(AndroidRotationStore(file))
                reopened.initialize()
                assertEquals(preferences.validated(), reopened.state.value.record.preferences)
                assertEquals(layout, reopened.state.value.record.dashboard)
                assertFalse(reopened.state.value.readOnly)
                for (gameFile in gameFiles) assertArrayEquals(before.getValue(gameFile.name), gameFile.readBytes())
                assertFalse(file.readText().contains("synthetic unrelated record"))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedNativeWriteRetainsPreviousSettingsAndPublishedState() = runBlocking {
        val directory = directory("interrupted")
        val file = File(directory, "appearance.json")
        var interrupt = false
        val native = AndroidRotationStore(file)
        val store = object : RotationStore {
            override suspend fun read() = native.read()
            override suspend fun write(value: String) {
                if (interrupt) {
                    AtomicFile(file).startWrite().use { it.write("{interrupted-cosmetic-write".toByteArray()) }
                    throw IllegalStateException("Synthetic power interruption")
                }
                native.write(value)
            }
        }
        try {
            val repository = AppearanceRepository(store)
            repository.initialize()
            val previous = AppearancePreferences(style = DesignStyle.EDITORIAL, mode = AppearanceMode.LIGHT)
            assertTrue(repository.savePreferences(previous))
            val committed = native.read()
            interrupt = true
            assertFalse(repository.savePreferences(previous.copy(style = DesignStyle.TACTICAL)))
            assertEquals(previous, repository.state.value.record.preferences)
            assertEquals(committed, native.read())
            val reopened = AppearanceRepository(AndroidRotationStore(file))
            reopened.initialize()
            assertEquals(previous, reopened.state.value.record.preferences)
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptAndInvalidUtf8SettingsStayReadOnlyUntilExplicitRecovery() = runBlocking {
        val directory = directory("corrupt")
        val file = File(directory, "appearance.json")
        try {
            val future = AppearanceCodec.encode(AppearanceRecord()).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
            for (bytes in listOf("{unsupported cosmetic data".toByteArray(), byteArrayOf(0xC3.toByte(), 0x28),
                future.toByteArray(), " ".repeat(APPEARANCE_MAX_BYTES + 1).toByteArray())) {
                file.writeBytes(bytes)
                val repository = AppearanceRepository(AndroidRotationStore(file))
                repository.initialize()
                assertTrue(repository.state.value.readOnly)
                assertNotNull(repository.state.value.error)
                assertFalse(repository.savePreferences(AppearancePreferences(style = DesignStyle.LIQUID)))
                assertArrayEquals(bytes, file.readBytes())
                assertFalse(repository.resetAll())
                assertArrayEquals(bytes, file.readBytes())
                assertTrue(repository.resetAll(confirmCorruptReset = true))
                val reopened = AppearanceRepository(AndroidRotationStore(file))
                reopened.initialize()
                assertFalse(reopened.state.value.readOnly)
                assertEquals(AppearanceRecord(), reopened.state.value.record)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun nativePresetsRestoreCosmeticsAndLayoutAfterRestartThenResetWithoutDeletingOtherPresets() = runBlocking {
        val directory = directory("presets")
        val file = File(directory, "appearance.json")
        try {
            val repository = AppearanceRepository(AndroidRotationStore(file))
            repository.initialize()
            val custom = AppearancePreferences(style = DesignStyle.LIQUID, accentHex = "7357DB", density = LayoutDensity.SPACIOUS)
            val layout = defaultDashboard(DesignStyle.LIQUID).copy(hideUnavailable = true)
            assertTrue(repository.savePreferences(custom))
            assertTrue(repository.saveDashboard(layout))
            assertTrue(repository.createPreset("Synthetic purple"))
            val originalId = repository.state.value.record.presets.single().id
            assertTrue(repository.duplicatePreset(originalId))
            val copyId = repository.state.value.record.presets.single { it.id != originalId }.id
            assertTrue(repository.renamePreset(copyId, "Synthetic copy"))
            assertTrue(repository.resetToStyleDefaults(DesignStyle.TACTICAL))
            // Appearance defaults are independent from the separately configured dashboard.
            assertEquals(layout, repository.state.value.record.dashboard)
            val reopened = AppearanceRepository(AndroidRotationStore(file))
            reopened.initialize()
            assertEquals(2, reopened.state.value.record.presets.size)
            assertTrue(reopened.restorePreset(originalId))
            assertEquals(custom, reopened.state.value.record.preferences)
            assertEquals(layout, reopened.state.value.record.dashboard)
            assertTrue(reopened.deletePreset(originalId))
            val final = AppearanceRepository(AndroidRotationStore(file))
            final.initialize()
            assertEquals("Synthetic copy", final.state.value.record.presets.single().name)
            assertEquals(copyId, final.state.value.record.presets.single().id)
            assertEquals(custom, final.state.value.record.preferences)
        } finally { directory.deleteRecursively() }
    }
}
