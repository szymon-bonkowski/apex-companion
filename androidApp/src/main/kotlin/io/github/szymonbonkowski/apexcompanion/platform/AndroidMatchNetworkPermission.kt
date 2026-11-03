package io.github.szymonbonkowski.apexcompanion.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/** Android17 LAN permission is requested only when the user explicitly pairs a companion. */
class AndroidMatchNetworkPermission(private val activity: ComponentActivity): DefaultLifecycleObserver {
    private var pending: CancellableContinuation<Boolean>? = null
    private var busy = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val continuation = pending; pending = null; busy = false
        if (continuation?.isActive == true) continuation.resume(granted)
    }
    init { current = WeakReference(this); activity.lifecycle.addObserver(this) }
    suspend fun request(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (granted(activity)) return@withContext true
        check(!busy) { "Local network permission request already open" }
        busy = true
        suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { if (pending === continuation) pending = null }
            try { launcher.launch(PERMISSION) }
            catch (failure: Exception) { pending = null; busy = false; continuation.resumeWith(Result.failure(failure)) }
        }
    }
    override fun onDestroy(owner: LifecycleOwner) {
        pending?.cancel(); pending = null; busy = false
        if (current?.get() === this) current = null
        launcher.unregister(); activity.lifecycle.removeObserver(this)
    }
    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private var current: WeakReference<AndroidMatchNetworkPermission>? = null
        fun granted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        suspend fun authorize(context: Context): Boolean = if (granted(context)) true else current?.get()?.request() ?: false
    }
}
