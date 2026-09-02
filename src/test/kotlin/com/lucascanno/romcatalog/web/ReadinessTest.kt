package com.lucascanno.romcatalog.web

import com.lucascanno.romcatalog.AppDependencies
import com.lucascanno.romcatalog.config.StorageConfig
import com.lucascanno.romcatalog.configureApp
import com.lucascanno.romcatalog.storage.MinioStorageClient
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestAuth
import com.lucascanno.romcatalog.web.dto.ReadinessResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadinessTest : IntegrationTestBase() {

    @Test
    fun `ready returns 200 with every check UP when the infra is healthy`() = testApplication {
        installTestApp()

        val response = jsonClient(token = null).get("/health/ready")
        val body: ReadinessResponse = response.body()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UP", body.status)
        assertEquals("UP", body.checks.getValue("db").status)
        assertEquals("UP", body.checks.getValue("storage").status)
    }

    @Test
    fun `ready returns 503 with storage DOWN when MinIO is unreachable`() = testApplication {
        val deadStorage = MinioStorageClient.create(
            StorageConfig(
                endpoint = "http://127.0.0.1:1",
                publicEndpoint = "http://127.0.0.1:1",
                accessKey = "x",
                secretKey = "y",
                bucket = "roms",
                timeoutMs = 1_000,
            )
        )
        application {
            configureApp(
                AppDependencies.of(
                    database = db.database,
                    storage = deadStorage,
                    authConfig = TestAuth.config,
                )
            )
        }

        val response = jsonClient(token = null).get("/health/ready")
        val body: ReadinessResponse = response.body()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("DOWN", body.status)
        assertEquals("DOWN", body.checks.getValue("storage").status)
        assertEquals("UP", body.checks.getValue("db").status)
    }

    @Test
    fun `readiness does not require a token`() = testApplication {
        installTestApp()

        assertEquals(HttpStatusCode.OK, jsonClient(token = null).get("/health/ready").status)
    }
}
