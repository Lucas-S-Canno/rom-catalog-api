package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.error.romNotFound
import com.lucascanno.romcatalog.repository.FavoriteRepository
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.web.dto.FavoriteDto
import com.lucascanno.romcatalog.web.dto.toDto
import java.util.UUID

class FavoriteService(
    private val favorites: FavoriteRepository,
    private val roms: RomRepository,
) {
    data class AddResult(val favorite: FavoriteDto, val created: Boolean)

    suspend fun list(): List<FavoriteDto> =
        favorites.listWithRom().map { it.toDto() }

    /**
     * Favorites a ROM. Idempotent: a repeated call returns the existing favorite
     * with [AddResult.created] = false so the route can answer 200 instead of 201.
     *
     * @throws com.lucascanno.romcatalog.error.ApiException 404 when `romId` is unknown.
     */
    suspend fun add(romId: UUID): AddResult {
        val rom = roms.findById(romId) ?: throw romNotFound(romId)
        val alreadyThere = favorites.existsByRomId(romId)
        val favorite = favorites.add(romId)
        return AddResult(
            favorite = (favorite to rom).toDto(),
            created = !alreadyThere,
        )
    }

    /** Idempotent: removing a ROM that is not favorited is a no-op and still succeeds (D-05 note). */
    suspend fun remove(romId: UUID) {
        favorites.removeByRomId(romId)
    }
}
