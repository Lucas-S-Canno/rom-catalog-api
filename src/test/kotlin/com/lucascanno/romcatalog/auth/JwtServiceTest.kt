package com.lucascanno.romcatalog.auth

import com.auth0.jwt.exceptions.IncorrectClaimException
import com.auth0.jwt.exceptions.SignatureVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.domain.Role
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtServiceTest {

    private val config = AuthConfig(
        jwtSecret = "unit-test-secret-abcdefghijklmnop",
        jwtIssuer = "iss-under-test",
        jwtAudience = "aud-under-test",
    )
    private val service = JwtService(config)

    @Test
    fun `a freshly issued token passes the matching verifier`() {
        val token = service.issueBreakGlass(Role.USER, Duration.ofMinutes(5))

        val decoded = service.verifier().verify(token)

        assertEquals("user", decoded.getClaim("scope").asString())
        assertEquals("user", decoded.getClaim("role").asString())
        assertEquals("iss-under-test", decoded.issuer)
        assertTrue(decoded.audience.contains("aud-under-test"))
        assertEquals("break-glass-user", decoded.subject)
        assertNotNull(decoded.expiresAt)
    }

    @Test
    fun `admin scope survives the round-trip`() {
        val token = service.issueBreakGlass(Role.ADMIN, Duration.ofMinutes(5))

        assertEquals("admin", service.verifier().verify(token).getClaim("scope").asString())
    }

    @Test
    fun `an expired token is rejected`() {
        val token = service.issueBreakGlass(Role.USER, Duration.ofSeconds(-5))

        val ex = assertFailsWith<TokenExpiredException> { service.verifier().verify(token) }
        assertTrue(ex.message?.contains("expired", ignoreCase = true) == true)
    }

    @Test
    fun `a token signed with another secret is rejected`() {
        val foreign = JwtService(config.copy(jwtSecret = "some-other-secret-0000000000")).issueBreakGlass(Role.USER, Duration.ofMinutes(5))

        val ex = assertFailsWith<SignatureVerificationException> { service.verifier().verify(foreign) }
        assertNotNull(ex)
    }

    @Test
    fun `a token from another issuer is rejected`() {
        val foreign = JwtService(config.copy(jwtIssuer = "evil-issuer")).issueBreakGlass(Role.USER, Duration.ofMinutes(5))

        val ex = assertFailsWith<IncorrectClaimException> { service.verifier().verify(foreign) }
        assertEquals("iss", ex.claimName)
    }
}
