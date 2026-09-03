package com.lucascanno.romcatalog.web.dto

import com.lucascanno.romcatalog.domain.Favorite
import com.lucascanno.romcatalog.domain.PageResult
import com.lucascanno.romcatalog.domain.Rom
import com.lucascanno.romcatalog.domain.User
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String = "UP")

@Serializable
data class CheckResult(val status: String, val detail: String? = null)

@Serializable
data class ReadinessResponse(val status: String, val checks: Map<String, CheckResult>)

@Serializable
data class ErrorResponse(val error: ErrorBody) {
    constructor(code: String, message: String) : this(ErrorBody(code, message))
}

@Serializable
data class ErrorBody(val code: String, val message: String)

@Serializable
data class RomDto(
    val id: String,
    val name: String,
    val system: String,
    val sizeBytes: Long,
    val hash: String,
    val coverUrl: String? = null,
    val createdAt: String,
)

@Serializable
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
)

@Serializable
data class DownloadResponse(
    val url: String,
    val expiresAt: String,
    val hash: String,
    val sizeBytes: Long,
)

@Serializable
data class AdminPingResponse(val scope: String, val status: String = "ok")

/** JSON body for `POST /admin/roms` when the object is already in the bucket. */
@Serializable
data class RegisterRomRequest(
    val name: String,
    val system: String,
    val hash: String,
    val sizeBytes: Long,
    val storageKey: String,
    val coverUrl: String? = null,
)

/**
 * JSON body for `PATCH /admin/roms/{id}`. Only the mutable metadata:
 * - `name` omitted → unchanged; present → new name.
 * - `coverUrl` omitted → unchanged; `""` → cover cleared; non-empty → new cover URL.
 */
@Serializable
data class UpdateRomRequest(
    val name: String? = null,
    val coverUrl: String? = null,
)

@Serializable
data class AddFavoriteRequest(val romId: String)

@Serializable
data class FavoriteDto(
    val romId: String,
    val createdAt: String,
    val rom: RomDto,
)

// ── auth / users ─────────────────────────────────────────────────────────────

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long,
    val role: String,
    val mustChangeCredentials: Boolean,
)

@Serializable
data class ChangeCredentialsRequest(
    val currentPassword: String,
    val newUsername: String? = null,
    val newPassword: String? = null,
)

@Serializable
data class MeResponse(
    val id: String,
    val username: String,
    val role: String,
    val mustChangeCredentials: Boolean,
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val role: String? = null, // defaults to "user"
)

@Serializable
data class ResetPasswordRequest(val password: String)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val role: String,
    val mustChangeCredentials: Boolean,
    val createdAt: String,
)

fun User.toDto(): UserDto = UserDto(
    id = id.toString(),
    username = username,
    role = role.claim,
    mustChangeCredentials = mustChangeCredentials,
    createdAt = createdAt.toString(),
)

fun User.toMe(): MeResponse = MeResponse(
    id = id.toString(),
    username = username,
    role = role.claim,
    mustChangeCredentials = mustChangeCredentials,
)

// ── mappers ──────────────────────────────────────────────────────────────────

fun Rom.toDto(): RomDto = RomDto(
    id = id.toString(),
    name = name,
    system = system.api,
    sizeBytes = sizeBytes,
    hash = hash,
    coverUrl = coverUrl,
    createdAt = createdAt.toString(),
)

fun PageResult<Rom>.toDto(page: Int, size: Int): PageDto<RomDto> =
    PageDto(items = items.map { it.toDto() }, page = page, size = size, total = total)

fun Pair<Favorite, Rom>.toDto(): FavoriteDto {
    val (favorite, rom) = this
    return FavoriteDto(
        romId = favorite.romId.toString(),
        createdAt = favorite.createdAt.toString(),
        rom = rom.toDto(),
    )
}
