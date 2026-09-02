package com.lucascanno.romcatalog.web

import com.lucascanno.romcatalog.auth.JwtService
import com.lucascanno.romcatalog.auth.Scope
import com.lucascanno.romcatalog.config.AuthConfig
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

/** Name of the single JWT provider. Use with `authenticate(AUTH_JWT) { ... }`. */
const val AUTH_JWT = "auth-jwt"

/**
 * Installs one JWT provider that accepts any signature-valid, non-expired token
 * whose `scope` is `user` or `admin`. Scope-level authorization (admin-only) is
 * enforced per route via [requireAdminScope] so a valid-but-underprivileged token
 * gets `403`, not `401`.
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
                val scope = Scope.fromClaim(credential.payload.getClaim(JwtService.SCOPE_CLAIM).asString())
                if (scope != null) JWTPrincipal(credential.payload) else null
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

/** The scope carried by the caller's token, or null when unauthenticated. */
fun ApplicationCall.callerScope(): Scope? =
    Scope.fromClaim(principal<JWTPrincipal>()?.payload?.getClaim(JwtService.SCOPE_CLAIM)?.asString())

/** Throws `403` unless the caller holds an `admin`-scoped token. Assumes the route is already behind [AUTH_JWT]. */
fun ApplicationCall.requireAdminScope() {
    if (callerScope() != Scope.ADMIN) {
        throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "This endpoint requires an admin-scoped token")
    }
}
