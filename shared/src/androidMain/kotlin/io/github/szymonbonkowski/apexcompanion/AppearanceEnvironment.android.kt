package io.github.szymonbonkowski.apexcompanion.appearance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun platformAppearanceEnvironment(): AppearanceEnvironment {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    revision++
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("high_text_contrast_enabled"),
            false,
            observer,
        )
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    revision++
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return remember(context, dark, revision) {
        val reduce =
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        val contrast =
            Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
        val power =
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        val accent =
            if (Build.VERSION.SDK_INT >= 31)
                runCatching {
                    val color =
                        context.getColor(
                            if (dark) android.R.color.system_accent1_200
                            else android.R.color.system_accent1_700
                        )
                    (color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
                }
                    .getOrNull()
            else null
        AppearanceEnvironment(
            dark,
            reduce,
            highContrast = contrast,
            lowPower = power,
            dynamicAccentHex = accent,
        )
    }
}
