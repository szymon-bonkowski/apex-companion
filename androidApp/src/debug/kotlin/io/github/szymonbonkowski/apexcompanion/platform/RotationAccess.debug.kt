package io.github.szymonbonkowski.apexcompanion.platform

import android.content.Context
import io.github.szymonbonkowski.apexcompanion.maps.UnconfiguredRotationProvider
import io.github.szymonbonkowski.apexcompanion.network.*
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

/** Authorized developer testing only. No key is read from project files, resources or build properties. */
internal fun rotationRuntimeConfig(context: Context): RotationRuntimeConfig {
    val file = File(context.filesDir, "developer-als-key.private")
    if (!file.isFile) return RotationRuntimeConfig(UnconfiguredRotationProvider, false)
    val provider = ApexStatusRotationProvider(rotationHttpClient(OkHttp.create()),
        RotationAccess.AuthorizedDeveloper {
            require(file.length() in 1..4096)
            file.readText().trim().also { key ->
                require(key.isNotEmpty() && key.all { it.code in 33..126 })
            }
        })
    return RotationRuntimeConfig(provider, true, file.lastModified())
}
