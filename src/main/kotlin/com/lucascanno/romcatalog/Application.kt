package com.lucascanno.romcatalog

import com.lucascanno.romcatalog.auth.AdminBootstrap
import com.lucascanno.romcatalog.auth.JwtService
import com.lucascanno.romcatalog.auth.PasswordHasher
import com.lucascanno.romcatalog.config.AppConfig
import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.config.DownloadConfig
import com.lucascanno.romcatalog.db.DatabaseFactory
import com.lucascanno.romcatalog.repository.FavoriteRepository
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.repository.UserRepository
import com.lucascanno.romcatalog.service.AuthService
import com.lucascanno.romcatalog.service.DownloadService
import com.lucascanno.romcatalog.service.FavoriteService
import com.lucascanno.romcatalog.service.HealthService
import com.lucascanno.romcatalog.service.IngestionService
import com.lucascanno.romcatalog.service.RomService
import com.lucascanno.romcatalog.service.UserService
import com.lucascanno.romcatalog.storage.MinioStorageClient
import com.lucascanno.romcatalog.storage.StorageClient
import com.lucascanno.romcatalog.web.AUTH_JWT
import com.lucascanno.romcatalog.web.configureAuthentication
import com.lucascanno.romcatalog.web.configureMonitoring
import com.lucascanno.romcatalog.web.configureSerialization
import com.lucascanno.romcatalog.web.configureStatusPages
import com.lucascanno.romcatalog.web.routes.adminRoutes
import com.lucascanno.romcatalog.web.routes.authLoginRoute
import com.lucascanno.romcatalog.web.routes.authSelfRoutes
import com.lucascanno.romcatalog.web.routes.favoriteRoutes
import com.lucascanno.romcatalog.web.routes.healthRoutes
import com.lucascanno.romcatalog.web.routes.readinessRoutes
import com.lucascanno.romcatalog.web.routes.romRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
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

    val deps = AppDependencies.of(db.database, storage, config.download, config.auth)

    runBlocking {
        AdminBootstrap.run(UserRepository(db.database), PasswordHasher(config.auth.bcryptCost), config.auth)
    }

    configureApp(deps)
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
        authLoginRoute(deps.authService) // public — POST /auth/login
        authenticate(AUTH_JWT) {
            authSelfRoutes(deps.authService) // GET /auth/me, POST /auth/change-credentials
            romRoutes(deps.romService, deps.downloadService)
            favoriteRoutes(deps.favoriteService)
            adminRoutes(deps.ingestionService, deps.userService) // + per-handler admin-scope check
        }
    }
}

data class AppDependencies(
    val romService: RomService,
    val downloadService: DownloadService,
    val favoriteService: FavoriteService,
    val ingestionService: IngestionService,
    val healthService: HealthService,
    val authService: AuthService,
    val userService: UserService,
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
            val userRepository = UserRepository(database)
            val hasher = PasswordHasher(authConfig.bcryptCost)
            val jwtService = JwtService(authConfig)
            return AppDependencies(
                romService = RomService(romRepository),
                downloadService = DownloadService(romRepository, storage, downloadConfig),
                favoriteService = FavoriteService(favoriteRepository, romRepository),
                ingestionService = IngestionService(romRepository, storage),
                healthService = healthService,
                authService = AuthService(userRepository, hasher, jwtService, authConfig),
                userService = UserService(userRepository, hasher),
                authConfig = authConfig,
            )
        }
    }
}
