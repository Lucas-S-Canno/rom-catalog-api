package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.domain.PageResult
import com.lucascanno.romcatalog.domain.Rom
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.storage.StorageClient
import com.lucascanno.romcatalog.web.dto.UpdateRomRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RomServiceTest {

    private val repo = mockk<RomRepository>()
    private val storage = mockk<StorageClient>(relaxed = true)
    private val service = RomService(repo, storage)

    private fun sampleRom(id: UUID) = Rom(
        id = id,
        name = "Demo",
        system = GameSystem.GBA,
        sizeBytes = 123,
        hash = "deadbeef",
        storageKey = "GBA/deadbeef.bin",
        coverUrl = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `clamps oversized page size to the maximum`() = runBlocking {
        coEvery { repo.findAll(any(), any(), any()) } returns PageResult(emptyList(), 0)

        service.list(system = null, page = 0, size = 9_999)

        coVerify { repo.findAll(null, 0, RomService.MAX_PAGE_SIZE) }
    }

    @Test
    fun `clamps negative page and size defensively`() = runBlocking {
        coEvery { repo.findAll(any(), any(), any()) } returns PageResult(emptyList(), 0)

        val page = service.list(system = null, page = -5, size = -1)

        coVerify { repo.findAll(null, 0, 1) }
        assertEquals(0, page.page)
        assertEquals(1, page.size)
    }

    @Test
    fun `passes the system filter through and echoes pagination`() = runBlocking {
        coEvery { repo.findAll(GameSystem.NDS, 2, 10) } returns PageResult(emptyList(), 25)

        val page = service.list(system = GameSystem.NDS, page = 2, size = 10)

        assertEquals(2, page.page)
        assertEquals(10, page.size)
        assertEquals(25, page.total)
    }

    @Test
    fun `getById maps entity to dto and keeps null cover`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns Rom(
            id = id,
            name = "Demo",
            system = GameSystem.GBA,
            sizeBytes = 123,
            hash = "deadbeef",
            storageKey = "GBA/deadbeef.bin",
            coverUrl = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        val dto = service.getById(id)

        assertEquals(id.toString(), dto.id)
        assertEquals("GBA", dto.system)
        assertEquals(123, dto.sizeBytes)
        assertNull(dto.coverUrl)
    }

    @Test
    fun `getById throws 404 when missing`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns null

        val ex = assertFailsWith<ApiException> { service.getById(id) }
        assertEquals("ROM_NOT_FOUND", ex.code)
    }

    @Test
    fun `delete removes the object then the row`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns sampleRom(id)
        coEvery { repo.delete(id) } returns true

        service.delete(id)

        coVerify { storage.removeObject("GBA/deadbeef.bin") }
        coVerify { repo.delete(id) }
    }

    @Test
    fun `delete throws 404 when the rom is unknown`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findById(id) } returns null

        val ex = assertFailsWith<ApiException> { service.delete(id) }
        assertEquals("ROM_NOT_FOUND", ex.code)
        coVerify(exactly = 0) { storage.removeObject(any()) }
    }

    @Test
    fun `update with an empty body is NOTHING_TO_CHANGE`() = runBlocking {
        val ex = assertFailsWith<ApiException> { service.update(UUID.randomUUID(), UpdateRomRequest()) }
        assertEquals("NOTHING_TO_CHANGE", ex.code)
    }

    @Test
    fun `update passes a blank coverUrl through as a clear`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.update(id, "Novo", null, true) } returns sampleRom(id).copy(name = "Novo")

        val dto = service.update(id, UpdateRomRequest(name = "Novo", coverUrl = ""))

        assertEquals("Novo", dto.name)
        coVerify { repo.update(id, "Novo", null, true) }
    }

    @Test
    fun `update throws 404 when the rom is unknown`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.update(id, "Novo", null, false) } returns null

        val ex = assertFailsWith<ApiException> { service.update(id, UpdateRomRequest(name = "Novo")) }
        assertEquals("ROM_NOT_FOUND", ex.code)
    }
}
