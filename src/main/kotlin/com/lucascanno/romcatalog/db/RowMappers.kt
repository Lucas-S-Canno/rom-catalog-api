package com.lucascanno.romcatalog.db

import com.lucascanno.romcatalog.domain.Favorite
import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.domain.Rom
import org.jetbrains.exposed.sql.ResultRow

fun ResultRow.toRom(): Rom = Rom(
    id = this[RomsTable.id].value,
    name = this[RomsTable.name],
    system = GameSystem.fromApi(this[RomsTable.system])
        ?: error("Unknown system '${this[RomsTable.system]}' stored for rom ${this[RomsTable.id].value}"),
    sizeBytes = this[RomsTable.sizeBytes],
    hash = this[RomsTable.hash],
    storageKey = this[RomsTable.storageKey],
    coverUrl = this[RomsTable.coverUrl],
    createdAt = this[RomsTable.createdAt],
)

fun ResultRow.toFavorite(): Favorite = Favorite(
    id = this[FavoritesTable.id].value,
    romId = this[FavoritesTable.romId].value,
    createdAt = this[FavoritesTable.createdAt],
)
