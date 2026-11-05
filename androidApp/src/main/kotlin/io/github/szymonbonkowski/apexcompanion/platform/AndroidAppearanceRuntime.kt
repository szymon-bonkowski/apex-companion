package io.github.szymonbonkowski.apexcompanion.platform

import android.content.Context
import io.github.szymonbonkowski.apexcompanion.appearance.*
import java.io.File

/** Cosmetic state has its own bounded atomic record, completely separate from private game data. */
object AndroidAppearanceRuntime {
    @Volatile private var instance: AppearanceRepository? = null

    fun get(context: Context): AppearanceRepository =
        instance
            ?: synchronized(this) {
                instance
                    ?: AppearanceRepository(
                            AndroidRotationStore(
                                File(context.filesDir, "appearance-v1.json"),
                                APPEARANCE_MAX_BYTES,
                            )
                        )
                        .also { instance = it }
            }
}
