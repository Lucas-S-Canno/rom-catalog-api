package com.lucascanno.romcatalog.support

import com.lucascanno.romcatalog.auth.JwtService
import com.lucascanno.romcatalog.auth.Scope
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
    )

    private val service = JwtService(config)

    val userToken: String = service.issue(Scope.USER, Duration.ofHours(1))
    val adminToken: String = service.issue(Scope.ADMIN, Duration.ofHours(1))

    fun token(scope: Scope, ttl: Duration = Duration.ofHours(1)): String = service.issue(scope, ttl)

    fun expiredToken(scope: Scope = Scope.USER): String = service.issue(scope, Duration.ofSeconds(-30))

    fun tokenSignedWith(secret: String, scope: Scope = Scope.USER): String =
        JwtService(config.copy(jwtSecret = secret)).issue(scope, Duration.ofHours(1))

    fun tokenWithIssuer(issuer: String, scope: Scope = Scope.USER): String =
        JwtService(config.copy(jwtIssuer = issuer)).issue(scope, Duration.ofHours(1))
}

fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}
