package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestAuth
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import com.lucascanno.romcatalog.web.dto.RomDto
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RomCoverRouteTest : IntegrationTestBase() {

    private fun multipartFile(bytes: ByteArray, filename: String) = MultiPartFormDataContent(
        formData {
            append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$filename\"") })
        }
    )

    private suspend fun ApplicationTestBuilder.uploadRom(): RomDto =
        jsonClient(TestAuth.adminToken).post("/admin/roms") {
            setBody(multipartFile("gba bytes".toByteArray(), "g.gba"))
        }.body()

    private suspend fun ApplicationTestBuilder.uploadCover(romId: String, bytes: ByteArray) =
        jsonClient(TestAuth.adminToken).post("/admin/roms/$romId/cover") {
            setBody(multipartFile(bytes, "cover.jpg"))
        }

    // ── upload ─────────────────────────────────────────────────────────────

    @Test
    fun `upload stores the object and points coverUrl at the API route`() = testApplication {
        installTestApp()
        val rom = uploadRom()

        val res = uploadCover(rom.id, "fake jpeg bytes".toByteArray())

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("/roms/${rom.id}/cover", res.body<RomDto>().coverUrl)
        assertTrue(storage.objectExists("covers/${rom.id}.jpg"))
    }

    @Test
    fun `upload without a file part is 400`() = testApplication {
        installTestApp()
        val rom = uploadRom()

        val res = jsonClient(TestAuth.adminToken).post("/admin/roms/${rom.id}/cover") {
            setBody(MultiPartFormDataContent(formData { append("name", "orphan") }))
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("MISSING_FILE", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `upload on an unknown rom is 404`() = testApplication {
        installTestApp()
        val res = uploadCover(UUID.randomUUID().toString(), "x".toByteArray())
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("ROM_NOT_FOUND", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `upload requires an admin token`() = testApplication {
        installTestApp()
        val rom = uploadRom()

        val anon = jsonClient(token = null).post("/admin/roms/${rom.id}/cover") { setBody(multipartFile("x".toByteArray(), "c.jpg")) }
        val asUser = jsonClient(TestAuth.userToken).post("/admin/roms/${rom.id}/cover") { setBody(multipartFile("x".toByteArray(), "c.jpg")) }

        assertEquals(HttpStatusCode.Unauthorized, anon.status)
        assertEquals(HttpStatusCode.Forbidden, asUser.status)
        assertFalse(storage.objectExists("covers/${rom.id}.jpg"))
    }

    // ── get ────────────────────────────────────────────────────────────────

    @Test
    fun `get streams back the exact bytes uploaded`() = testApplication {
        installTestApp()
        val rom = uploadRom()
        val cover = "fake jpeg bytes for the cover".toByteArray()
        uploadCover(rom.id, cover)

        val res = jsonClient(TestAuth.userToken).get("/roms/${rom.id}/cover")

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("image/jpeg", res.headers[HttpHeaders.ContentType])
        assertTrue(cover.contentEquals(res.body<ByteArray>()))
    }

    @Test
    fun `get is 404 when the rom has no stored cover`() = testApplication {
        installTestApp()
        val rom = uploadRom()

        val res = jsonClient(TestAuth.userToken).get("/roms/${rom.id}/cover")

        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("COVER_NOT_FOUND", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `get is 404 when the rom itself does not exist`() = testApplication {
        installTestApp()
        val res = jsonClient(TestAuth.userToken).get("/roms/${UUID.randomUUID()}/cover")
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("ROM_NOT_FOUND", res.body<ErrorResponse>().error.code)
    }

    // ── delete ─────────────────────────────────────────────────────────────

    @Test
    fun `delete removes the object and clears coverUrl`() = testApplication {
        installTestApp()
        val rom = uploadRom()
        uploadCover(rom.id, "x".toByteArray())

        val res = jsonClient(TestAuth.adminToken).delete("/admin/roms/${rom.id}/cover")

        assertEquals(HttpStatusCode.OK, res.status)
        assertNull(res.body<RomDto>().coverUrl)
        assertFalse(storage.objectExists("covers/${rom.id}.jpg"))
        assertEquals(HttpStatusCode.NotFound, jsonClient(TestAuth.userToken).get("/roms/${rom.id}/cover").status)
    }

    @Test
    fun `delete cover requires an admin token`() = testApplication {
        installTestApp()
        val rom = uploadRom()
        uploadCover(rom.id, "x".toByteArray())

        val asUser = jsonClient(TestAuth.userToken).delete("/admin/roms/${rom.id}/cover")

        assertEquals(HttpStatusCode.Forbidden, asUser.status)
        assertTrue(storage.objectExists("covers/${rom.id}.jpg"))
    }

    @Test
    fun `deleting the whole rom also removes its cover object`() = testApplication {
        installTestApp()
        val rom = uploadRom()
        uploadCover(rom.id, "x".toByteArray())
        assertTrue(storage.objectExists("covers/${rom.id}.jpg"))

        val res = jsonClient(TestAuth.adminToken).delete("/admin/roms/${rom.id}")

        assertEquals(HttpStatusCode.NoContent, res.status)
        assertFalse(storage.objectExists("covers/${rom.id}.jpg"))
    }
}
