package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.auth.PasswordHasher
import com.lucascanno.romcatalog.domain.NewUser
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.domain.User
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.repository.UserRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserServiceTest {

    private val users = mockk<UserRepository>()
    private val hasher = PasswordHasher(cost = 4)
    private val service = UserService(users, hasher)

    private fun user(name: String, role: Role = Role.USER, id: UUID = UUID.randomUUID()): User {
        val now = Instant.now()
        return User(id, name, hasher.hash("whatever1"), role, false, now, now)
    }

    @Test
    fun `create makes a user that must change credentials`() = runBlocking {
        coEvery { users.findByUsername("amigo") } returns null
        val slot = slot<NewUser>()
        coEvery { users.create(capture(slot)) } answers { user("amigo").copy(mustChangeCredentials = true) }

        val created = service.create("amigo", "temp-pass-123", null)

        assertTrue(created.mustChangeCredentials)
        assertEquals(Role.USER, slot.captured.role)
        assertTrue(slot.captured.mustChangeCredentials)
        assertTrue(hasher.verify("temp-pass-123", slot.captured.passwordHash))
    }

    @Test
    fun `create honours an explicit admin role`() = runBlocking {
        coEvery { users.findByUsername("boss") } returns null
        val slot = slot<NewUser>()
        coEvery { users.create(capture(slot)) } answers { user("boss", Role.ADMIN) }

        service.create("boss", "temp-pass-123", "admin")

        assertEquals(Role.ADMIN, slot.captured.role)
    }

    @Test
    fun `create rejects a duplicate username`() = runBlocking {
        coEvery { users.findByUsername("taken") } returns user("taken")

        val ex = assertFailsWith<ApiException> { service.create("taken", "temp-pass-123", null) }
        assertEquals("USERNAME_TAKEN", ex.code)
    }

    @Test
    fun `create rejects a bad username or weak password`() = runBlocking {
        assertEquals("INVALID_USERNAME", assertFailsWith<ApiException> { service.create("no spaces!", "temp-pass-123", null) }.code)
        coEvery { users.findByUsername("ok_name") } returns null
        assertEquals("WEAK_PASSWORD", assertFailsWith<ApiException> { service.create("ok_name", "short", null) }.code)
    }

    @Test
    fun `create rejects an unknown role`() = runBlocking {
        val ex = assertFailsWith<ApiException> { service.create("ok_name", "temp-pass-123", "superuser") }
        assertEquals("INVALID_ROLE", ex.code)
    }

    @Test
    fun `delete refuses the last admin`() = runBlocking {
        val admin = user("root", Role.ADMIN)
        coEvery { users.findById(admin.id) } returns admin
        coEvery { users.countByRole(Role.ADMIN) } returns 1

        val ex = assertFailsWith<ApiException> { service.delete(admin.id, actingUserId = UUID.randomUUID()) }
        assertEquals("LAST_ADMIN", ex.code)
    }

    @Test
    fun `delete allows a non-last admin`() = runBlocking {
        val admin = user("second-admin", Role.ADMIN)
        coEvery { users.findById(admin.id) } returns admin
        coEvery { users.countByRole(Role.ADMIN) } returns 2
        coEvery { users.delete(admin.id) } returns true

        service.delete(admin.id, actingUserId = UUID.randomUUID())

        coVerify { users.delete(admin.id) }
    }

    @Test
    fun `delete refuses self-deletion`() = runBlocking {
        val me = user("me", Role.ADMIN)
        coEvery { users.findById(me.id) } returns me

        val ex = assertFailsWith<ApiException> { service.delete(me.id, actingUserId = me.id) }
        assertEquals("CANNOT_DELETE_SELF", ex.code)
    }

    @Test
    fun `delete of an unknown user is 404`() = runBlocking {
        val id = UUID.randomUUID()
        coEvery { users.findById(id) } returns null

        val ex = assertFailsWith<ApiException> { service.delete(id, actingUserId = UUID.randomUUID()) }
        assertEquals("USER_NOT_FOUND", ex.code)
    }

    @Test
    fun `reset-password sets a new hash and re-arms must-change`() = runBlocking {
        val u = user("friend")
        val slot = slot<String>()
        coEvery { users.findById(u.id) } returns u andThen u.copy(mustChangeCredentials = true)
        coEvery { users.updateCredentials(u.id, null, capture(slot), true) } returns true

        service.resetPassword(u.id, "fresh-temp-pass")

        assertTrue(hasher.verify("fresh-temp-pass", slot.captured))
        coVerify { users.updateCredentials(u.id, null, any(), true) }
    }
}
