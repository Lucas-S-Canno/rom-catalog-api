package com.lucascanno.romcatalog.storage

import com.lucascanno.romcatalog.config.StorageConfig
import com.lucascanno.romcatalog.error.StorageUnavailableException
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestInfra
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageClientIT : IntegrationTestBase() {

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun get(url: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `objectExists is true after upload and false otherwise`() {
        val bytes = "fake-rom-bytes".toByteArray()
        storage.putObject("GBA/it-exists.bin", bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")

        assertTrue(storage.objectExists("GBA/it-exists.bin"))
        assertFalse(storage.objectExists("GBA/missing.bin"))
    }

    @Test
    fun `presigned url downloads the exact bytes`() {
        val bytes = ByteArray(5000) { (it % 256).toByte() }
        storage.putObject("NDS/payload.bin", bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")

        val url = storage.presignedGetUrl("NDS/payload.bin", Duration.ofMinutes(5))
        val response = get(url)

        assertEquals(200, response.statusCode())
        assertTrue(bytes.contentEquals(response.body()))
    }

    @Test
    fun `presigned url is signed with the public endpoint host`() {
        val publicClient = MinioStorageClient.create(
            TestInfra.storageConfig().copy(publicEndpoint = "http://storage.example.local:9000")
        )
        val bytes = "x".toByteArray()
        storage.putObject("GBA/hosttest.bin", bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")

        val url = publicClient.presignedGetUrl("GBA/hosttest.bin", Duration.ofMinutes(5))

        assertTrue(url.startsWith("http://storage.example.local:9000/"), "unexpected url: $url")
    }

    @Test
    fun `presigned url enforces its expiry`() {
        val bytes = "expiring".toByteArray()
        storage.putObject("GBA/ttl.bin", bytes.inputStream(), bytes.size.toLong(), "application/octet-stream")

        // A comfortably long TTL is usable.
        val goodUrl = storage.presignedGetUrl("GBA/ttl.bin", Duration.ofMinutes(10))
        assertEquals(200, get(goodUrl).statusCode())

        // A 1s TTL is rejected shortly after (container clock skew only makes this stricter).
        val shortUrl = storage.presignedGetUrl("GBA/ttl.bin", Duration.ofSeconds(1))
        Thread.sleep(2500)
        assertEquals(403, get(shortUrl).statusCode())
    }

    @Test
    fun `unreachable storage raises StorageUnavailableException`() {
        val deadClient = MinioStorageClient.create(
            StorageConfig(
                endpoint = "http://127.0.0.1:1",
                publicEndpoint = "http://127.0.0.1:1",
                accessKey = "x",
                secretKey = "y",
                bucket = "roms",
            )
        )

        val ex = assertFailsWith<StorageUnavailableException> { deadClient.objectExists("whatever") }
        assertTrue(ex.message?.isNotBlank() == true)
    }
}
