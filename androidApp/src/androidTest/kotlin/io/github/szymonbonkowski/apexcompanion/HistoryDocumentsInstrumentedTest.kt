package io.github.szymonbonkowski.apexcompanion

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import io.github.szymonbonkowski.apexcompanion.history.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Exercises the actual Android system document picker and strict UTF-8 round trip. */
@RunWith(AndroidJUnit4::class)
class HistoryDocumentsInstrumentedTest {
    @Test fun systemPickerExportsThenReadsPortableJsonAndCancellationDoesNotChangeData() = runBlocking {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val filename = "apex-v03-picker-${System.currentTimeMillis()}.json"
        val value = HistoryCodec.export(HistoryBackup("synthetic-picker-backup", 1_793_650_000L,
            HistoryRecord(settings = PortableRankedSettings(manualRp = 13000, manualUpdatedAtSeconds = 1_793_650_000L))))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val saved = CompletableDeferred<Boolean>()
                scenario.onActivity { activity -> scope.launch {
                    try { saved.complete(activity.historyDocuments.exportJson(value)) }
                    catch (failure: Throwable) { saved.completeExceptionally(failure) }
                } }
                assertTrue("System save picker unavailable", device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui"))), 10_000))
                selectDownloads(device)
                val filenameField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                assertNotNull("Save picker filename input missing", filenameField)
                filenameField!!.click()
                filenameField.text = filename
                val save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)save"))), 5_000)
                assertNotNull("Save document action missing", save)
                save!!.click()
                assertTrue(withTimeout(10_000) { saved.await() })

                val imported = CompletableDeferred<String?>()
                scenario.onActivity { activity -> scope.launch {
                    try { imported.complete(activity.historyDocuments.importJson()) }
                    catch (failure: Throwable) { imported.completeExceptionally(failure) }
                } }
                assertTrue(device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui"))), 10_000))
                val recent = device.wait(Until.findObject(By.text(filename)), 3_000)
                if (recent == null) selectDownloads(device)
                var item = device.wait(Until.findObject(By.text(filename)), 5_000)
                if (item == null) {
                    val search = device.findObject(By.desc(Pattern.compile("(?i)search")))
                    search?.click()
                    device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000)?.text = filename
                    device.pressEnter()
                    item = device.wait(Until.findObject(By.text(filename)), 5_000)
                }
                assertNotNull("Exported backup not visible in system picker", item)
                item!!.click()
                assertEquals(value, withTimeout(10_000) { imported.await() })
                assertEquals(13000L, HistoryCodec.import(value, 1_793_650_000L).record.settings?.manualRp)

                val canceled = CompletableDeferred<String?>()
                scenario.onActivity { activity -> scope.launch {
                    try { canceled.complete(activity.historyDocuments.importJson()) }
                    catch (failure: Throwable) { canceled.completeExceptionally(failure) }
                } }
                assertTrue(device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui"))), 10_000))
                device.pressBack()
                assertNull(withTimeout(10_000) { canceled.await() })
            }
        } finally {
            scope.cancel()
            // Only the unique synthetic file this test created in the selected Downloads root.
            device.executeShellCommand("rm -f /sdcard/Download/$filename")
        }
    }

    private fun selectDownloads(device: UiDevice) {
        device.waitForIdle()
        val drawer = device.findObject(By.desc(Pattern.compile("(?i)(show roots|open navigation drawer)")))
        if (drawer != null) {
            drawer.click()
            device.waitForIdle()
            repeat(3) {
                try {
                    device.wait(Until.hasObject(By.text("Downloads")), 3_000)
                    // The drawer row follows the current-directory toolbar title in traversal.
                    device.findObjects(By.text("Downloads")).lastOrNull()?.click()
                    device.waitForIdle()
                    return
                } catch (_: StaleObjectException) { device.waitForIdle() }
            }
        }
    }
}
