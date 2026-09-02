package com.lucascanno.romcatalog.repository

import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestInfra
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoriteRepositoryTest : IntegrationTestBase() {

    @Test
    fun `add is idempotent for the same rom`() = runBlocking {
        val rom = romRepository.create(newRom())

        val first = favoriteRepository.add(rom.id)
        val second = favoriteRepository.add(rom.id)

        assertEquals(first.id, second.id)
        assertEquals(1, favoriteRowCount())
    }

    @Test
    fun `existsByRomId reflects state`() = runBlocking {
        val rom = romRepository.create(newRom())
        assertFalse(favoriteRepository.existsByRomId(rom.id))

        favoriteRepository.add(rom.id)

        assertTrue(favoriteRepository.existsByRomId(rom.id))
    }

    @Test
    fun `removeByRomId deletes and reports whether a row was removed`() = runBlocking {
        val rom = romRepository.create(newRom())
        favoriteRepository.add(rom.id)

        assertTrue(favoriteRepository.removeByRomId(rom.id))
        assertFalse(favoriteRepository.removeByRomId(rom.id))
        assertEquals(0, favoriteRowCount())
    }

    @Test
    fun `removeByRomId on a non-favorite rom is a no-op`() = runBlocking {
        assertFalse(favoriteRepository.removeByRomId(UUID.randomUUID()))
    }

    @Test
    fun `deleting the rom cascades to its favorite`() = runBlocking {
        val rom = romRepository.create(newRom())
        favoriteRepository.add(rom.id)

        TestInfra.execute("DELETE FROM roms WHERE id = '${rom.id}'")

        assertEquals(0, favoriteRowCount())
    }

    @Test
    fun `listWithRom joins favorite and rom, newest first`() = runBlocking {
        val a = romRepository.create(newRom(name = "A"))
        val b = romRepository.create(newRom(name = "B"))
        favoriteRepository.add(a.id)
        Thread.sleep(5)
        favoriteRepository.add(b.id)

        val list = favoriteRepository.listWithRom()

        assertEquals(2, list.size)
        assertEquals(b.id, list.first().first.romId)
        assertEquals("B", list.first().second.name)
        assertEquals(setOf("A", "B"), list.map { it.second.name }.toSet())
    }

    private fun favoriteRowCount(): Int =
        TestInfra.query("SELECT count(*) FROM favorites") { rs -> rs.getInt(1) }.first()
}
