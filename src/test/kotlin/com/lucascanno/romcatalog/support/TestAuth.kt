package com.lucascanno.romcatalog.support

import com.lucascanno.romcatalog.auth.JwtService
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.config.AuthConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.time.Duration

/** Fixed auth config + helpers used by every HTTP test. */
object TestAuth {

    val config = AuthConfig(
        jwtSecret = "test-secret-please-do-not-use-anywhere-real",
        jwtIssuer = "rom-catalog-api-test",
        jwtAudience = "rom-catalog-app-test",
        jwtRealm = "rom-catalog-test",
        tokenTtlHours = 24,
        bcryptCost = 4,
    )

    private val service = JwtService(config)

    val userToken: String = service.issueBreakGlass(Role.USER, Duration.ofHours(1))
    val adminToken: String = service.issueBreakGlass(Role.ADMIN, Duration.ofHours(1))

    fun token(scope: Role, ttl: Duration = Duration.ofHours(1)): String = service.issueBreakGlass(scope, ttl)

    fun expiredToken(scope: Role = Role.USER): String = service.issueBreakGlass(scope, Duration.ofSeconds(-30))

    fun tokenSignedWith(secret: String, scope: Role = Role.USER): String =
        JwtService(config.copy(jwtSecret = secret)).issueBreakGlass(scope, Duration.ofHours(1))

    fun tokenWithIssuer(issuer: String, scope: Role = Role.USER): String =
        JwtService(config.copy(jwtIssuer = issuer)).issueBreakGlass(scope, Duration.ofHours(1))
}

fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}
