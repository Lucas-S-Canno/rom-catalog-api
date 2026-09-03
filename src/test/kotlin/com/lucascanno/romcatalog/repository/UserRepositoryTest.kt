package com.lucascanno.romcatalog.repository

import com.lucascanno.romcatalog.domain.NewUser
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.support.IntegrationTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRepositoryTest : IntegrationTestBase() {

    private fun newUser(name: String, role: Role = Role.USER, mustChange: Boolean = false) =
        NewUser(name, "hash-for-$name", role, mustChange)

    @Test
    fun `create then findById and findByUsername round-trip`() = runBlocking {
        val created = userRepository.create(newUser("lucas", Role.ADMIN, mustChange = true))

        assertEquals(created, userRepository.findById(created.id))
        assertEquals(created, userRepository.findByUsername("lucas"))
        assertTrue(created.mustChangeCredentials)
        assertEquals(Role.ADMIN, created.role)
    }

    @Test
    fun `findByUsername is case-sensitive`() = runBlocking {
        userRepository.create(newUser("Lucas"))

        assertNull(userRepository.findByUsername("lucas"))
        assertNull(userRepository.findByUsername("LUCAS"))
        assertEquals("Lucas", userRepository.findByUsername("Lucas")?.username)
    }

    @Test
    fun `duplicate username violates the unique constraint`() = runBlocking {
        userRepository.create(newUser("dup"))

        val ex = assertFailsWith<ExposedSQLException> { userRepository.create(newUser("dup")) }
        assertTrue(ex.message?.contains("users_username_key") == true || ex.message?.contains("unique") == true)
    }

    @Test
    fun `countByRole counts only that role`() = runBlocking {
        userRepository.create(newUser("a", Role.ADMIN))
        userRepository.create(newUser("b", Role.USER))
        userRepository.create(newUser("c", Role.USER))

        assertEquals(1, userRepository.countByRole(Role.ADMIN))
        assertEquals(2, userRepository.countByRole(Role.USER))
    }

    @Test
    fun `updateCredentials writes only the given fields and bumps updated_at`() = runBlocking {
        val u = userRepository.create(newUser("temp", mustChange = true))
        Thread.sleep(5)

        val changed = userRepository.updateCredentials(
            u.id,
            newUsername = "final",
            newPasswordHash = "new-hash",
            mustChangeCredentials = false,
        )

        assertTrue(changed)
        val after = userRepository.findById(u.id)!!
        assertEquals("final", after.username)
        assertEquals("new-hash", after.passwordHash)
        assertTrue(!after.mustChangeCredentials)
        assertTrue(after.updatedAt.isAfter(u.updatedAt))
        assertEquals(u.createdAt, after.createdAt)
    }

    @Test
    fun `updateCredentials on an unknown id returns false`() = runBlocking {
        assertTrue(!userRepository.updateCredentials(UUID.randomUUID(), newUsername = "x"))
    }

    @Test
    fun `delete removes the row`() = runBlocking {
        val u = userRepository.create(newUser("gone"))

        assertTrue(userRepository.delete(u.id))
        assertNull(userRepository.findById(u.id))
        assertTrue(!userRepository.delete(u.id))
    }

    @Test
    fun `list is ordered by created_at`() = runBlocking {
        userRepository.create(newUser("first"))
        Thread.sleep(5)
        userRepository.create(newUser("second"))

        assertEquals(listOf("first", "second"), userRepository.list().map { it.username })
    }
}
