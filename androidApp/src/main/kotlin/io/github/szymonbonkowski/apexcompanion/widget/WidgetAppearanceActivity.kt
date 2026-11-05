package io.github.szymonbonkowski.apexcompanion.widget

import android.appwidget.AppWidgetManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import io.github.szymonbonkowski.apexcompanion.appearance.*
import kotlinx.coroutines.*

/** Private, local-only native editor for the selected widget. No network or provider access. */
class WidgetAppearanceActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val selectors = mutableListOf<Spinner>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider?.packageName != packageName) { finish(); return }
        scope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetAppearanceActivity).getGlanceIdBy(widgetId)
            val preferences = getAppWidgetState(this@WidgetAppearanceActivity, PreferencesGlanceStateDefinition, glanceId)
            val existing = widgetInstanceAppearance(preferences)
            val app = WidgetAppearancePublisher.read(this@WidgetAppearanceActivity)
            val content = LinearLayout(this@WidgetAppearanceActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(32))
            }
            fun text(value: String, size: Float = 16f) = TextView(this@WidgetAppearanceActivity).apply {
                text = value; textSize = size; setPadding(0, dp(8), 0, dp(8))
            }.also(content::addView)
            text("Widget appearance", 26f)
            text("Changes affect only this widget. Follow app keeps its appearance in sync. Source, freshness and update frequency stay unchanged.")
            val preview = text("DEMO preview", 18f).apply { minHeight = dp(100); setPadding(dp(16), dp(16), dp(16), dp(16)) }
            fun selector(label: String, values: List<String>, selected: Int): Spinner {
                text(label, 14f)
                return Spinner(this@WidgetAppearanceActivity).apply {
                    contentDescription = label
                    minimumHeight = dp(48)
                    adapter = ArrayAdapter(this@WidgetAppearanceActivity, android.R.layout.simple_spinner_dropdown_item, values)
                    val index = selectors.size
                    id = 17001 + index
                    setSelection(savedInstanceState?.getIntArray("appearanceSelections")?.getOrNull(index)?.coerceIn(values.indices) ?: selected)
                    selectors += this
                    content.addView(this, LinearLayout.LayoutParams(-1, dp(56)))
                }
            }
            val designs = listOf("Follow app", "Carbon · technical minimalism", "Liquid · soft depth", "Editorial · reading journal", "Tactical · telemetry")
            val style = selector("Design", designs, existing.style?.ordinal?.plus(1) ?: 0)
            val accents = listOf(null, "6EE7D1", "D3B9FF", "FFAA75", "91C8FF")
            val accent = selector("Accent", listOf("Theme default", "Turquoise", "Violet", "Amber", "Blue"), accents.indexOf(existing.accentHex).coerceAtLeast(0))
            val densities = listOf(null) + WidgetDensity.entries
            val density = selector("Density", listOf("Follow app", "Compact", "Comfortable", "Spacious"), densities.indexOf(existing.density).coerceAtLeast(0))
            val surface = selector("Surface", listOf("Theme default", "Dark", "Light", "OLED black"), existing.surface.ordinal)
            fun selected() = WidgetInstanceAppearance(
                WidgetDesign.entries.getOrNull(style.selectedItemPosition - 1), accents[accent.selectedItemPosition],
                densities[density.selectedItemPosition], WidgetSurface.entries[surface.selectedItemPosition])
            fun updatePreview() {
                val appearance = resolveWidgetAppearance(app, selected())
                val colors = widgetPalette(appearance)
                preview.text = "DEMO · ${appearance.style.name.lowercase().replaceFirstChar { it.uppercase() }}\nSample map · Ends 18:30\nPreview only · no game data"
                preview.setBackgroundColor(colors.background.toInt()); preview.setTextColor(colors.foreground.toInt())
                preview.typeface = when (appearance.style) {
                    WidgetDesign.EDITORIAL -> Typeface.SERIF
                    WidgetDesign.TACTICAL -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
            }
            val listener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updatePreview()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            listOf(style, accent, density, surface).forEach { it.onItemSelectedListener = listener }
            val status = text("")
            val save = Button(this@WidgetAppearanceActivity).apply { text = "Apply to this widget"; minHeight = dp(48) }
            content.addView(save)
            save.setOnClickListener {
                save.isEnabled = false
                scope.launch {
                    try {
                        val chosen = selected()
                        updateAppWidgetState(this@WidgetAppearanceActivity, glanceId) { state ->
                            chosen.style?.let { state[WIDGET_STYLE_KEY] = it.name } ?: state.remove(WIDGET_STYLE_KEY)
                            chosen.accentHex?.let { state[WIDGET_ACCENT_KEY] = it } ?: state.remove(WIDGET_ACCENT_KEY)
                            chosen.density?.let { state[WIDGET_DENSITY_KEY] = it.name } ?: state.remove(WIDGET_DENSITY_KEY)
                            state[WIDGET_SURFACE_KEY] = chosen.surface.name
                        }
                        when (info.provider.className) {
                            MapRotationWidgetReceiver::class.java.name -> MapRotationWidget().update(this@WidgetAppearanceActivity, glanceId)
                            RankedGoalWidgetReceiver::class.java.name -> RankedGoalWidget().update(this@WidgetAppearanceActivity, glanceId)
                            PredatorThresholdWidgetReceiver::class.java.name -> PredatorThresholdWidget().update(this@WidgetAppearanceActivity, glanceId)
                            else -> RankProgressWidget().update(this@WidgetAppearanceActivity, glanceId)
                        }
                        finish()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status.text = "Appearance could not be saved. Your data is unchanged."; save.isEnabled = true }
                }
            }
            content.addView(Button(this@WidgetAppearanceActivity).apply { text = "Cancel"; minHeight = dp(48); setOnClickListener { finish() } })
            setContentView(ScrollView(this@WidgetAppearanceActivity).apply { addView(content) })
            updatePreview()
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putIntArray("appearanceSelections", selectors.map { it.selectedItemPosition }.toIntArray())
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
