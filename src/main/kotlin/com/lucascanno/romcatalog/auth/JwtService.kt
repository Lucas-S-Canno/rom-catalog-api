package com.lucascanno.romcatalog.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.lucascanno.romcatalog.config.AuthConfig
import java.time.Duration
import java.time.Instant

/** Known token scopes. `admin` implies every `user` capability. */
enum class Scope(val claim: String) {
    USER("user"),
    ADMIN("admin");

    companion object {
        fun fromClaim(value: String?): Scope? = entries.firstOrNull { it.claim == value }
    }
}

/**
 * Single source of truth for how tokens are minted and verified. Shared by the
 * Ktor auth plugin and the `issueToken` CLI so the two can never drift.
 */
class JwtService(private val config: AuthConfig) {

    private val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    fun issue(scope: Scope, ttl: Duration, subject: String = "rom-catalog-${scope.claim}"): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(subject)
            .withClaim(SCOPE_CLAIM, scope.claim)
            .withIssuedAt(now)
            .withExpiresAt(now.plus(ttl))
            .sign(algorithm)
    }

    fun verifier(): JWTVerifier =
        JWT.require(algorithm)
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .build()

    companion object {
        const val SCOPE_CLAIM = "scope"
    }
}
