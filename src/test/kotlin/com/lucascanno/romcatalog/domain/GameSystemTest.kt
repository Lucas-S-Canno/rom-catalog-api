package com.lucascanno.romcatalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameSystemTest {

    @Test
    fun `maps known api values case-insensitively`() {
        assertEquals(GameSystem.GBA, GameSystem.fromApi("GBA"))
        assertEquals(GameSystem.GBA, GameSystem.fromApi("gba"))
        assertEquals(GameSystem.NDS, GameSystem.fromApi("NDS"))
        assertEquals(GameSystem.N3DS, GameSystem.fromApi("3DS"))
        assertEquals(GameSystem.N3DS, GameSystem.fromApi("3ds"))
    }

    @Test
    fun `returns null for unknown values`() {
        assertNull(GameSystem.fromApi("N64"))
        assertNull(GameSystem.fromApi(""))
        assertNull(GameSystem.fromApi("PSX"))
    }

    @Test
    fun `api string is the wire representation`() {
        assertEquals("3DS", GameSystem.N3DS.api)
    }
}
