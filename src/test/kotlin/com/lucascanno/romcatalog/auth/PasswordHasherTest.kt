package com.lucascanno.romcatalog.auth

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHasherTest {

    // low cost so the tests stay fast
    private val hasher = PasswordHasher(cost = 4)

    @Test
    fun `hash then verify round-trips`() {
        val hash = hasher.hash("correct horse battery staple")

        assertTrue(hasher.verify("correct horse battery staple", hash))
        assertFalse(hasher.verify("wrong password", hash))
    }

    @Test
    fun `two hashes of the same password differ (salted)`() {
        val a = hasher.hash("same")
        val b = hasher.hash("same")

        assertTrue(a != b)
        assertTrue(hasher.verify("same", a))
        assertTrue(hasher.verify("same", b))
    }

    @Test
    fun `hash string is a bcrypt string`() {
        assertTrue(hasher.hash("x").startsWith("\$2"))
    }

    @Test
    fun `an invalid cost is rejected`() {
        assertFailsWith<IllegalArgumentException> { PasswordHasher(cost = 3) }
        assertFailsWith<IllegalArgumentException> { PasswordHasher(cost = 32) }
    }
}
