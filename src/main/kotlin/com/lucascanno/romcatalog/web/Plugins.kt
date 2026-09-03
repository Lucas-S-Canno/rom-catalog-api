package com.lucascanno.romcatalog.web

import com.lucascanno.romcatalog.config.CorsConfig
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.error.StorageUnavailableException
import com.lucascanno.romcatalog.web.dto.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        )
    }
}

fun Application.configureMonitoring() {
    install(DefaultHeaders)
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        filter { call -> !call.request.path().startsWith("/health") }
    }
}

/**
 * Browser CORS for the admin panel. No-op when [CorsConfig.allowedOrigins] is empty
 * and [CorsConfig.anyHost] is false — the mobile app never needs CORS.
 */
fun Application.configureCors(config: CorsConfig) {
    if (!config.anyHost && config.allowedOrigins.isEmpty()) return
    install(CORS) {
        if (config.anyHost) {
            anyHost()
        } else {
            config.allowedOrigins.forEach { origin ->
                val scheme = origin.substringBefore("://", missingDelimiterValue = "https")
                val host = origin.substringAfter("://")
                allowHost(host, schemes = listOf(scheme))
            }
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowNonSimpleContentTypes = true
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.code, cause.message))
        }
        exception<StorageUnavailableException> { call, cause ->
            call.application.log.warn("Storage unavailable: {}", cause.message)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("STORAGE_UNAVAILABLE", "The ROM storage is currently unavailable"),
            )
        }
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_BODY", cause.message ?: "Malformed request body"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error on ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "Unexpected error"),
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("NOT_FOUND", "Resource not found"))
        }
    }
}
