package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.config.UploadConfig
import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.storage.StorageClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestionServiceTest {

    private val roms = mockk<RomRepository>()
    private val storage = mockk<StorageClient>()

    @Test
    fun `presignUpload names the object under the system prefix with a random id`() {
        val service = IngestionService(roms, storage, UploadConfig(urlTtlSeconds = 1800))
        val keySlot = slot<String>()
        val ttlSlot = slot<Duration>()
        every { storage.presignedPutUrl(capture(keySlot), capture(ttlSlot)) } returns "http://minio/signed-put-url"

        val result = service.presignUpload(GameSystem.N3DS, "Some Game (USA).3ds")

        assertTrue(keySlot.captured.startsWith("3DS/"))
        assertTrue(keySlot.captured.endsWith(".3ds"))
        val idPart = keySlot.captured.removePrefix("3DS/").removeSuffix(".3ds")
        assertEquals(idPart, UUID.fromString(idPart).toString()) // is a real UUID
        assertEquals(Duration.ofSeconds(1800), ttlSlot.captured)
        assertEquals("http://minio/signed-put-url", result.uploadUrl)
        assertEquals(keySlot.captured, result.storageKey)
    }

    @Test
    fun `presignUpload falls back to the system's default extension when the filename has none`() {
        val service = IngestionService(roms, storage, UploadConfig())
        every { storage.presignedPutUrl(any(), any()) } returns "http://minio/signed-put-url"

        val result = service.presignUpload(GameSystem.GBA, "no-extension-here")

        assertTrue(result.storageKey.endsWith(".gba"), result.storageKey)
    }

    @Test
    fun `presignUpload reports an expiry consistent with the configured ttl`() {
        val service = IngestionService(roms, storage, UploadConfig(urlTtlSeconds = 60))
        every { storage.presignedPutUrl(any(), any()) } returns "http://minio/signed-put-url"

        val before = Instant.now()
        val result = service.presignUpload(GameSystem.NDS, "game.nds")
        val after = Instant.now()

        assertTrue(!result.expiresAt.isBefore(before.plusSeconds(59)))
        assertTrue(!result.expiresAt.isAfter(after.plusSeconds(61)))
    }
}
