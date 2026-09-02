package com.lucascanno.romcatalog.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthServiceTest {

    @Test
    fun `all probes passing means UP`() = runBlocking {
        val service = HealthService(dbCheck = {}, storageCheck = { true })

        val readiness = service.readiness()

        assertEquals("UP", readiness.status)
        assertEquals("UP", readiness.checks.getValue("db").status)
        assertEquals("UP", readiness.checks.getValue("storage").status)
    }

    @Test
    fun `a throwing db probe makes the whole thing DOWN but keeps storage UP`() = runBlocking {
        val service = HealthService(
            dbCheck = { throw IllegalStateException("connection refused") },
            storageCheck = { true },
        )

        val readiness = service.readiness()

        assertEquals("DOWN", readiness.status)
        assertEquals("DOWN", readiness.checks.getValue("db").status)
        assertEquals("connection refused", readiness.checks.getValue("db").detail)
        assertEquals("UP", readiness.checks.getValue("storage").status)
    }

    @Test
    fun `a storage probe returning false is DOWN`() = runBlocking {
        val service = HealthService(dbCheck = {}, storageCheck = { false })

        val readiness = service.readiness()

        assertEquals("DOWN", readiness.status)
        assertEquals("DOWN", readiness.checks.getValue("storage").status)
    }

    @Test
    fun `a hanging probe is reported as a timeout`() = runBlocking {
        val service = HealthService(
            dbCheck = { delay(10_000) },
            storageCheck = { true },
            timeout = Duration.ofMillis(100),
        )

        val readiness = service.readiness()

        assertEquals("DOWN", readiness.checks.getValue("db").status)
        assertTrue(readiness.checks.getValue("db").detail!!.contains("timed out"))
    }
}
