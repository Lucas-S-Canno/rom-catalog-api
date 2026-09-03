package com.lucascanno.romcatalog.auth

import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.support.IntegrationTestBase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminBootstrapTest : IntegrationTestBase() {

    private val hasher = PasswordHasher(cost = 4)

    private fun config(username: String?, password: String?) = AuthConfig(
        jwtSecret = "bootstrap-test-secret-0123456789",
        adminUsername = username,
        adminBootstrapPassword = password,
        bcryptCost = 4,
    )

    @Test
    fun `creates the admin when none exists and env is set`() = runBlocking {
        AdminBootstrap.run(userRepository, hasher, config("lucas", "a-real-bootstrap-pw"))

        val admin = userRepository.findByUsername("lucas")!!
        assertEquals(Role.ADMIN, admin.role)
        assertTrue(!admin.mustChangeCredentials)
        assertTrue(hasher.verify("a-real-bootstrap-pw", admin.passwordHash))
    }

    @Test
    fun `is idempotent — does nothing when an admin already exists`() = runBlocking {
        seedUser("existing-admin", "whatever1", Role.ADMIN)

        AdminBootstrap.run(userRepository, hasher, config("lucas", "a-real-bootstrap-pw"))

        assertEquals(null, userRepository.findByUsername("lucas"))
        assertEquals(1, userRepository.countByRole(Role.ADMIN))
    }

    @Test
    fun `does nothing when the env vars are not set`() = runBlocking {
        AdminBootstrap.run(userRepository, hasher, config(null, null))

        assertEquals(0, userRepository.countByRole(Role.ADMIN))
    }

    @Test
    fun `does not clobber a non-admin user with the same name`() = runBlocking {
        seedUser("lucas", "whatever1", Role.USER)

        AdminBootstrap.run(userRepository, hasher, config("lucas", "a-real-bootstrap-pw"))

        assertEquals(Role.USER, userRepository.findByUsername("lucas")!!.role)
        assertEquals(0, userRepository.countByRole(Role.ADMIN))
    }
}
