package com.lucascanno.romcatalog.web

import com.lucascanno.romcatalog.support.IntegrationTestBase
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** The default test [com.lucascanno.romcatalog.config.CorsConfig] allows `http://localhost:4200`. */
class CorsTest : IntegrationTestBase() {

    @Test
    fun `preflight from an allowed origin echoes Access-Control-Allow-Origin`() = testApplication {
        installTestApp()

        val res = jsonClient(token = null).options("/admin/users") {
            header(HttpHeaders.Origin, "http://localhost:4200")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }

        assertEquals("http://localhost:4200", res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `preflight from a foreign origin is refused`() = testApplication {
        installTestApp()

        val res = jsonClient(token = null).options("/admin/users") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }

        assertEquals(null, res.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
