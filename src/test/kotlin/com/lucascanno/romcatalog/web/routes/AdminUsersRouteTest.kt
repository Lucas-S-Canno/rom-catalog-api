package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestAuth
import com.lucascanno.romcatalog.web.dto.CreateUserRequest
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import com.lucascanno.romcatalog.web.dto.LoginRequest
import com.lucascanno.romcatalog.web.dto.LoginResponse
import com.lucascanno.romcatalog.web.dto.ResetPasswordRequest
import com.lucascanno.romcatalog.web.dto.UserDto
import io.ktor.client.call.body
import io.ktor.client.request.delete
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

class AdminUsersRouteTest : IntegrationTestBase() {

    private suspend fun ApplicationTestBuilder.login(username: String, password: String): String =
        jsonClient(token = null).post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body<LoginResponse>().token

    @Test
    fun `admin creates a user that must change credentials`() = testApplication {
        installTestApp()
        seedUser("root", "admin-pass-1", Role.ADMIN)
        val admin = login("root", "admin-pass-1")

        val res = jsonClient(admin).post("/admin/users") {
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest("amigo", "temp-pass-abc"))
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val dto: UserDto = res.body()
        assertEquals("amigo", dto.username)
        assertEquals("user", dto.role)
        assertTrue(dto.mustChangeCredentials)

        // the new user can log in with the temp password
        assertTrue(login("amigo", "temp-pass-abc").isNotBlank())
    }

    @Test
    fun `a user-scoped token cannot manage users`() = testApplication {
        installTestApp()
        seedUser("plain", "user-pass-1", Role.USER)
        val user = login("plain", "user-pass-1")

        assertEquals(HttpStatusCode.Forbidden, jsonClient(user).get("/admin/users").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            jsonClient(user).post("/admin/users") {
                contentType(ContentType.Application.Json); setBody(CreateUserRequest("x", "temp-pass-abc"))
            }.status,
        )
    }

    @Test
    fun `no token cannot manage users`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient(token = null).get("/admin/users").status)
    }

    @Test
    fun `a break-glass admin token CAN manage users`() = testApplication {
        installTestApp()

        val res = jsonClient(TestAuth.adminToken).post("/admin/users") {
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest("bootstrapped", "temp-pass-abc"))
        }
        assertEquals(HttpStatusCode.Created, res.status)
    }

    @Test
    fun `duplicate username is 409`() = testApplication {
        installTestApp()
        seedUser("root", "admin-pass-1", Role.ADMIN)
        seedUser("taken", "whatever1", Role.USER)
        val admin = login("root", "admin-pass-1")

        val res = jsonClient(admin).post("/admin/users") {
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest("taken", "temp-pass-abc"))
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("USERNAME_TAKEN", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `list, reset-password and delete`() = testApplication {
        installTestApp()
        seedUser("root", "admin-pass-1", Role.ADMIN)
        val victim = seedUser("victim", "old-pass-1", Role.USER)
        val admin = login("root", "admin-pass-1")

        val list: List<UserDto> = jsonClient(admin).get("/admin/users").body()
        assertEquals(setOf("root", "victim"), list.map { it.username }.toSet())

        val reset = jsonClient(admin).post("/admin/users/${victim.id}/reset-password") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest("new-temp-pass-1"))
        }
        assertEquals(HttpStatusCode.OK, reset.status)
        assertTrue(reset.body<UserDto>().mustChangeCredentials)
        assertTrue(login("victim", "new-temp-pass-1").isNotBlank())

        assertEquals(HttpStatusCode.NoContent, jsonClient(admin).delete("/admin/users/${victim.id}").status)
        assertEquals(1, jsonClient(admin).get("/admin/users").body<List<UserDto>>().size)
    }

    @Test
    fun `deleting the last admin is 409, deleting yourself is 409`() = testApplication {
        installTestApp()
        val root = seedUser("root", "admin-pass-1", Role.ADMIN)
        val admin = login("root", "admin-pass-1")

        val lastAdmin = jsonClient(admin).delete("/admin/users/${root.id}")
        assertEquals(HttpStatusCode.Conflict, lastAdmin.status)
        // "root" is both the last admin and the caller — either guard is acceptable
        assertTrue(lastAdmin.body<ErrorResponse>().error.code in setOf("LAST_ADMIN", "CANNOT_DELETE_SELF"))
    }

    @Test
    fun `deleting an unknown user is 404`() = testApplication {
        installTestApp()
        seedUser("root", "admin-pass-1", Role.ADMIN)
        val admin = login("root", "admin-pass-1")

        val res = jsonClient(admin).delete("/admin/users/${java.util.UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("USER_NOT_FOUND", res.body<ErrorResponse>().error.code)
    }
}
