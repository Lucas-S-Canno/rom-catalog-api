package com.lucascanno.romcatalog.ingest

import com.lucascanno.romcatalog.domain.GameSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemDetectorTest {

    @Test
    fun `maps each known extension to its system`() {
        assertEquals(GameSystem.GBA, SystemDetector.fromFilename("Zelda Minish Cap.gba"))
        assertEquals(GameSystem.NDS, SystemDetector.fromFilename("Mario Kart DS.nds"))
        assertEquals(GameSystem.N3DS, SystemDetector.fromFilename("homebrew.3ds"))
        assertEquals(GameSystem.N3DS, SystemDetector.fromFilename("installer.cia"))
    }

    @Test
    fun `extension matching is case-insensitive`() {
        assertEquals(GameSystem.GBA, SystemDetector.fromFilename("GAME.GBA"))
        assertEquals(GameSystem.N3DS, SystemDetector.fromFilename("Game.3Ds"))
    }

    @Test
    fun `unknown or missing extensions yield null`() {
        assertNull(SystemDetector.fromFilename("notes.txt"))
        assertNull(SystemDetector.fromFilename("archive.zip"))
        assertNull(SystemDetector.fromFilename("README"))
        assertNull(SystemDetector.fromFilename("rom.n64"))
    }

    @Test
    fun `defaultExtension is the canonical one per system`() {
        assertEquals("gba", SystemDetector.defaultExtension(GameSystem.GBA))
        assertEquals("nds", SystemDetector.defaultExtension(GameSystem.NDS))
        assertEquals("3ds", SystemDetector.defaultExtension(GameSystem.N3DS))
    }
}
