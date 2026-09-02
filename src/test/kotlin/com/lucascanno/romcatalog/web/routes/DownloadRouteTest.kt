package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.AppDependencies
import com.lucascanno.romcatalog.configureApp
import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.storage.MinioStorageClient
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestAuth
import com.lucascanno.romcatalog.support.TestInfra
import com.lucascanno.romcatalog.web.dto.DownloadResponse
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadRouteTest : IntegrationTestBase() {

    @Test
    fun `returns a presigned url for an existing rom whose object is present`() = testApplication {
        installTestApp()
        val bytes = ByteArray(1234) { 7 }
        val hash = randomHash()
        val key = "GBA/$hash.bin"
        storage.putObject(key, bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")
        val rom = runBlocking {
            romRepository.create(newRom(system = GameSystem.GBA, sizeBytes = bytes.size.toLong(), hash = hash, storageKey = key))
        }

        val body: DownloadResponse = jsonClient().get("/roms/${rom.id}/download").body()

        assertTrue(body.url.contains(key))
        assertEquals(hash, body.hash)
        assertEquals(bytes.size.toLong(), body.sizeBytes)
        assertTrue(Instant.parse(body.expiresAt).isAfter(Instant.now()))
    }

    @Test
    fun `download url points at the configured public endpoint host`() = testApplication {
        // rebuild the app with a storage client that signs against a different host
        application {
            configureApp(
                AppDependencies.of(
                    database = db.database,
                    storage = MinioStorageClient.create(
                        TestInfra.storageConfig().copy(publicEndpoint = "http://storage.public.local:9000")
                    ),
                    authConfig = TestAuth.config,
                )
            )
        }
        val bytes = "z".toByteArray()
        val hash = randomHash()
        val key = "GBA/$hash.bin"
        storage.putObject(key, bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")
        val rom = runBlocking { romRepository.create(newRom(hash = hash, storageKey = key)) }

        val body: DownloadResponse = jsonClient().get("/roms/${rom.id}/download").body()

        assertTrue(body.url.startsWith("http://storage.public.local:9000/"), "unexpected url: ${body.url}")
    }

    @Test
    fun `404 when the rom does not exist`() = testApplication {
        installTestApp()
        val response = jsonClient().get("/roms/${UUID.randomUUID()}/download")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("ROM_NOT_FOUND", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `503 when the rom exists but its object is missing from the bucket`() = testApplication {
        installTestApp()
        val rom = runBlocking {
            romRepository.create(newRom(storageKey = "GBA/never-uploaded.bin"))
        }

        val response = jsonClient().get("/roms/${rom.id}/download")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("STORAGE_UNAVAILABLE", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun `GET roms by id is not shadowed by the download subroute`() = testApplication {
        installTestApp()
        val rom = runBlocking { romRepository.create(newRom()) }

        assertEquals(HttpStatusCode.OK, jsonClient().get("/roms/${rom.id}").status)
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/roms/${UUID.randomUUID()}").status)
    }
}
