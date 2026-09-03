package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.service.AuthService
import com.lucascanno.romcatalog.web.dto.ChangeCredentialsRequest
import com.lucascanno.romcatalog.web.dto.LoginRequest
import com.lucascanno.romcatalog.web.dto.LoginResponse
import com.lucascanno.romcatalog.web.dto.toMe
import com.lucascanno.romcatalog.web.requireUserId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Public: `POST /auth/login`. Mount OUTSIDE `authenticate(AUTH_JWT)`. */
fun Route.authLoginRoute(authService: AuthService) {
    post("/auth/login") {
        val body = call.receive<LoginRequest>()
        val session = authService.login(body.username.trim(), body.password)
        call.respond(
            LoginResponse(
                token = session.token,
                expiresInSeconds = session.expiresInSeconds,
                role = session.user.role.claim,
                mustChangeCredentials = session.user.mustChangeCredentials,
            )
        )
    }
}

/** Authenticated self-service. Mount INSIDE `authenticate(AUTH_JWT)`. */
fun Route.authSelfRoutes(authService: AuthService) {
    route("/auth") {
        get("/me") {
            call.respond(authService.me(call.requireUserId()).toMe())
        }
        post("/change-credentials") {
            val body = call.receive<ChangeCredentialsRequest>()
            val session = authService.changeCredentials(
                userId = call.requireUserId(),
                currentPassword = body.currentPassword,
                newUsername = body.newUsername?.trim(),
                newPassword = body.newPassword,
            )
            call.respond(
                LoginResponse(
                    token = session.token,
                    expiresInSeconds = session.expiresInSeconds,
                    role = session.user.role.claim,
                    mustChangeCredentials = session.user.mustChangeCredentials,
                )
            )
        }
    }
}
