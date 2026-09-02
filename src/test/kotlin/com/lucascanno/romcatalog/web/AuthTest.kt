package com.lucascanno.romcatalog.web

import com.lucascanno.romcatalog.auth.Scope
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestAuth
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest : IntegrationTestBase() {

    // ── unauthenticated → 401 ────────────────────────────────────────────────

    @Test
    fun `protected routes require a token`() = testApplication {
        installTestApp()
        val anon = jsonClient(token = null)

        assertEquals(HttpStatusCode.Unauthorized, anon.get("/roms").status)
        assertEquals(HttpStatusCode.Unauthorized, anon.get("/roms/${UUID.randomUUID()}").status)
        assertEquals(HttpStatusCode.Unauthorized, anon.get("/roms/${UUID.randomUUID()}/download").status)
        assertEquals(HttpStatusCode.Unauthorized, anon.get("/favorites").status)
        assertEquals(HttpStatusCode.Unauthorized, anon.get("/admin/ping").status)
    }

    @Test
    fun `401 uses the standard error envelope`() = testApplication {
        installTestApp()
        val response = jsonClient(token = null).get("/roms")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("UNAUTHORIZED", response.body<ErrorResponse>().error.code)
    }

    // ── malformed / invalid tokens → 401 ────────────────────────────────────

    @Test
    fun `a garbage bearer value is rejected`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient("not-a-jwt").get("/roms").status)
    }

    @Test
    fun `a token with a bad signature is rejected`() = testApplication {
        installTestApp()
        val forged = TestAuth.tokenSignedWith("a-totally-different-secret-999999")
        assertEquals(HttpStatusCode.Unauthorized, jsonClient(forged).get("/roms").status)
    }

    @Test
    fun `an expired token is rejected`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient(TestAuth.expiredToken()).get("/roms").status)
    }

    @Test
    fun `a token from another issuer is rejected`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient(TestAuth.tokenWithIssuer("someone-else")).get("/roms").status)
    }

    // ── valid tokens ───────────────────────────────────────────────────────

    @Test
    fun `a valid user token is accepted on user routes`() = testApplication {
        installTestApp()
        val user = jsonClient(TestAuth.userToken)

        assertEquals(HttpStatusCode.OK, user.get("/roms").status)
        assertEquals(HttpStatusCode.OK, user.get("/favorites").status)
    }

    @Test
    fun `an admin token also works on user routes`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.OK, jsonClient(TestAuth.adminToken).get("/roms").status)
    }

    // ── admin scope ────────────────────────────────────────────────────────

    @Test
    fun `admin route rejects a user-scoped token with 403`() = testApplication {
        installTestApp()
        val response = jsonClient(TestAuth.userToken).get("/admin/ping")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("FORBIDDEN", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `admin route accepts an admin-scoped token`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.OK, jsonClient(TestAuth.token(Scope.ADMIN)).get("/admin/ping").status)
    }

    // ── health stays public ────────────────────────────────────────────────

    @Test
    fun `health is reachable without a token`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.OK, jsonClient(token = null).get("/health").status)
    }
}
