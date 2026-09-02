package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import com.lucascanno.romcatalog.web.dto.PageDto
import com.lucascanno.romcatalog.web.dto.RomDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RomRoutesTest : IntegrationTestBase() {

    @Test
    fun `GET roms returns an empty page when the catalog is empty`() = testApplication {
        installTestApp()
        val page: PageDto<RomDto> = jsonClient().get("/roms").body()

        assertEquals(0, page.total)
        assertTrue(page.items.isEmpty())
        assertEquals(50, page.size)
        assertEquals(0, page.page)
    }

    @Test
    fun `GET roms lists everything and reports the total`() = testApplication {
        installTestApp()
        runBlocking {
            repeat(3) { romRepository.create(newRom(name = "rom-$it")) }
        }

        val page: PageDto<RomDto> = jsonClient().get("/roms").body()

        assertEquals(3, page.total)
        assertEquals(3, page.items.size)
    }

    @Test
    fun `GET roms filters by system`() = testApplication {
        installTestApp()
        runBlocking {
            romRepository.create(newRom(system = GameSystem.GBA))
            romRepository.create(newRom(system = GameSystem.N3DS))
        }

        val page: PageDto<RomDto> = jsonClient().get("/roms?system=GBA").body()

        assertEquals(1, page.total)
        assertEquals("GBA", page.items.single().system)
    }

    @Test
    fun `GET roms rejects an unknown system with 400`() = testApplication {
        installTestApp()
        val response = jsonClient().get("/roms?system=PSX")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_SYSTEM", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `GET roms clamps oversized size and rejects negatives`() = testApplication {
        installTestApp()
        runBlocking { repeat(3) { romRepository.create(newRom()) } }

        val clamped: PageDto<RomDto> = jsonClient().get("/roms?size=9999").body()
        assertEquals(200, clamped.size)

        assertEquals(HttpStatusCode.BadRequest, jsonClient().get("/roms?size=-1").status)
        assertEquals(HttpStatusCode.BadRequest, jsonClient().get("/roms?page=-1").status)
        assertEquals(HttpStatusCode.BadRequest, jsonClient().get("/roms?size=abc").status)
    }

    @Test
    fun `GET roms paginates without overlap across pages`() = testApplication {
        installTestApp()
        runBlocking { repeat(5) { romRepository.create(newRom(name = "rom-$it")) } }
        val client = jsonClient()

        val p0: PageDto<RomDto> = client.get("/roms?page=0&size=2").body()
        val p1: PageDto<RomDto> = client.get("/roms?page=1&size=2").body()
        val p2: PageDto<RomDto> = client.get("/roms?page=2&size=2").body()

        val ids = (p0.items + p1.items + p2.items).map { it.id }
        assertEquals(5, ids.size)
        assertEquals(5, ids.toSet().size)
    }

    @Test
    fun `GET roms by id returns the resource`() = testApplication {
        installTestApp()
        val created = runBlocking { romRepository.create(newRom(name = "Zelda", coverUrl = null)) }

        val dto: RomDto = jsonClient().get("/roms/${created.id}").body()

        assertEquals(created.id.toString(), dto.id)
        assertEquals("Zelda", dto.name)
        assertTrue(dto.hash.isNotBlank())
        assertTrue(dto.createdAt.isNotBlank())
    }

    @Test
    fun `GET roms by unknown id returns the standard 404 envelope`() = testApplication {
        installTestApp()
        val response = jsonClient().get("/roms/${UUID.randomUUID()}")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("ROM_NOT_FOUND", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `GET roms by malformed id returns 400`() = testApplication {
        installTestApp()
        val response = jsonClient().get("/roms/not-a-uuid")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_PATH_PARAM", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `unknown route still yields the standard error envelope`() = testApplication {
        installTestApp()
        val response = jsonClient().get("/nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains(""""code":"NOT_FOUND""""))
    }
}
