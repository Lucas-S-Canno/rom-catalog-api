package com.lucascanno.romcatalog.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfigTest {

    @Test
    fun `applies defaults when environment is empty`() {
        val config = AppConfig.fromEnv { null }

        assertEquals(8080, config.port)
        assertEquals("0.0.0.0", config.host)
        assertEquals("jdbc:postgresql://localhost:5432/romcatalog", config.database.url)
        assertEquals("romcatalog", config.database.user)
        assertEquals("http://localhost:9000", config.storage.endpoint)
        assertEquals("roms", config.storage.bucket)
        assertEquals(900, config.download.urlTtlSeconds)
        // CORS is off unless CORS_ALLOWED_ORIGINS is set — a localhost default here
        // would make requireProductionReady() reject an otherwise-fine prod config.
        assertEquals(emptyList(), config.cors.allowedOrigins)
        assertEquals(false, config.cors.anyHost)
    }

    @Test
    fun `environment overrides win`() {
        val env = mapOf(
            "PORT" to "9999",
            "DB_URL" to "jdbc:postgresql://db:5432/prod",
            "DB_PASSWORD" to "s3cr3t",
            "MINIO_ENDPOINT" to "http://minio:9000",
            "MINIO_BUCKET" to "prod-roms",
            "DOWNLOAD_URL_TTL_SECONDS" to "60",
        )
        val config = AppConfig.fromEnv { env[it] }

        assertEquals(9999, config.port)
        assertEquals("jdbc:postgresql://db:5432/prod", config.database.url)
        assertEquals("s3cr3t", config.database.password)
        assertEquals("http://minio:9000", config.storage.endpoint)
        assertEquals("prod-roms", config.storage.bucket)
        assertEquals(60, config.download.urlTtlSeconds)
    }

    @Test
    fun `public endpoint falls back to the internal endpoint`() {
        val config = AppConfig.fromEnv { key -> if (key == "MINIO_ENDPOINT") "http://internal:9000" else null }

        assertEquals("http://internal:9000", config.storage.endpoint)
        assertEquals("http://internal:9000", config.storage.publicEndpoint)
    }

    @Test
    fun `public endpoint can be set independently`() {
        val env = mapOf(
            "MINIO_ENDPOINT" to "http://internal:9000",
            "MINIO_PUBLIC_ENDPOINT" to "https://storage.lucascanno.com.br",
        )
        val config = AppConfig.fromEnv { env[it] }

        assertEquals("http://internal:9000", config.storage.endpoint)
        assertEquals("https://storage.lucascanno.com.br", config.storage.publicEndpoint)
    }

    @Test
    fun `defaults to the local environment`() {
        assertEquals("local", AppConfig.fromEnv { null }.appEnv)
        assertEquals(false, AppConfig.fromEnv { null }.isProduction)
        assertEquals(true, AppConfig.fromEnv { if (it == "APP_ENV") "production" else null }.isProduction)
    }

    @Test
    fun `requireProductionReady rejects leftover dev defaults and lists them all`() {
        val config = AppConfig.fromEnv { if (it == "APP_ENV") "production" else null }

        val ex = assertFailsWith<ConfigurationException> { config.requireProductionReady() }

        assertTrue(ex.message!!.contains("JWT_SECRET"))
        assertTrue(ex.message!!.contains("DB_URL"))
        assertTrue(ex.message!!.contains("DB_PASSWORD"))
        assertTrue(ex.message!!.contains("MINIO_ACCESS_KEY"))
        assertTrue(ex.message!!.contains("MINIO_PUBLIC_ENDPOINT"))
    }

    private val realProdEnv = mapOf(
        "APP_ENV" to "production",
        "JWT_SECRET" to "a-real-long-random-secret-value",
        "DB_URL" to "jdbc:postgresql://postgres.prod:5432/romcatalog",
        "DB_PASSWORD" to "a-real-db-password",
        "MINIO_ENDPOINT" to "http://minio.prod:9000",
        "MINIO_PUBLIC_ENDPOINT" to "https://storage.lucascanno.com.br",
        "MINIO_ACCESS_KEY" to "prod-access-key",
        "MINIO_SECRET_KEY" to "prod-secret-key",
    )

    @Test
    fun `requireProductionReady passes with real values and CORS unset`() {
        // Regression: an unset CORS_ALLOWED_ORIGINS must NOT block a prod boot.
        AppConfig.fromEnv { realProdEnv[it] }.requireProductionReady() // must not throw
    }

    @Test
    fun `requireProductionReady passes with an explicit non-local CORS origin`() {
        val env = realProdEnv + ("CORS_ALLOWED_ORIGINS" to "https://rom-catalog-admin.lucascanno.com.br")
        AppConfig.fromEnv { env[it] }.requireProductionReady() // must not throw
    }

    @Test
    fun `requireProductionReady rejects an explicit localhost CORS origin`() {
        val env = realProdEnv + ("CORS_ALLOWED_ORIGINS" to "http://localhost:4200")
        val ex = assertFailsWith<ConfigurationException> { AppConfig.fromEnv { env[it] }.requireProductionReady() }
        assertTrue(ex.message!!.contains("CORS_ALLOWED_ORIGINS"))
    }

    @Test
    fun `requireProductionReady rejects a wildcard CORS origin`() {
        val env = realProdEnv + ("CORS_ALLOWED_ORIGINS" to "*")
        val ex = assertFailsWith<ConfigurationException> { AppConfig.fromEnv { env[it] }.requireProductionReady() }
        assertTrue(ex.message!!.contains("CORS_ALLOWED_ORIGINS"))
    }

    @Test
    fun `CORS_ALLOWED_ORIGINS parses a comma-separated list`() {
        val config = AppConfig.fromEnv {
            if (it == "CORS_ALLOWED_ORIGINS") "https://a.example, https://b.example" else null
        }
        assertEquals(listOf("https://a.example", "https://b.example"), config.cors.allowedOrigins)
        assertEquals(false, config.cors.anyHost)
    }
}
