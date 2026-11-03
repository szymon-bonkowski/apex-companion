package io.github.szymonbonkowski.apexcompanion.platform

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.szymonbonkowski.apexcompanion.matches.MatchScreenshotDraft
import io.github.szymonbonkowski.apexcompanion.matches.MatchScreenshotPicker
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.resume

/** Real system Photo Picker, no gallery permission/grant persistence/upload. No OCR is claimed. */
class AndroidMatchScreenshots(private val activity: ComponentActivity): MatchScreenshotPicker, DefaultLifecycleObserver {
    private var pending: CancellableContinuation<Uri?>? = null
    private var busy = false
    private val picker = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val continuation = pending; pending = null; busy = false
        if (continuation?.isActive == true) continuation.resume(uri)
    }
    init { activity.lifecycle.addObserver(this) }
    override suspend fun pick(): MatchScreenshotDraft? {
        val uri = withContext(Dispatchers.Main.immediate) {
            check(!busy) { "Photo picker already open" }
            busy = true
            suspendCancellableCoroutine<Uri?> { continuation ->
                pending = continuation
                continuation.invokeOnCancellation { if (pending === continuation) pending = null }
                try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (failure: Exception) { pending = null; busy = false; continuation.resumeWith(Result.failure(failure)) }
            }
        } ?: return null
        return withContext(Dispatchers.IO) {
            val bytes = activity.contentResolver.openInputStream(uri)?.use { readBounded(it, 16_777_216) }
                ?: error("Selected photo could not be read")
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(options.outWidth in 1..16384 && options.outHeight in 1..16384) { "Unsupported image dimensions" }
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while ((options.outWidth.toLong() / options.inSampleSize) * (options.outHeight.toLong() / options.inSampleSize) > 4_194_304) options.inSampleSize *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Unsupported image")
                bitmap.recycle()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                MatchScreenshotDraft("screenshot:$digest", "manual-screenshot-v1", warning =
                    "On-device OCR is not available in this build. Enter only fields you can read from this screenshot, then explicitly confirm. No image is retained or uploaded.")
            } finally { bytes.fill(0) }
        }
    }
    override fun onDestroy(owner: LifecycleOwner) {
        pending?.cancel(); pending = null; busy = false
        picker.unregister(); activity.lifecycle.removeObserver(this)
    }
}
