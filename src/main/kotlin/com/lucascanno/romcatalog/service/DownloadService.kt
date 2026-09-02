package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.config.DownloadConfig
import com.lucascanno.romcatalog.error.StorageUnavailableException
import com.lucascanno.romcatalog.error.romNotFound
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.storage.StorageClient
import com.lucascanno.romcatalog.web.dto.DownloadResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DownloadService(
    private val roms: RomRepository,
    private val storage: StorageClient,
    private val config: DownloadConfig,
) {
    /**
     * Builds a temporary presigned URL that downloads the ROM bytes straight from
     * object storage (the API never proxies them).
     *
     * @throws com.lucascanno.romcatalog.error.ApiException 404 when the ROM is unknown.
     * @throws StorageUnavailableException 503 when the store is down or the object is missing.
     */
    suspend fun buildFor(id: UUID): DownloadResponse {
        val rom = roms.findById(id) ?: throw romNotFound(id)

        if (!storage.objectExists(rom.storageKey)) {
            throw StorageUnavailableException("Object '${rom.storageKey}' is missing from the bucket")
        }

        val ttl = Duration.ofSeconds(config.urlTtlSeconds)
        val url = storage.presignedGetUrl(rom.storageKey, ttl)
        return DownloadResponse(
            url = url,
            expiresAt = Instant.now().plus(ttl).toString(),
            hash = rom.hash,
            sizeBytes = rom.sizeBytes,
        )
    }
}
