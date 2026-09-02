package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.domain.Favorite
import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.domain.Rom
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.repository.FavoriteRepository
import com.lucascanno.romcatalog.repository.RomRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoriteServiceTest {

    private val favorites = mockk<FavoriteRepository>(relaxed = true)
    private val roms = mockk<RomRepository>()
    private val service = FavoriteService(favorites, roms)

    private fun rom(id: UUID) = Rom(id, "Demo", GameSystem.GBA, 10, "h", "GBA/h.bin", null, Instant.now())

    @Test
    fun `add returns created=true the first time`() = runBlocking {
        val romId = UUID.randomUUID()
        coEvery { roms.findById(romId) } returns rom(romId)
        coEvery { favorites.existsByRomId(romId) } returns false
        coEvery { favorites.add(romId) } returns Favorite(UUID.randomUUID(), romId, Instant.now())

        val result = service.add(romId)

        assertTrue(result.created)
        assertEquals(romId.toString(), result.favorite.romId)
        assertEquals(romId.toString(), result.favorite.rom.id)
    }

    @Test
    fun `add returns created=false when already favorited`() = runBlocking {
        val romId = UUID.randomUUID()
        coEvery { roms.findById(romId) } returns rom(romId)
        coEvery { favorites.existsByRomId(romId) } returns true
        coEvery { favorites.add(romId) } returns Favorite(UUID.randomUUID(), romId, Instant.now())

        val result = service.add(romId)

        assertFalse(result.created)
    }

    @Test
    fun `add throws 404 when the rom does not exist`() = runBlocking {
        val romId = UUID.randomUUID()
        coEvery { roms.findById(romId) } returns null

        val ex = assertFailsWith<ApiException> { service.add(romId) }
        assertEquals("ROM_NOT_FOUND", ex.code)
        coVerify(exactly = 0) { favorites.add(any()) }
    }

    @Test
    fun `remove delegates to the repository and never throws for a non-favorite`() = runBlocking {
        val romId = UUID.randomUUID()
        coEvery { favorites.removeByRomId(romId) } returns false

        service.remove(romId)

        coVerify { favorites.removeByRomId(romId) }
    }
}
