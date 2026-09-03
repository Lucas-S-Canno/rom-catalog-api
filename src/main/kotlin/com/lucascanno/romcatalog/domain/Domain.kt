package com.lucascanno.romcatalog.domain

import java.time.Instant
import java.util.UUID

enum class GameSystem(val api: String) {
    GBA("GBA"),
    NDS("NDS"),
    N3DS("3DS");

    companion object {
        fun fromApi(value: String): GameSystem? =
            entries.firstOrNull { it.api.equals(value, ignoreCase = true) }
    }
}

/** Account role. `admin` implies every `user` capability. Serialised as the JWT `scope`/`role` claim. */
enum class Role(val claim: String) {
    ADMIN("admin"),
    USER("user");

    companion object {
        fun fromClaim(value: String?): Role? = entries.firstOrNull { it.claim == value }
    }
}

data class User(
    val id: UUID,
    val username: String,
    val passwordHash: String,
    val role: Role,
    val mustChangeCredentials: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Fields needed to create a user. `id`/timestamps are assigned by the repository. */
data class NewUser(
    val username: String,
    val passwordHash: String,
    val role: Role,
    val mustChangeCredentials: Boolean = false,
)

data class Rom(
    val id: UUID,
    val name: String,
    val system: GameSystem,
    val sizeBytes: Long,
    val hash: String,
    val storageKey: String,
    val coverUrl: String?,
    val createdAt: Instant,
)

/** Fields needed to create a ROM. `id`/`createdAt` are assigned by the repository. */
data class NewRom(
    val name: String,
    val system: GameSystem,
    val sizeBytes: Long,
    val hash: String,
    val storageKey: String,
    val coverUrl: String? = null,
)

data class Favorite(
    val id: UUID,
    val romId: UUID,
    val createdAt: Instant,
)

/** A slice of a larger result set plus the total count of matching rows. */
data class PageResult<T>(
    val items: List<T>,
    val total: Long,
)
