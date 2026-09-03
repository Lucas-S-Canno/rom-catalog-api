package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestAuth
import com.lucascanno.romcatalog.web.dto.ChangeCredentialsRequest
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import com.lucascanno.romcatalog.web.dto.LoginRequest
import com.lucascanno.romcatalog.web.dto.LoginResponse
import com.lucascanno.romcatalog.web.dto.MeResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRoutesTest : IntegrationTestBase() {

    private suspend fun ApplicationTestBuilder.login(username: String, password: String): String =
        jsonClient(token = null).post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body<LoginResponse>().token

    @Test
    fun `login with correct credentials returns a usable token`() = testApplication {
        installTestApp()
        seedUser("lucas", "supersecret1", Role.ADMIN)

        val res = jsonClient(token = null).post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("lucas", "supersecret1"))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body: LoginResponse = res.body()
        assertEquals("Bearer", body.tokenType)
        assertEquals("admin", body.role)
        assertTrue(!body.mustChangeCredentials)
        assertEquals(24 * 3600L, body.expiresInSeconds)

        assertEquals(HttpStatusCode.OK, jsonClient(body.token).get("/roms").status)
    }

    @Test
    fun `wrong password is 401 INVALID_CREDENTIALS`() = testApplication {
        installTestApp()
        seedUser("lucas", "supersecret1")

        val res = jsonClient(token = null).post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("lucas", "WRONG"))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("INVALID_CREDENTIALS", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `unknown user gives the exact same 401`() = testApplication {
        installTestApp()

        val res = jsonClient(token = null).post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("ghost", "whatever12"))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("INVALID_CREDENTIALS", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `GET auth me returns the caller`() = testApplication {
        installTestApp()
        seedUser("friend", "pass12345", Role.USER)

        val me: MeResponse = jsonClient(login("friend", "pass12345")).get("/auth/me").body()

        assertEquals("friend", me.username)
        assertEquals("user", me.role)
    }

    @Test
    fun `first-login flow — mustChangeCredentials then change then cleared`() = testApplication {
        installTestApp()
        seedUser("amigo", "temp-pass-1", Role.USER, mustChangeCredentials = true)

        val first: LoginResponse = jsonClient(token = null).post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("amigo", "temp-pass-1"))
        }.body()
        assertTrue(first.mustChangeCredentials)

        val changed: LoginResponse = jsonClient(first.token).post("/auth/change-credentials") {
            contentType(ContentType.Application.Json)
            setBody(ChangeCredentialsRequest("temp-pass-1", newUsername = "amigo2", newPassword = "my-own-pass-9"))
        }.body()
        assertTrue(!changed.mustChangeCredentials)

        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient(token = null).post("/auth/login") {
                contentType(ContentType.Application.Json); setBody(LoginRequest("amigo", "temp-pass-1"))
            }.status,
        )
        val relogin: MeResponse = jsonClient(login("amigo2", "my-own-pass-9")).get("/auth/me").body()
        assertEquals("amigo2", relogin.username)
    }

    @Test
    fun `change-credentials rejects a wrong current password`() = testApplication {
        installTestApp()
        seedUser("u", "right-pass-1")

        val res = jsonClient(login("u", "right-pass-1")).post("/auth/change-credentials") {
            contentType(ContentType.Application.Json)
            setBody(ChangeCredentialsRequest("WRONG", newPassword = "another-pass-1"))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("INVALID_CREDENTIALS", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `auth me needs a real user token — a break-glass token is 401`() = testApplication {
        installTestApp()

        val res = jsonClient(TestAuth.adminToken).get("/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
