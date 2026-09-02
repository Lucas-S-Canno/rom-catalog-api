package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.error.romNotFound
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.web.dto.PageDto
import com.lucascanno.romcatalog.web.dto.RomDto
import com.lucascanno.romcatalog.web.dto.toDto
import java.util.UUID

class RomService(private val roms: RomRepository) {

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

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 200
    }
}
