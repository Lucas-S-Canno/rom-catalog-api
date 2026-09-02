package com.lucascanno.romcatalog.web.dto

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializationSmokeTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `health response round-trips`() {
        val encoded = json.encodeToString(HealthResponse.serializer(), HealthResponse())
        assertEquals("""{"status":"UP"}""", encoded)
        assertEquals(HealthResponse("UP"), json.decodeFromString(HealthResponse.serializer(), encoded))
    }

    @Test
    fun `error envelope has the standard shape`() {
        val encoded = json.encodeToString(
            ErrorResponse.serializer(),
            ErrorResponse("ROM_NOT_FOUND", "ROM 'x' not found"),
        )
        assertEquals("""{"error":{"code":"ROM_NOT_FOUND","message":"ROM 'x' not found"}}""", encoded)
    }

    @Test
    fun `null coverUrl is omitted from the json`() {
        val dto = RomDto(
            id = "id-1",
            name = "Demo",
            system = "GBA",
            sizeBytes = 10,
            hash = "abc",
            coverUrl = null,
            createdAt = "2026-01-01T00:00:00Z",
        )
        val encoded = json.encodeToString(RomDto.serializer(), dto)
        assertFalse(encoded.contains("coverUrl"), "expected coverUrl to be absent, was: $encoded")
    }

    @Test
    fun `present coverUrl is kept`() {
        val dto = RomDto("id-1", "Demo", "GBA", 10, "abc", "http://cover", "2026-01-01T00:00:00Z")
        val encoded = json.encodeToString(RomDto.serializer(), dto)
        assertTrue(encoded.contains(""""coverUrl":"http://cover""""))
    }

    @Test
    fun `page envelope carries pagination metadata`() {
        val page = PageDto(items = listOf(1, 2, 3), page = 2, size = 3, total = 42)
        val encoded = json.encodeToString(PageDto.serializer(Int.serializer()), page)
        assertEquals("""{"items":[1,2,3],"page":2,"size":3,"total":42}""", encoded)
    }
}
