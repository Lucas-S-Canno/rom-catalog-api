package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.ingest.Hashing
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestAuth
import com.lucascanno.romcatalog.support.TestInfra
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import com.lucascanno.romcatalog.web.dto.RomDto
import com.lucascanno.romcatalog.web.dto.UpdateRomRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminRomsRouteTest : IntegrationTestBase() {

    private fun multipart(bytes: ByteArray, filename: String) = MultiPartFormDataContent(
        formData {
            append(
                "file",
                bytes,
                Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$filename\"") },
            )
        }
    )

    private fun romRowCount(): Int =
        TestInfra.query("SELECT count(*) FROM roms") { rs -> rs.getInt(1) }.first()

    private suspend fun ApplicationTestBuilder.uploadRom(bytes: ByteArray, filename: String): RomDto =
        jsonClient(TestAuth.adminToken).post("/admin/roms") { setBody(multipart(bytes, filename)) }.body()

    // ── DELETE ─────────────────────────────────────────────────────────────

    @Test
    fun `delete removes the row and the stored object`() = testApplication {
        installTestApp()
        val bytes = "gba bytes".toByteArray()
        val fp = Hashing.fingerprint(bytes)
        val rom = uploadRom(bytes, "Zelda.gba")
        assertTrue(storage.objectExists("GBA/${fp.sha256}.gba"))

        val res = jsonClient(TestAuth.adminToken).delete("/admin/roms/${rom.id}")

        assertEquals(HttpStatusCode.NoContent, res.status)
        assertEquals(0, romRowCount())
        assertFalse(storage.objectExists("GBA/${fp.sha256}.gba"))
    }

    @Test
    fun `deleting an unknown rom is 404`() = testApplication {
        installTestApp()
        val res = jsonClient(TestAuth.adminToken).delete("/admin/roms/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("ROM_NOT_FOUND", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `deleting with a non-uuid id is 400`() = testApplication {
        installTestApp()
        val res = jsonClient(TestAuth.adminToken).delete("/admin/roms/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("INVALID_PATH_PARAM", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `delete requires an admin token`() = testApplication {
        installTestApp()
        val rom = uploadRom("x".toByteArray(), "g.gba")

        val anon = jsonClient(token = null).delete("/admin/roms/${rom.id}")
        val asUser = jsonClient(TestAuth.userToken).delete("/admin/roms/${rom.id}")

        assertEquals(HttpStatusCode.Unauthorized, anon.status)
        assertEquals(HttpStatusCode.Forbidden, asUser.status)
        assertEquals(1, romRowCount())
    }

    // ── PATCH ──────────────────────────────────────────────────────────────

    @Test
    fun `patch renames a rom`() = testApplication {
        installTestApp()
        val rom = uploadRom("x".toByteArray(), "old.gba")

        val res = jsonClient(TestAuth.adminToken).patch("/admin/roms/${rom.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateRomRequest(name = "Novo Nome"))
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Novo Nome", res.body<RomDto>().name)
    }

    @Test
    fun `patch with a blank coverUrl clears the cover`() = testApplication {
        installTestApp()
        val rom = uploadRom("x".toByteArray(), "c.gba")
        jsonClient(TestAuth.adminToken).patch("/admin/roms/${rom.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateRomRequest(coverUrl = "https://img.example/c.png"))
        }

        val cleared = jsonClient(TestAuth.adminToken).patch("/admin/roms/${rom.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateRomRequest(coverUrl = ""))
        }

        assertEquals(HttpStatusCode.OK, cleared.status)
        assertNull(cleared.body<RomDto>().coverUrl)
    }

    @Test
    fun `patch with nothing to change is 400`() = testApplication {
        installTestApp()
        val rom = uploadRom("x".toByteArray(), "n.gba")

        val res = jsonClient(TestAuth.adminToken).patch("/admin/roms/${rom.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateRomRequest())
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("NOTHING_TO_CHANGE", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `patch on an unknown rom is 404`() = testApplication {
        installTestApp()
        val res = jsonClient(TestAuth.adminToken).patch("/admin/roms/${UUID.randomUUID()}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateRomRequest(name = "x"))
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("ROM_NOT_FOUND", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `patch requires an admin token`() = testApplication {
        installTestApp()
        val rom = uploadRom("x".toByteArray(), "p.gba")

        val asUser = jsonClient(TestAuth.userToken).patch("/admin/roms/${rom.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateRomRequest(name = "nope"))
        }

        assertEquals(HttpStatusCode.Forbidden, asUser.status)
    }
}
