package com.lucascanno.romcatalog.web

import com.lucascanno.romcatalog.auth.JwtService
import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.util.UUID

/** Name of the single JWT provider. Use with `authenticate(AUTH_JWT) { ... }`. */
const val AUTH_JWT = "auth-jwt"

/**
 * Installs one JWT provider that accepts any signature-valid, non-expired token
 * whose `scope`/`role` is `user` or `admin`. Role-level authorization (admin-only)
 * is enforced per route via [requireAdminScope] so a valid-but-underprivileged
 * token gets `403`, not `401`. Verification is stateless — it does not check that
 * the user still exists (break-glass tokens have no backing row).
 */
fun Application.configureAuthentication(config: AuthConfig) {
    if (config.usingInsecureDefaultSecret) {
        log.warn("JWT_SECRET is not set — using the insecure dev default. Set JWT_SECRET before any real deployment.")
    }
    val jwtService = JwtService(config)

    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = config.jwtRealm
            verifier(jwtService.verifier())
            validate { credential ->
                val role = Role.fromClaim(credential.payload.getClaim(JwtService.SCOPE_CLAIM).asString())
                if (role != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("UNAUTHORIZED", "Missing or invalid authentication token"),
                )
            }
        }
    }
}

/** The role carried by the caller's token, or null when unauthenticated. */
fun ApplicationCall.callerRole(): Role? =
    Role.fromClaim(principal<JWTPrincipal>()?.payload?.getClaim(JwtService.SCOPE_CLAIM)?.asString())

/** The caller's user id (`sub`), or null when unauthenticated or the token is break-glass (non-UUID `sub`). */
fun ApplicationCall.callerUserId(): UUID? {
    val sub = principal<JWTPrincipal>()?.payload?.subject ?: return null
    return try {
        UUID.fromString(sub)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** The caller's user id, or `401` if the token carries no real user (e.g. break-glass). */
fun ApplicationCall.requireUserId(): UUID =
    callerUserId()
        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "This endpoint needs a real user token, not a break-glass one")

/** Throws `403` unless the caller holds an `admin`-scoped token. Assumes the route is already behind [AUTH_JWT]. */
fun ApplicationCall.requireAdminScope() {
    if (callerRole() != Role.ADMIN) {
        throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "This endpoint requires an admin-scoped token")
    }
}
