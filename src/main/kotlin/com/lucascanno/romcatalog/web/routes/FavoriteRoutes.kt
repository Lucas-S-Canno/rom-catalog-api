package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.error.invalidBody
import com.lucascanno.romcatalog.service.FavoriteService
import com.lucascanno.romcatalog.web.dto.AddFavoriteRequest
import com.lucascanno.romcatalog.web.uuidPathParam
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.favoriteRoutes(favoriteService: FavoriteService) {
    route("/favorites") {
        get {
            call.respond(favoriteService.list())
        }

        post {
            val body = call.receive<AddFavoriteRequest>()
            val romId = try {
                UUID.fromString(body.romId)
            } catch (_: IllegalArgumentException) {
                throw invalidBody("'romId' must be a UUID")
            }
            val result = favoriteService.add(romId)
            call.respond(
                if (result.created) HttpStatusCode.Created else HttpStatusCode.OK,
                result.favorite,
            )
        }

        delete("/{romId}") {
            favoriteService.remove(call.uuidPathParam("romId"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
