package com.lucascanno.romcatalog.web

import com.lucascanno.romcatalog.web.routes.healthRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Liveness must stand on its own: this test wires ONLY serialization + status
 * pages + the health route — no database, no storage. If it ever needs a
 * container to pass, a dependency leaked into the health path.
 */
class HealthCheckTest {

    @Test
    fun `GET health returns 200 UP as json`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { healthRoutes() }
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"UP"}""", response.bodyAsText())
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
    }

    @Test
    fun `unknown route yields the standard error envelope`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { healthRoutes() }
        }

        val response = client.get("/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":{"code":"NOT_FOUND","message":"Resource not found"}}""", response.bodyAsText())
    }
}
