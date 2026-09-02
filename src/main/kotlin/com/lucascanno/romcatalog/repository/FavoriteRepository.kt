package com.lucascanno.romcatalog.repository

import com.lucascanno.romcatalog.db.FavoritesTable
import com.lucascanno.romcatalog.db.RomsTable
import com.lucascanno.romcatalog.db.toFavorite
import com.lucascanno.romcatalog.db.toRom
import com.lucascanno.romcatalog.domain.Favorite
import com.lucascanno.romcatalog.domain.Rom
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class FavoriteRepository(private val database: Database) {

    /**
     * Idempotent: favoriting an already-favorited ROM returns the existing row
     * instead of creating a duplicate (the `favorites_rom_unique` constraint
     * would reject it anyway).
     */
    suspend fun add(romId: UUID): Favorite = dbQuery {
        val existing = FavoritesTable.selectAll()
            .where { FavoritesTable.romId eq romId }
            .singleOrNull()
        if (existing != null) {
            existing.toFavorite()
        } else {
            val createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val id = FavoritesTable.insertAndGetId {
                it[FavoritesTable.romId] = romId
                it[FavoritesTable.createdAt] = createdAt
            }.value
            Favorite(id, romId, createdAt)
        }
    }

    suspend fun existsByRomId(romId: UUID): Boolean = dbQuery {
        FavoritesTable.selectAll().where { FavoritesTable.romId eq romId }.limit(1).any()
    }

    /** @return true when a row was actually removed. */
    suspend fun removeByRomId(romId: UUID): Boolean = dbQuery {
        FavoritesTable.deleteWhere { FavoritesTable.romId eq romId } > 0
    }

    suspend fun listWithRom(): List<Pair<Favorite, Rom>> = dbQuery {
        (FavoritesTable innerJoin RomsTable)
            .selectAll()
            .orderBy(FavoritesTable.createdAt to SortOrder.DESC)
            .map { it.toFavorite() to it.toRom() }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
