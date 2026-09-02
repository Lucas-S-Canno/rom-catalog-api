package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.config.DownloadConfig
import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.domain.Rom
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.error.StorageUnavailableException
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.storage.StorageClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadServiceTest {

    private val roms = mockk<RomRepository>()
    private val storage = mockk<StorageClient>()
    private val service = DownloadService(roms, storage, DownloadConfig(urlTtlSeconds = 900))

    private fun rom(id: UUID) = Rom(
        id = id,
        name = "Demo",
        system = GameSystem.N3DS,
        sizeBytes = 2_000_000_000L,
        hash = "cafe",
        storageKey = "3DS/cafe.bin",
        coverUrl = null,
        createdAt = Instant.now(),
    )

    @Test
    fun `builds a presigned response for an existing rom and object`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { roms.findById(id) } returns rom(id)
        every { storage.objectExists("3DS/cafe.bin") } returns true
        every { storage.presignedGetUrl("3DS/cafe.bin", Duration.ofSeconds(900)) } returns "http://pub/roms/3DS/cafe.bin?sig=x"

        val response = service.buildFor(id)

        assertEquals("http://pub/roms/3DS/cafe.bin?sig=x", response.url)
        assertEquals("cafe", response.hash)
        assertEquals(2_000_000_000L, response.sizeBytes)
        assertTrue(Instant.parse(response.expiresAt).isAfter(Instant.now()))
    }

    @Test
    fun `throws 404 when the rom is unknown`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { roms.findById(id) } returns null

        val ex = assertFailsWith<ApiException> { service.buildFor(id) }
        assertEquals("ROM_NOT_FOUND", ex.code)
    }

    @Test
    fun `throws storage unavailable when the object is missing from the bucket`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { roms.findById(id) } returns rom(id)
        every { storage.objectExists("3DS/cafe.bin") } returns false

        val ex = assertFailsWith<StorageUnavailableException> { service.buildFor(id) }
        assertTrue(ex.message!!.contains("3DS/cafe.bin"))
    }
}
