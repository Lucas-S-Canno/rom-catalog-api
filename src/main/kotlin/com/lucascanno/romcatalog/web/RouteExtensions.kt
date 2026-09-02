package com.lucascanno.romcatalog.web

import com.lucascanno.romcatalog.error.invalidPathParam
import com.lucascanno.romcatalog.error.invalidQueryParam
import io.ktor.server.application.ApplicationCall
import java.util.UUID

/** Reads a non-negative integer query param, or [default] when absent. Throws 400 on garbage. */
fun ApplicationCall.intQueryParam(name: String, default: Int): Int {
    val raw = request.queryParameters[name] ?: return default
    val parsed = raw.toIntOrNull() ?: throw invalidQueryParam(name, "must be an integer")
    if (parsed < 0) throw invalidQueryParam(name, "must be >= 0")
    return parsed
}

/** Reads a UUID path param. Throws 400 when missing or not a UUID. */
fun ApplicationCall.uuidPathParam(name: String): UUID {
    val raw = parameters[name] ?: throw invalidPathParam(name, "is required")
    return try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw invalidPathParam(name, "must be a UUID")
    }
}
