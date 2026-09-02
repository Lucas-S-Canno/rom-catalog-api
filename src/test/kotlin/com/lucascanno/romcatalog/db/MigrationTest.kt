package com.lucascanno.romcatalog.db

import com.lucascanno.romcatalog.config.DatabaseConfig
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestInfra
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest : IntegrationTestBase() {

    private fun columnsOf(table: String): Map<String, String> =
        TestInfra.query(
            """
            SELECT column_name, data_type FROM information_schema.columns
            WHERE table_name = '$table'
            """.trimIndent()
        ) { rs -> rs.getString(1) to rs.getString(2) }.toMap()

    @Test
    fun `roms table has the expected columns`() {
        val cols = columnsOf("roms")
        assertEquals("uuid", cols["id"])
        assertEquals("text", cols["name"])
        assertEquals("character varying", cols["system"])
        assertEquals("bigint", cols["size_bytes"])
        assertEquals("text", cols["hash"])
        assertEquals("text", cols["storage_key"])
        assertEquals("text", cols["cover_url"])
        assertTrue(cols["created_at"]?.startsWith("timestamp") == true)
    }

    @Test
    fun `favorites table has the expected columns`() {
        val cols = columnsOf("favorites")
        assertEquals("uuid", cols["id"])
        assertEquals("uuid", cols["rom_id"])
        assertTrue(cols["created_at"]?.startsWith("timestamp") == true)
    }

    @Test
    fun `constraints from the migrations are in place`() {
        val constraints = TestInfra.query(
            """
            SELECT conname FROM pg_constraint
            WHERE conrelid IN ('roms'::regclass, 'favorites'::regclass)
            """.trimIndent()
        ) { rs -> rs.getString(1) }.toSet()

        assertTrue("roms_system_check" in constraints, "missing system CHECK; got $constraints")
        assertTrue("roms_hash_unique" in constraints, "missing hash UNIQUE; got $constraints")
        assertTrue("roms_size_nonneg" in constraints, "missing size CHECK; got $constraints")
        assertTrue("favorites_rom_fk" in constraints, "missing FK; got $constraints")
        assertTrue("favorites_rom_unique" in constraints, "missing rom_id UNIQUE; got $constraints")
    }

    @Test
    fun `flyway migration is idempotent`() {
        val before = successfulMigrations()

        DatabaseFactory.init(
            DatabaseConfig(
                url = TestInfra.postgres.jdbcUrl,
                user = TestInfra.postgres.username,
                password = TestInfra.postgres.password,
            )
        ).use { /* second run must be a clean no-op */ }

        assertEquals(before, successfulMigrations())
    }

    private fun successfulMigrations(): Int =
        TestInfra.query("SELECT count(*) FROM flyway_schema_history WHERE success = true") { rs -> rs.getInt(1) }
            .firstOrNull() ?: 0
}
