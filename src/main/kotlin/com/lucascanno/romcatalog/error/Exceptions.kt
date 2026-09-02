package com.lucascanno.romcatalog.error

import io.ktor.http.HttpStatusCode

/**
 * Domain error that carries the HTTP status and a stable machine-readable code.
 * Rendered by StatusPages into the standard error envelope `{ "error": { code, message } }`.
 */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

/** Raised when the object storage is unreachable or the expected object is missing. Maps to 503. */
class StorageUnavailableException(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// ── Factories for the common cases ────────────────────────────────────────────

fun romNotFound(id: Any): ApiException =
    ApiException(HttpStatusCode.NotFound, "ROM_NOT_FOUND", "ROM '$id' not found")

fun invalidQueryParam(name: String, reason: String): ApiException =
    ApiException(HttpStatusCode.BadRequest, "INVALID_QUERY_PARAM", "Query parameter '$name' $reason")

fun invalidPathParam(name: String, reason: String): ApiException =
    ApiException(HttpStatusCode.BadRequest, "INVALID_PATH_PARAM", "Path parameter '$name' $reason")

fun invalidBody(reason: String): ApiException =
    ApiException(HttpStatusCode.BadRequest, "INVALID_BODY", reason)
