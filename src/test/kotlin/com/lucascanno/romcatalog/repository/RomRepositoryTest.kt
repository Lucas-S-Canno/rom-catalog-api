package com.lucascanno.romcatalog.repository

import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.support.IntegrationTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RomRepositoryTest : IntegrationTestBase() {

    @Test
    fun `create then findById round-trips every field`() = runBlocking {
        val created = romRepository.create(
            newRom(name = "Cave Story", system = GameSystem.NDS, sizeBytes = 4096, coverUrl = "http://c")
        )

        val found = romRepository.findById(created.id)

        assertEquals(created, found)
        assertEquals("Cave Story", found?.name)
        assertEquals(GameSystem.NDS, found?.system)
        assertEquals("http://c", found?.coverUrl)
    }

    @Test
    fun `findById returns null for an unknown id`() = runBlocking {
        assertNull(romRepository.findById(UUID.randomUUID()))
    }

    @Test
    fun `findAll filters by system`() = runBlocking {
        romRepository.create(newRom(system = GameSystem.GBA))
        romRepository.create(newRom(system = GameSystem.GBA))
        romRepository.create(newRom(system = GameSystem.N3DS))

        val gba = romRepository.findAll(GameSystem.GBA, page = 0, size = 50)
        val all = romRepository.findAll(null, page = 0, size = 50)

        assertEquals(2, gba.total)
        assertEquals(2, gba.items.size)
        assertTrue(gba.items.all { it.system == GameSystem.GBA })
        assertEquals(3, all.total)
    }

    @Test
    fun `findAll paginates with a stable order and no overlap`() = runBlocking {
        repeat(5) { romRepository.create(newRom(name = "rom-$it")) }

        val page0 = romRepository.findAll(null, page = 0, size = 2)
        val page1 = romRepository.findAll(null, page = 1, size = 2)
        val page2 = romRepository.findAll(null, page = 2, size = 2)

        assertEquals(5, page0.total)
        assertEquals(listOf(2, 2, 1), listOf(page0.items.size, page1.items.size, page2.items.size))
        val ids = (page0.items + page1.items + page2.items).map { it.id }
        assertEquals(ids.size, ids.toSet().size, "pages must not overlap")
    }

    @Test
    fun `existsByHash reflects stored rows`() = runBlocking {
        val rom = romRepository.create(newRom(hash = "abc123"))

        assertTrue(romRepository.existsByHash(rom.hash))
        assertTrue(!romRepository.existsByHash("nope"))
    }

    @Test
    fun `duplicate hash violates the unique constraint`() = runBlocking {
        romRepository.create(newRom(hash = "same-hash"))

        val ex = assertFailsWith<ExposedSQLException> {
            romRepository.create(newRom(hash = "same-hash"))
        }
        assertTrue(ex.message?.contains("roms_hash_unique") == true || ex.message?.contains("unique") == true)
    }
}
