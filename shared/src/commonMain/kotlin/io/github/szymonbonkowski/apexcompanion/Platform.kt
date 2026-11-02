package io.github.szymonbonkowski.apexcompanion

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform