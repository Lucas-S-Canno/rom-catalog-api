package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.error.invalidBody
import com.lucascanno.romcatalog.error.romNotFound
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.storage.StorageClient
import com.lucascanno.romcatalog.web.dto.PageDto
import com.lucascanno.romcatalog.web.dto.RomDto
import com.lucascanno.romcatalog.web.dto.UpdateRomRequest
import com.lucascanno.romcatalog.web.dto.toDto
import io.ktor.http.HttpStatusCode
import java.util.UUID

class RomService(
    private val roms: RomRepository,
    private val storage: StorageClient,
) {

    /**
     * Lists the catalog. `size` is clamped to `1..MAX_PAGE_SIZE` and `page` to `>= 0`
     * as a defensive measure — the route layer already rejects malformed values.
     */
    suspend fun list(system: GameSystem?, page: Int, size: Int): PageDto<RomDto> {
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val result = roms.findAll(system, safePage, safeSize)
        return PageDto(
            items = result.items.map { it.toDto() },
            page = safePage,
            size = safeSize,
            total = result.total,
        )
    }

    suspend fun getById(id: UUID): RomDto =
        roms.findById(id)?.toDto() ?: throw romNotFound(id)

    /**
     * Deletes a ROM and its stored object. The object is removed first: if storage
     * is unreachable the catalog row is left intact and the caller gets a 503.
     *
     * @throws com.lucascanno.romcatalog.error.ApiException 404 when the ROM is unknown.
     * @throws com.lucascanno.romcatalog.error.StorageUnavailableException 503 when the store is down.
     */
    suspend fun delete(id: UUID) {
        val rom = roms.findById(id) ?: throw romNotFound(id)
        storage.removeObject(rom.storageKey)
        roms.delete(id)
    }

    /**
     * Patches the mutable metadata (`name`, `coverUrl`) of a ROM.
     *
     * @throws com.lucascanno.romcatalog.error.ApiException 400 NOTHING_TO_CHANGE / INVALID_BODY, 404 ROM_NOT_FOUND.
     */
    suspend fun update(id: UUID, req: UpdateRomRequest): RomDto {
        if (req.name == null && req.coverUrl == null) {
            throw ApiException(HttpStatusCode.BadRequest, "NOTHING_TO_CHANGE", "Provide 'name' and/or 'coverUrl'")
        }
        val newName = req.name?.also {
            if (it.isBlank()) throw invalidBody("name must not be blank")
        }?.trim()
        val clearCover = req.coverUrl != null && req.coverUrl.isBlank()
        val newCover = req.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
        return roms.update(id, newName, newCover, clearCover)?.toDto() ?: throw romNotFound(id)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 200
    }
}
