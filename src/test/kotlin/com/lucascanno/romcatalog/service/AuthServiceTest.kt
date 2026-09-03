package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.auth.JwtService
import com.lucascanno.romcatalog.auth.PasswordHasher
import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.domain.User
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthServiceTest {

    private val users = mockk<UserRepository>()
    private val hasher = PasswordHasher(cost = 4)
    private val config = AuthConfig(jwtSecret = "auth-service-test-secret-0123456789", tokenTtlHours = 24)
    private val jwt = JwtService(config)
    private val service = AuthService(users, hasher, jwt, config)

    private fun user(username: String, password: String, role: Role = Role.USER, mustChange: Boolean = false): User {
        val now = Instant.now()
        return User(UUID.randomUUID(), username, hasher.hash(password), role, mustChange, now, now)
    }

    @Test
    fun `login returns a session for correct credentials`() = runBlocking {
        val u = user("lucas", "supersecret")
        coEvery { users.findByUsername("lucas") } returns u

        val session = service.login("lucas", "supersecret")

        assertEquals(u.id, session.user.id)
        assertTrue(session.token.isNotBlank())
        assertEquals(24 * 3600L, session.expiresInSeconds)
        assertEquals("lucas", jwt.verifier().verify(session.token).getClaim("username").asString())
    }

    @Test
    fun `login with wrong password is 401 INVALID_CREDENTIALS`() = runBlocking {
        coEvery { users.findByUsername("lucas") } returns user("lucas", "supersecret")

        val ex = assertFailsWith<ApiException> { service.login("lucas", "nope") }
        assertEquals("INVALID_CREDENTIALS", ex.code)
    }

    @Test
    fun `login with unknown user is the SAME 401 INVALID_CREDENTIALS`() = runBlocking {
        coEvery { users.findByUsername("ghost") } returns null

        val ex = assertFailsWith<ApiException> { service.login("ghost", "whatever") }
        assertEquals("INVALID_CREDENTIALS", ex.code)
    }

    @Test
    fun `username lookup is case-sensitive`() = runBlocking {
        coEvery { users.findByUsername("Lucas") } returns null
        coEvery { users.findByUsername("lucas") } returns user("lucas", "supersecret")

        assertFailsWith<ApiException> { service.login("Lucas", "supersecret") }
        assertEquals("lucas", service.login("lucas", "supersecret").user.username)
    }

    @Test
    fun `change-credentials updates the password and clears the must-change flag`() = runBlocking {
        val u = user("temp", "temp1234", mustChange = true)
        coEvery { users.findById(u.id) } returns u andThen u.copy(passwordHash = "x", mustChangeCredentials = false)
        val hashSlot = slot<String>()
        coEvery { users.updateCredentials(u.id, null, capture(hashSlot), false) } returns true

        service.changeCredentials(u.id, currentPassword = "temp1234", newUsername = null, newPassword = "brand-new-pass")

        coVerify { users.updateCredentials(u.id, null, any(), false) }
        assertTrue(hasher.verify("brand-new-pass", hashSlot.captured))
    }

    @Test
    fun `change-credentials rejects a wrong current password`() = runBlocking {
        val u = user("temp", "temp1234")
        coEvery { users.findById(u.id) } returns u

        val ex = assertFailsWith<ApiException> {
            service.changeCredentials(u.id, currentPassword = "WRONG", newUsername = null, newPassword = "brand-new-pass")
        }
        assertEquals("INVALID_CREDENTIALS", ex.code)
    }

    @Test
    fun `change-credentials rejects a weak new password`() = runBlocking {
        val u = user("temp", "temp1234")
        coEvery { users.findById(u.id) } returns u

        val ex = assertFailsWith<ApiException> {
            service.changeCredentials(u.id, currentPassword = "temp1234", newUsername = null, newPassword = "short")
        }
        assertEquals("WEAK_PASSWORD", ex.code)
    }

    @Test
    fun `change-credentials rejects a taken username`() = runBlocking {
        val u = user("temp", "temp1234")
        coEvery { users.findById(u.id) } returns u
        coEvery { users.findByUsername("taken") } returns user("taken", "whatever")

        val ex = assertFailsWith<ApiException> {
            service.changeCredentials(u.id, currentPassword = "temp1234", newUsername = "taken", newPassword = null)
        }
        assertEquals("USERNAME_TAKEN", ex.code)
    }

    @Test
    fun `change-credentials with nothing to change is 400`() = runBlocking {
        val u = user("temp", "temp1234")
        coEvery { users.findById(u.id) } returns u

        val ex = assertFailsWith<ApiException> {
            service.changeCredentials(u.id, currentPassword = "temp1234", newUsername = u.username, newPassword = null)
        }
        assertEquals("NOTHING_TO_CHANGE", ex.code)
    }
}
