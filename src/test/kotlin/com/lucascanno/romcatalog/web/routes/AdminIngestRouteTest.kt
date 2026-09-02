package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.ingest.Hashing
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestAuth
import com.lucascanno.romcatalog.support.TestInfra
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import com.lucascanno.romcatalog.web.dto.RegisterRomRequest
import com.lucascanno.romcatalog.web.dto.RomDto
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminIngestRouteTest : IntegrationTestBase() {

    private fun multipart(
        fileBytes: ByteArray,
        filename: String,
        name: String? = null,
        system: String? = null,
    ) = MultiPartFormDataContent(
        formData {
            name?.let { append("name", it) }
            system?.let { append("system", it) }
            append(
                "file",
                fileBytes,
                Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$filename\"") },
            )
        }
    )

    private fun romRowCount(): Int =
        TestInfra.query("SELECT count(*) FROM roms") { rs -> rs.getInt(1) }.first()

    // ── multipart mode ─────────────────────────────────────────────────────

    @Test
    fun `multipart upload creates the rom and stores the object`() = testApplication {
        installTestApp()
        val bytes = "fake gba rom".toByteArray()
        val fp = Hashing.fingerprint(bytes)

        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            setBody(multipart(bytes, "Minish Cap.gba", name = "Minish Cap"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val dto: RomDto = response.body()
        assertEquals("Minish Cap", dto.name)
        assertEquals("GBA", dto.system)
        assertEquals(fp.sha256, dto.hash)
        assertEquals(bytes.size.toLong(), dto.sizeBytes)
        assertTrue(storage.objectExists("GBA/${fp.sha256}.gba"))
        assertEquals(1, romRowCount())
    }

    @Test
    fun `system is inferred from the file extension when the field is absent`() = testApplication {
        installTestApp()
        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            setBody(multipart("nds bytes".toByteArray(), "Mario Kart.nds"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("NDS", response.body<RomDto>().system)
    }

    @Test
    fun `re-uploading the same file is a 409 and writes nothing new`() = testApplication {
        installTestApp()
        val bytes = "dup rom".toByteArray()
        val fp = Hashing.fingerprint(bytes)
        val client = jsonClient(TestAuth.adminToken)

        val first = client.post("/admin/roms") { setBody(multipart(bytes, "game.gba")) }
        val second = client.post("/admin/roms") { setBody(multipart(bytes, "game.gba")) }

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals(fp.sha256, second.body<RomDto>().hash)
        assertEquals(1, romRowCount())
    }

    @Test
    fun `an explicit invalid system field is a 400`() = testApplication {
        installTestApp()
        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            setBody(multipart("x".toByteArray(), "game.gba", system = "PSX"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_SYSTEM", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `an unrecognised extension with no system field is a 400`() = testApplication {
        installTestApp()
        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            setBody(multipart("x".toByteArray(), "notes.txt"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("UNKNOWN_SYSTEM", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `multipart without a file part is a 400`() = testApplication {
        installTestApp()
        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            setBody(MultiPartFormDataContent(formData { append("name", "orphan") }))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("MISSING_FILE", response.body<ErrorResponse>().error.code)
    }

    // ── json mode (object already in the bucket) ───────────────────────────

    @Test
    fun `json registration of a pre-uploaded object succeeds`() = testApplication {
        installTestApp()
        val bytes = "prestaged 3ds rom".toByteArray()
        val fp = Hashing.fingerprint(bytes)
        val key = "3DS/${fp.sha256}.3ds"
        storage.putObject(key, bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")

        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRomRequest("Prestaged", "3DS", fp.sha256, fp.sizeBytes, key))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(key, TestInfra.query("SELECT storage_key FROM roms") { it.getString(1) }.first())
    }

    @Test
    fun `json registration with a wrong hash is a 422`() = testApplication {
        installTestApp()
        val bytes = "real bytes".toByteArray()
        val key = "GBA/staged.gba"
        storage.putObject(key, bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")

        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRomRequest("Wrong", "GBA", "0".repeat(64), bytes.size.toLong(), key))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("HASH_MISMATCH", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `json registration with a wrong size is a 422`() = testApplication {
        installTestApp()
        val bytes = "some bytes".toByteArray()
        val fp = Hashing.fingerprint(bytes)
        val key = "GBA/sized.gba"
        storage.putObject(key, bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")

        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRomRequest("Sized", "GBA", fp.sha256, fp.sizeBytes + 1, key))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("SIZE_MISMATCH", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `json registration for a missing object is a 422`() = testApplication {
        installTestApp()
        val response = jsonClient(TestAuth.adminToken).post("/admin/roms") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRomRequest("Ghost", "GBA", "0".repeat(64), 10, "GBA/nope.gba"))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("OBJECT_NOT_FOUND", response.body<ErrorResponse>().error.code)
    }

    // ── auth ───────────────────────────────────────────────────────────────

    @Test
    fun `ingestion requires an admin token`() = testApplication {
        installTestApp()

        val anon = jsonClient(token = null).post("/admin/roms") { setBody(multipart("x".toByteArray(), "g.gba")) }
        val asUser = jsonClient(TestAuth.userToken).post("/admin/roms") { setBody(multipart("x".toByteArray(), "g.gba")) }

        assertEquals(HttpStatusCode.Unauthorized, anon.status)
        assertEquals(HttpStatusCode.Forbidden, asUser.status)
        assertEquals(0, romRowCount())
    }
}
