package com.lucascanno.romcatalog.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.domain.User
import java.time.Duration
import java.time.Instant

/**
 * Single source of truth for how tokens are minted and verified. Shared by the
 * login flow, the Ktor auth plugin, and the `issueToken` CLI so they can't drift.
 *
 * Claims: `sub` (user id, or a synthetic id for break-glass), `username`, `role`,
 * and `scope` (== role, kept for the auth plugin's `requireAdminScope`).
 */
class JwtService(private val config: AuthConfig) {

    private val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    /** Token for a real logged-in user. */
    fun issue(user: User, ttl: Duration = config.tokenTtl): String =
        issue(subject = user.id.toString(), username = user.username, role = user.role, ttl = ttl)

    /** Break-glass token — no backing DB user (used by the `issueToken` CLI). */
    fun issueBreakGlass(role: Role, ttl: Duration, subject: String = "break-glass-${role.claim}"): String =
        issue(subject = subject, username = subject, role = role, ttl = ttl)

    private fun issue(subject: String, username: String, role: Role, ttl: Duration): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(subject)
            .withClaim(USERNAME_CLAIM, username)
            .withClaim(ROLE_CLAIM, role.claim)
            .withClaim(SCOPE_CLAIM, role.claim)
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
        const val ROLE_CLAIM = "role"
        const val USERNAME_CLAIM = "username"
    }
}
