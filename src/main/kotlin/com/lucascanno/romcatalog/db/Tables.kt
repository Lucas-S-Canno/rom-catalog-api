package com.lucascanno.romcatalog.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed mappings for the schema. The schema itself is owned by the Flyway
 * migrations under `resources/db/migration` — these objects only describe how to
 * read/write it, they never create it.
 */
object RomsTable : UUIDTable("roms") {
    val name = text("name")
    val system = varchar("system", 8)
    val sizeBytes = long("size_bytes")
    val hash = text("hash")
    val storageKey = text("storage_key")
    val coverUrl = text("cover_url").nullable()
    val createdAt = timestamp("created_at")
}

object FavoritesTable : UUIDTable("favorites") {
    val romId = reference("rom_id", RomsTable, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at")
}
