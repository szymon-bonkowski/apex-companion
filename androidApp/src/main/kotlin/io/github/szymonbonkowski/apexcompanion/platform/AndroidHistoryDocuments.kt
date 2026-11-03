package io.github.szymonbonkowski.apexcompanion.platform

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.szymonbonkowski.apexcompanion.history.HISTORY_MAX_BYTES
import io.github.szymonbonkowski.apexcompanion.history.HistoryDocuments
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Activity-owned SAF launchers. No storage permission, persisted URI grant or automatic sharing. */
class AndroidHistoryDocuments(private val activity: ComponentActivity) : HistoryDocuments, DefaultLifecycleObserver {
    private var pending: CancellableContinuation<Uri?>? = null
    private var busy = false
    private val createDocument = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { finish(it) }
    private val openDocument = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { finish(it) }

    init { activity.lifecycle.addObserver(this) }

    private fun finish(uri: Uri?) {
        val continuation = pending
        pending = null
        // Keep busy until the original external picker returns, even if its caller was canceled.
        busy = false
        if (continuation?.isActive == true) continuation.resume(uri)
    }

    private suspend fun pick(exporting: Boolean): Uri? = withContext(Dispatchers.Main.immediate) {
        check(!busy) { "A document picker is already open" }
        busy = true
        suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { if (pending === continuation) pending = null }
            try {
                if (exporting) createDocument.launch("apex-companion-backup.json")
                else openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            } catch (failure: Exception) {
                pending = null
                busy = false
                continuation.resumeWith(Result.failure(failure))
            }
        }
    }

    override suspend fun exportJson(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= HISTORY_MAX_BYTES) { "Backup exceeds size limit" }
        val uri = pick(exporting = true) ?: return false
        return withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("Document could not be opened")
            true
        }
    }

    override suspend fun importJson(): String? {
        val uri = pick(exporting = false) ?: return null
        return withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, HISTORY_MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= HISTORY_MAX_BYTES) { "Backup exceeds size limit" }
                }
                output.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } ?: error("Document could not be opened")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        pending?.cancel()
        pending = null
        busy = false
        createDocument.unregister()
        openDocument.unregister()
        activity.lifecycle.removeObserver(this)
    }
}
