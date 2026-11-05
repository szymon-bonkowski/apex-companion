package io.github.szymonbonkowski.apexcompanion

/** Only public, read-only widget destinations are accepted. URLs carry no user data. */
fun widgetDestination(url: String?): String? = when (url) {
    "apexcompanion://maps", "apexcompanion://maps/" -> "Maps"
    "apexcompanion://ranked", "apexcompanion://ranked/" -> "Ranked progress"
    else -> null
}

data class WidgetNavigationRequest(val destination: String, val sequence: Long)
