package com.lucascanno.romcatalog.ingest

import com.lucascanno.romcatalog.domain.GameSystem

/** Maps a ROM file name to its console by extension. */
object SystemDetector {

    private val byExtension: Map<String, GameSystem> = mapOf(
        "gba" to GameSystem.GBA,
        "nds" to GameSystem.NDS,
        "3ds" to GameSystem.N3DS,
        "cia" to GameSystem.N3DS,
    )

    fun extensionOf(filename: String): String =
        filename.substringAfterLast('.', "").lowercase()

    fun fromFilename(filename: String): GameSystem? =
        byExtension[extensionOf(filename)]

    /** The canonical extension to store a ROM of [system] under when the source has none. */
    fun defaultExtension(system: GameSystem): String = when (system) {
        GameSystem.GBA -> "gba"
        GameSystem.NDS -> "nds"
        GameSystem.N3DS -> "3ds"
    }
}
