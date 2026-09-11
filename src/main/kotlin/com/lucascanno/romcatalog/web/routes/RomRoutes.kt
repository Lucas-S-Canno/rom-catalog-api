package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.service.DownloadService
import com.lucascanno.romcatalog.service.RomService
import com.lucascanno.romcatalog.web.intQueryParam
import com.lucascanno.romcatalog.web.uuidPathParam
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.romRoutes(romService: RomService, downloadService: DownloadService) {
    route("/roms") {
        get {
            val system = call.request.queryParameters["system"]?.let { raw ->
                GameSystem.fromApi(raw)
                    ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_SYSTEM", "Unknown system '$raw'")
            }
            val page = call.intQueryParam("page", default = 0)
            val size = call.intQueryParam("size", default = RomService.DEFAULT_PAGE_SIZE)
            call.respond(romService.list(system, page, size))
        }

        get("/{id}") {
            call.respond(romService.getById(call.uuidPathParam("id")))
        }

        get("/{id}/download") {
            call.respond(downloadService.buildFor(call.uuidPathParam("id")))
        }

        // Any logged-in user can view a cover, same as GET /roms/{id} — uploading/removing
        // one stays admin-only (see /admin/roms/{id}/cover).
        get("/{id}/cover") {
            val input = romService.openCover(call.uuidPathParam("id"))
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondOutputStream(ContentType.Image.JPEG) {
                val output = this
                withContext(Dispatchers.IO) {
                    input.use { it.copyTo(output) }
                }
            }
        }
    }
}
