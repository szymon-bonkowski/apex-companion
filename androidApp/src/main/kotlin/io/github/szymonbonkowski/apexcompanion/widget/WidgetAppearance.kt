package io.github.szymonbonkowski.apexcompanion.widget

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.szymonbonkowski.apexcompanion.appearance.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal val WIDGET_STYLE_KEY = stringPreferencesKey("appearance_style")
internal val WIDGET_ACCENT_KEY = stringPreferencesKey("appearance_accent")
internal val WIDGET_DENSITY_KEY = stringPreferencesKey("appearance_density")
internal val WIDGET_SURFACE_KEY = stringPreferencesKey("appearance_surface")

internal fun widgetInstanceAppearance(preferences: Preferences) = WidgetInstanceAppearance(
    style = preferences[WIDGET_STYLE_KEY]?.let { runCatching { WidgetDesign.valueOf(it) }.getOrNull() },
    accentHex = preferences[WIDGET_ACCENT_KEY]?.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) },
    density = preferences[WIDGET_DENSITY_KEY]?.let { runCatching { WidgetDensity.valueOf(it) }.getOrNull() },
    surface = preferences[WIDGET_SURFACE_KEY]?.let { runCatching { WidgetSurface.valueOf(it) }.getOrNull() } ?: WidgetSurface.FOLLOW,
)

/** App calls this after cosmetic commits. Local reloads do not request provider work. */
object WidgetAppearancePublisher {
    private const val FILE_NAME = "widget-appearance-v1.json"
    private val fileMutex = Mutex()
    suspend fun publish(context: Context, wire: String) = withContext(Dispatchers.IO) {
        val normalized = WidgetAppearanceCodec.encode(WidgetAppearanceCodec.decodeStrict(wire))
        fileMutex.withLock {
            // A canceled composition may finish native IO. Keep its atomic commit serialized
            // with the next publication/read.
            withContext(NonCancellable) {
                val file = AtomicFile(File(context.filesDir, FILE_NAME))
                if (runCatching { file.openRead().use(::readBounded) }.getOrNull() == normalized) false
                else {
                    val stream = file.startWrite()
                    try { stream.write(normalized.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                    catch (error: Exception) { file.failWrite(stream); throw error }
                    true
                }
            }
        }
        // Also reload an identical projection: the previous process/effect may have
        // committed bytes but stopped before requesting a native reload. No provider work.
        withContext(NonCancellable) {
            MapRotationWidget().updateAll(context)
            updateAllRankedWidgets(context)
        }
    }
    internal suspend fun read(context: Context): WidgetAppearance = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val file = AtomicFile(File(context.filesDir, FILE_NAME))
            val text = runCatching { file.openRead().use(::readBounded) }.getOrNull()
            WidgetAppearanceCodec.decode(text)
        }
    }
    private fun readBounded(stream: java.io.InputStream): String? {
        val bytes = ByteArray(2049)
        var size = 0
        while (size < bytes.size) {
            val count = stream.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        return if (size > 2048) null else bytes.copyOf(size).decodeToString(throwOnInvalidSequence = true)
    }
}

internal fun widgetLookIntent(context: Context, widgetId: Int) = Intent(context, WidgetAppearanceActivity::class.java)
    .setData(android.net.Uri.parse("apexcompanion://widget-appearance/$widgetId"))
    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

internal fun WidgetAppearance.fontFamily(): FontFamily = when (style) {
    WidgetDesign.EDITORIAL -> FontFamily.Serif
    WidgetDesign.TACTICAL -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}
internal fun WidgetAppearance.padding(roomy: Boolean): Int = when (density) {
    WidgetDensity.COMPACT -> if (roomy) 12 else 8
    WidgetDensity.COMFORTABLE -> if (roomy) 16 else 10
    WidgetDensity.SPACIOUS -> if (roomy) 20 else 10 // smallest families preserve critical data
}
internal fun WidgetAppearance.corner(): Int = when (style) {
    WidgetDesign.CARBON -> 16
    WidgetDesign.LIQUID -> 28
    WidgetDesign.EDITORIAL -> 2
    WidgetDesign.TACTICAL -> 4
}
internal fun widgetContainer(appearance: WidgetAppearance, roomy: Boolean) = GlanceModifier.fillMaxSize()
    .background(Color(widgetPalette(appearance).background))
    .cornerRadius(appearance.corner().dp).padding(appearance.padding(roomy).dp)

@Composable
internal fun WidgetIdentityRule(appearance: WidgetAppearance, roomy: Boolean, title: String) {
    val palette = widgetPalette(appearance)
    when (appearance.style) {
        WidgetDesign.CARBON -> if (roomy) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text("APEX COMPANION", style = TextStyle(color = ColorProvider(Color(palette.accent)), fontSize = 10.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(4.dp))
        }
        WidgetDesign.LIQUID -> if (roomy) {
            // Native families have a fixed height: reserve space for source/time and actions.
            Text(title, modifier = GlanceModifier.background(Color(palette.surface)).cornerRadius(10.dp).padding(horizontal = 10.dp, vertical = 1.dp),
                style = TextStyle(color = ColorProvider(Color(palette.accent)), fontSize = 10.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(1.dp))
        }
        WidgetDesign.EDITORIAL -> {
            Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(Color(palette.accent)))
            Spacer(GlanceModifier.height(if (roomy) 8.dp else 4.dp))
        }
        WidgetDesign.TACTICAL -> if (roomy) {
            Text("// $title", style = TextStyle(color = ColorProvider(Color(palette.accent)), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), maxLines = 1)
            Spacer(GlanceModifier.height(3.dp))
        }
    }
}

/** Explicit local activity intent; distinct data prevents PendingIntent destination collisions. */
internal fun widgetOpenIntent(context: Context, destination: String) =
    Intent(context, io.github.szymonbonkowski.apexcompanion.MainActivity::class.java)
        .setData(android.net.Uri.parse("apexcompanion://$destination"))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
