package com.lucascanno.romcatalog.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorHandlingTest {

    @Test
    fun `an unhandled exception becomes a 500 envelope and never leaks internals`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { get("/boom") { throw IllegalStateException("secret internal detail 42") } }
        }

        val response = client.get("/boom")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("""{"error":{"code":"INTERNAL_ERROR","message":"Unexpected error"}}""", body)
        assertFalse(body.contains("secret internal detail 42"))
        assertFalse(body.contains("IllegalStateException"))
        assertFalse(body.contains("com.lucascanno"))
    }

    @Test
    fun `the exception is written to the log with its stacktrace`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            testApplication {
                application {
                    configureSerialization()
                    configureStatusPages()
                    routing { get("/boom") { throw IllegalStateException("kaboom-marker-99") } }
                }
                client.get("/boom")
            }
        } finally {
            root.detachAppender(appender)
        }

        val logged = appender.list.any { event ->
            val t = event.throwableProxy
            t != null && (t.className.contains("IllegalStateException") || t.message?.contains("kaboom-marker-99") == true)
        }
        assertTrue(logged, "expected the exception (with stacktrace) in the logs")
    }

    @Test
    fun `an unknown route returns the standard 404 envelope`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { get("/known") { } }
        }

        val response = client.get("/nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":{"code":"NOT_FOUND","message":"Resource not found"}}""", response.bodyAsText())
    }
}
