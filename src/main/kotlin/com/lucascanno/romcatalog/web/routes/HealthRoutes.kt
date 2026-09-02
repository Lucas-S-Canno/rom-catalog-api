package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.service.HealthService
import com.lucascanno.romcatalog.web.dto.HealthResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Liveness only. This handler must never touch the database or the object store —
 * that is what readiness is for.
 */
fun Route.healthRoutes() {
    get("/health") {
        call.respond(HealthResponse(status = "UP"))
    }
}

/**
 * Readiness: `200` only when every dependency probe passes, otherwise `503` with
 * the per-check breakdown. Public (no auth) so orchestrators can poll it.
 */
fun Route.readinessRoutes(healthService: HealthService) {
    get("/health/ready") {
        val readiness = healthService.readiness()
        val code = if (readiness.status == HealthService.UP) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(code, readiness)
    }
}
