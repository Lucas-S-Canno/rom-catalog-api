package com.lucascanno.romcatalog.support

import com.lucascanno.romcatalog.AppDependencies
import com.lucascanno.romcatalog.auth.PasswordHasher
import com.lucascanno.romcatalog.config.DownloadConfig
import com.lucascanno.romcatalog.configureApp
import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.domain.NewRom
import com.lucascanno.romcatalog.domain.NewUser
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.domain.User
import com.lucascanno.romcatalog.repository.FavoriteRepository
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.repository.UserRepository
import io.ktor.client.plugins.DefaultRequest
import kotlinx.coroutines.runBlocking
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.util.UUID

@Tag("it")
abstract class IntegrationTestBase {

    protected val db get() = TestInfra.db
    protected val storage get() = TestInfra.storage
    protected val romRepository: RomRepository by lazy { RomRepository(db.database) }
    protected val favoriteRepository: FavoriteRepository by lazy { FavoriteRepository(db.database) }
    protected val userRepository: UserRepository by lazy { UserRepository(db.database) }

    /** Fast hasher (cost 4) for seeding test users. */
    protected val testHasher = PasswordHasher(cost = 4)

    protected fun seedUser(
        username: String,
        password: String,
        role: Role = Role.USER,
        mustChangeCredentials: Boolean = false,
    ): User = runBlocking {
        userRepository.create(NewUser(username, testHasher.hash(password), role, mustChangeCredentials))
    }

    @BeforeEach
    fun resetState() {
        TestInfra.truncateAll()
        TestInfra.clearBucket()
    }

    protected fun ApplicationTestBuilder.installTestApp(
        downloadConfig: DownloadConfig = DownloadConfig(urlTtlSeconds = 900),
    ) {
        application {
            configureApp(
                AppDependencies.of(
                    database = db.database,
                    storage = storage,
                    downloadConfig = downloadConfig,
                    authConfig = TestAuth.config,
                )
            )
        }
    }

    /**
     * HTTP client for the test app. Sends `Authorization: Bearer <userToken>` by
     * default so existing route tests stay green; pass `token = null` for the
     * unauthenticated case or another token to vary the scope.
     */
    protected fun ApplicationTestBuilder.jsonClient(token: String? = TestAuth.userToken) = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        if (token != null) {
            install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
        }
    }

    protected fun newRom(
        name: String = "Homebrew Demo",
        system: GameSystem = GameSystem.GBA,
        sizeBytes: Long = 2048,
        hash: String = randomHash(),
        storageKey: String? = null,
        coverUrl: String? = null,
    ): NewRom = NewRom(
        name = name,
        system = system,
        sizeBytes = sizeBytes,
        hash = hash,
        storageKey = storageKey ?: "${system.api}/$hash.bin",
        coverUrl = coverUrl,
    )

    protected fun randomHash(): String = UUID.randomUUID().toString().replace("-", "")
}
