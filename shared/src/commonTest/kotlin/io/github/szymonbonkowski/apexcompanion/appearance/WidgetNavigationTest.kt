package io.github.szymonbonkowski.apexcompanion.appearance

import io.github.szymonbonkowski.apexcompanion.widgetDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetNavigationTest {
    @Test fun onlyExactPublicWidgetDestinationsAreAccepted() {
        assertEquals("Maps", widgetDestination("apexcompanion://maps"))
        assertEquals("Ranked progress", widgetDestination("apexcompanion://ranked/"))
        listOf(null, "https://maps", "apexcompanion://pairing", "apexcompanion://ranked?token=private",
            "apexcompanion://maps/../settings", "apexcompanion://maps/anything").forEach { assertNull(widgetDestination(it)) }
    }
}
