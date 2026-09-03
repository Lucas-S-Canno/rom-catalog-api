package com.lucascanno.romcatalog.web.routes

import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.ingest.SystemDetector
import com.lucascanno.romcatalog.service.IngestionService
import com.lucascanno.romcatalog.service.RomService
import com.lucascanno.romcatalog.service.UserService
import com.lucascanno.romcatalog.web.callerUserId
import com.lucascanno.romcatalog.web.dto.AdminPingResponse
import com.lucascanno.romcatalog.web.dto.CreateUserRequest
import com.lucascanno.romcatalog.web.dto.RegisterRomRequest
import com.lucascanno.romcatalog.web.dto.ResetPasswordRequest
import com.lucascanno.romcatalog.web.dto.UpdateRomRequest
import com.lucascanno.romcatalog.web.dto.toDto
import com.lucascanno.romcatalog.web.requireAdminScope
import com.lucascanno.romcatalog.web.uuidPathParam
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Admin surface. Mounted inside `authenticate(AUTH_JWT) { ... }`; every handler
 * also calls [requireAdminScope] so a valid `user` token gets 403.
 */
fun Route.adminRoutes(ingestionService: IngestionService, userService: UserService, romService: RomService) {
    route("/admin") {
        get("/ping") {
            call.requireAdminScope()
            call.respond(AdminPingResponse(scope = "admin"))
        }

        // ── users ────────────────────────────────────────────────────────────
        route("/users") {
            get {
                call.requireAdminScope()
                call.respond(userService.list().map { it.toDto() })
            }
            post {
                call.requireAdminScope()
                val body = call.receive<CreateUserRequest>()
                val user = userService.create(body.username.trim(), body.password, body.role?.trim())
                call.respond(HttpStatusCode.Created, user.toDto())
            }
            post("/{id}/reset-password") {
                call.requireAdminScope()
                val body = call.receive<ResetPasswordRequest>()
                val user = userService.resetPassword(call.uuidPathParam("id"), body.password)
                call.respond(user.toDto())
            }
            delete("/{id}") {
                call.requireAdminScope()
                userService.delete(call.uuidPathParam("id"), call.callerUserId())
                call.respond(HttpStatusCode.NoContent)
            }
        }

        post("/roms") {
            call.requireAdminScope()
            val contentType = call.request.contentType()
            val outcome = when {
                contentType.match(ContentType.MultiPart.FormData) -> ingestMultipart(call, ingestionService)
                contentType.match(ContentType.Application.Json) -> ingestJson(call, ingestionService)
                else -> throw ApiException(
                    HttpStatusCode.UnsupportedMediaType,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Use multipart/form-data (with a 'file' part) or application/json",
                )
            }
            when (outcome) {
                is IngestionService.Outcome.Created -> call.respond(HttpStatusCode.Created, outcome.rom.toDto())
                is IngestionService.Outcome.Duplicate -> call.respond(HttpStatusCode.Conflict, outcome.existing.toDto())
                is IngestionService.Outcome.Planned -> error("dry-run outcome from a non-dry-run request")
            }
        }

        patch("/roms/{id}") {
            call.requireAdminScope()
            val body = call.receive<UpdateRomRequest>()
            call.respond(romService.update(call.uuidPathParam("id"), body))
        }

        delete("/roms/{id}") {
            call.requireAdminScope()
            romService.delete(call.uuidPathParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun ingestMultipart(call: ApplicationCall, service: IngestionService): IngestionService.Outcome {
    var name: String? = null
    var systemRaw: String? = null
    var coverUrl: String? = null
    var originalFilename: String? = null
    var tempFile: File? = null

    call.receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FormItem -> when (part.name) {
                "name" -> name = part.value
                "system" -> systemRaw = part.value
                "coverUrl" -> coverUrl = part.value
            }

            is PartData.FileItem -> {
                originalFilename = part.originalFileName
                val target = withContext(Dispatchers.IO) { File.createTempFile("rom-ingest-", ".part") }
                part.provider().toInputStream().use { input ->
                    withContext(Dispatchers.IO) {
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                tempFile = target
            }

            else -> {}
        }
        part.dispose()
    }

    val file = tempFile
        ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FILE", "multipart body must include a 'file' part")
    val filename = originalFilename ?: "upload.bin"
    val systemFieldValue = systemRaw

    return try {
        val system = if (systemFieldValue != null) {
            GameSystem.fromApi(systemFieldValue)
                ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_SYSTEM", "Unknown system '$systemFieldValue'")
        } else {
            SystemDetector.fromFilename(filename)
                ?: throw ApiException(
                    HttpStatusCode.BadRequest,
                    "UNKNOWN_SYSTEM",
                    "Cannot infer system from '$filename'; send a 'system' field",
                )
        }
        val effectiveName = name?.takeIf { it.isNotBlank() } ?: filename.substringBeforeLast('.')
        service.ingestBytes(effectiveName, system, filename, { file.inputStream() }, coverUrl)
    } finally {
        file.delete()
    }
}

private suspend fun ingestJson(call: ApplicationCall, service: IngestionService): IngestionService.Outcome {
    val body = call.receive<RegisterRomRequest>()
    val system = GameSystem.fromApi(body.system)
        ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_SYSTEM", "Unknown system '${body.system}'")
    return service.ingestExistingObject(
        name = body.name,
        system = system,
        storageKey = body.storageKey,
        expectedHash = body.hash,
        expectedSizeBytes = body.sizeBytes,
        coverUrl = body.coverUrl,
    )
}
