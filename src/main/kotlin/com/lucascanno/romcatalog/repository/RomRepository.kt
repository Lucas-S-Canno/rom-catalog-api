package com.lucascanno.romcatalog.repository

import com.lucascanno.romcatalog.db.RomsTable
import com.lucascanno.romcatalog.db.toRom
import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.domain.NewRom
import com.lucascanno.romcatalog.domain.PageResult
import com.lucascanno.romcatalog.domain.Rom
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class RomRepository(private val database: Database) {

    suspend fun create(command: NewRom): Rom = dbQuery {
        val createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val id = RomsTable.insertAndGetId {
            it[name] = command.name
            it[system] = command.system.api
            it[sizeBytes] = command.sizeBytes
            it[hash] = command.hash
            it[storageKey] = command.storageKey
            it[coverUrl] = command.coverUrl
            it[RomsTable.createdAt] = createdAt
        }.value
        Rom(
            id = id,
            name = command.name,
            system = command.system,
            sizeBytes = command.sizeBytes,
            hash = command.hash,
            storageKey = command.storageKey,
            coverUrl = command.coverUrl,
            createdAt = createdAt,
        )
    }

    suspend fun findById(id: UUID): Rom? = dbQuery {
        RomsTable.selectAll()
            .where { RomsTable.id eq id }
            .singleOrNull()
            ?.toRom()
    }

    /** @return true when a row was actually removed. Favorites cascade away (FK `ON DELETE CASCADE`). */
    suspend fun delete(id: UUID): Boolean = dbQuery {
        RomsTable.deleteWhere { RomsTable.id eq id } > 0
    }

    /**
     * Patches the mutable metadata of a ROM. `name`/`coverUrl` are applied only when non-null;
     * `clearCover` wins over `coverUrl` and sets the column to NULL.
     * @return the updated [Rom], or null when the id does not exist.
     */
    suspend fun update(id: UUID, name: String?, coverUrl: String?, clearCover: Boolean): Rom? = dbQuery {
        val changed = RomsTable.update({ RomsTable.id eq id }) {
            if (name != null) it[RomsTable.name] = name
            when {
                clearCover -> it[RomsTable.coverUrl] = null
                coverUrl != null -> it[RomsTable.coverUrl] = coverUrl
            }
        }
        if (changed == 0) null
        else RomsTable.selectAll().where { RomsTable.id eq id }.singleOrNull()?.toRom()
    }

    suspend fun findAll(system: GameSystem?, page: Int, size: Int): PageResult<Rom> = dbQuery {
        val query = RomsTable.selectAll()
        if (system != null) {
            query.andWhere { RomsTable.system eq system.api }
        }
        val total = query.count()
        val items = query
            .orderBy(RomsTable.createdAt to SortOrder.ASC, RomsTable.id to SortOrder.ASC)
            .limit(size)
            .offset(page.toLong() * size)
            .map { it.toRom() }
        PageResult(items, total)
    }

    suspend fun existsByHash(hash: String): Boolean = dbQuery {
        RomsTable.selectAll().where { RomsTable.hash eq hash }.limit(1).any()
    }

    suspend fun findByHash(hash: String): Rom? = dbQuery {
        RomsTable.selectAll().where { RomsTable.hash eq hash }.singleOrNull()?.toRom()
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
