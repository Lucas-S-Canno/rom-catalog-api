package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.web.dto.AddFavoriteRequest
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import com.lucascanno.romcatalog.web.dto.FavoriteDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FavoriteRoutesTest : IntegrationTestBase() {

    @Test
    fun `POST favorites creates a favorite and returns 201`() = testApplication {
        installTestApp()
        val rom = runBlocking { romRepository.create(newRom(name = "Metroid")) }
        val client = jsonClient()

        val response = client.post("/favorites") {
            contentType(ContentType.Application.Json)
            setBody(AddFavoriteRequest(rom.id.toString()))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val dto: FavoriteDto = response.body()
        assertEquals(rom.id.toString(), dto.romId)
        assertEquals("Metroid", dto.rom.name)
    }

    @Test
    fun `POST favorites is idempotent and returns 200 on repeat`() = testApplication {
        installTestApp()
        val rom = runBlocking { romRepository.create(newRom()) }
        val client = jsonClient()
        fun add() = runBlocking {
            client.post("/favorites") {
                contentType(ContentType.Application.Json)
                setBody(AddFavoriteRequest(rom.id.toString()))
            }
        }

        assertEquals(HttpStatusCode.Created, add().status)
        assertEquals(HttpStatusCode.OK, add().status)

        val list: List<FavoriteDto> = client.get("/favorites").body()
        assertEquals(1, list.size)
    }

    @Test
    fun `POST favorites with unknown rom returns 404`() = testApplication {
        installTestApp()
        val response = jsonClient().post("/favorites") {
            contentType(ContentType.Application.Json)
            setBody(AddFavoriteRequest(UUID.randomUUID().toString()))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("ROM_NOT_FOUND", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `POST favorites with no body is rejected as a client error`() = testApplication {
        installTestApp()
        val response = jsonClient().post("/favorites")

        // Ktor answers 415 for a bodyless request to a JSON endpoint; either way it must be 4xx.
        assertTrue(response.status.value in 400..499, "expected a 4xx, got ${response.status}")
    }

    @Test
    fun `POST favorites with a malformed json body returns 400`() = testApplication {
        installTestApp()
        val response = jsonClient().post("/favorites") {
            contentType(ContentType.Application.Json)
            setBody("{ not json ")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST favorites with a non-uuid romId returns 400`() = testApplication {
        installTestApp()
        val response = jsonClient().post("/favorites") {
            contentType(ContentType.Application.Json)
            setBody(AddFavoriteRequest("not-a-uuid"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_BODY", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `GET favorites returns the joined rom, newest first`() = testApplication {
        installTestApp()
        val client = jsonClient()
        val (a, b) = runBlocking {
            val a = romRepository.create(newRom(name = "A"))
            val b = romRepository.create(newRom(name = "B"))
            favoriteRepository.add(a.id)
            Thread.sleep(5)
            favoriteRepository.add(b.id)
            a to b
        }

        val list: List<FavoriteDto> = client.get("/favorites").body()

        assertEquals(listOf(b.id.toString(), a.id.toString()), list.map { it.romId })
        assertTrue(list.all { it.rom.name.isNotBlank() })
    }

    @Test
    fun `DELETE favorites removes the favorite and answers 204`() = testApplication {
        installTestApp()
        val client = jsonClient()
        val rom = runBlocking {
            val r = romRepository.create(newRom())
            favoriteRepository.add(r.id)
            r
        }

        assertEquals(HttpStatusCode.NoContent, client.delete("/favorites/${rom.id}").status)
        assertEquals(0, client.get("/favorites").body<List<FavoriteDto>>().size)
    }

    @Test
    fun `DELETE favorites is idempotent for a non-favorite rom`() = testApplication {
        installTestApp()
        val response = jsonClient().delete("/favorites/${UUID.randomUUID()}")

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE favorites with a malformed id returns 400`() = testApplication {
        installTestApp()
        val response = jsonClient().delete("/favorites/not-a-uuid")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_PATH_PARAM", response.body<ErrorResponse>().error.code)
    }
}
