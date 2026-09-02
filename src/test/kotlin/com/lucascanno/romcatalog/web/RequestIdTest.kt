package com.lucascanno.romcatalog.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.lucascanno.romcatalog.support.IntegrationTestBase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequestIdTest : IntegrationTestBase() {

    @Test
    fun `a request id is generated and echoed when the caller sends none`() = testApplication {
        installTestApp()

        val response = jsonClient().get("/roms")
        val id = response.headers["X-Request-Id"]

        assertNotNull(id)
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `an incoming request id is preserved in the response`() = testApplication {
        installTestApp()

        val response = jsonClient().get("/roms") { header("X-Request-Id", "trace-abc-123") }

        assertEquals("trace-abc-123", response.headers["X-Request-Id"])
    }

    @Test
    fun `the request id reaches the logging MDC`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            testApplication {
                installTestApp()
                jsonClient().get("/roms") { header("X-Request-Id", "mdc-trace-777") }
            }
        } finally {
            root.detachAppender(appender)
        }

        val sawIt = appender.list.any { it.mdcPropertyMap["requestId"] == "mdc-trace-777" }
        assertTrue(sawIt, "expected requestId=mdc-trace-777 in some log event's MDC")
    }
}
