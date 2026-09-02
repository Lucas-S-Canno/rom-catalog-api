package com.lucascanno.romcatalog

import com.lucascanno.romcatalog.config.AppConfig
import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.config.DownloadConfig
import com.lucascanno.romcatalog.db.DatabaseFactory
import com.lucascanno.romcatalog.repository.FavoriteRepository
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.service.DownloadService
import com.lucascanno.romcatalog.service.FavoriteService
import com.lucascanno.romcatalog.service.HealthService
import com.lucascanno.romcatalog.service.IngestionService
import com.lucascanno.romcatalog.service.RomService
import com.lucascanno.romcatalog.storage.MinioStorageClient
import com.lucascanno.romcatalog.storage.StorageClient
import com.lucascanno.romcatalog.web.AUTH_JWT
import com.lucascanno.romcatalog.web.configureAuthentication
import com.lucascanno.romcatalog.web.configureMonitoring
import com.lucascanno.romcatalog.web.configureSerialization
import com.lucascanno.romcatalog.web.configureStatusPages
import com.lucascanno.romcatalog.web.routes.adminRoutes
import com.lucascanno.romcatalog.web.routes.favoriteRoutes
import com.lucascanno.romcatalog.web.routes.healthRoutes
import com.lucascanno.romcatalog.web.routes.readinessRoutes
import com.lucascanno.romcatalog.web.routes.romRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import org.jetbrains.exposed.sql.Database

fun main() {
    val config = AppConfig.fromEnv()
    if (config.isProduction) config.requireProductionReady()
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

/** Wires the real infrastructure (Postgres, MinIO) and installs the app. */
fun Application.module(config: AppConfig = AppConfig.fromEnv()) {
    val db = DatabaseFactory.init(config.database)
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { db.close() }

    val storage = MinioStorageClient.create(config.storage)
    storage.ensureBucket()

    val romRepository = RomRepository(db.database)
    val favoriteRepository = FavoriteRepository(db.database)

    configureApp(
        AppDependencies(
            romService = RomService(romRepository),
            downloadService = DownloadService(romRepository, storage, config.download),
            favoriteService = FavoriteService(favoriteRepository, romRepository),
            ingestionService = IngestionService(romRepository, storage),
            healthService = HealthService.forInfra(db.database, storage),
            authConfig = config.auth,
        )
    )
}

/**
 * Installs plugins and routes given ready-made dependencies. Kept separate from
 * [module] so tests can supply their own (container-backed) dependencies.
 */
fun Application.configureApp(deps: AppDependencies) {
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureAuthentication(deps.authConfig)
    routing {
        healthRoutes() // public — liveness only
        readinessRoutes(deps.healthService) // public — checks DB + MinIO
        authenticate(AUTH_JWT) {
            romRoutes(deps.romService, deps.downloadService)
            favoriteRoutes(deps.favoriteService)
            adminRoutes(deps.ingestionService) // + per-handler admin-scope check
        }
    }
}

data class AppDependencies(
    val romService: RomService,
    val downloadService: DownloadService,
    val favoriteService: FavoriteService,
    val ingestionService: IngestionService,
    val healthService: HealthService,
    val authConfig: AuthConfig,
) {
    companion object {
        /** Convenience for tests: build the whole stack from a DB handle + storage. */
        fun of(
            database: Database,
            storage: StorageClient,
            downloadConfig: DownloadConfig = DownloadConfig(),
            authConfig: AuthConfig = AuthConfig(),
            healthService: HealthService = HealthService.forInfra(database, storage),
        ): AppDependencies {
            val romRepository = RomRepository(database)
            val favoriteRepository = FavoriteRepository(database)
            return AppDependencies(
                romService = RomService(romRepository),
                downloadService = DownloadService(romRepository, storage, downloadConfig),
                favoriteService = FavoriteService(favoriteRepository, romRepository),
                ingestionService = IngestionService(romRepository, storage),
                healthService = healthService,
                authConfig = authConfig,
            )
        }
    }
}
