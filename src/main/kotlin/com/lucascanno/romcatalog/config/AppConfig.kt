package com.lucascanno.romcatalog.config

class ConfigurationException(message: String) : RuntimeException(message)

/**
 * Application configuration, resolved entirely from environment variables (12-factor).
 * Local defaults line up with the `docker-compose.yml` in the repo so `./gradlew run`
 * works after `docker compose up -d` without an `.env` file.
 *
 * When `APP_ENV=production`, [requireProductionReady] rejects any leftover dev default.
 */
data class AppConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val appEnv: String = "local",
    val database: DatabaseConfig,
    val storage: StorageConfig,
    val download: DownloadConfig = DownloadConfig(),
    val auth: AuthConfig = AuthConfig(),
    val cors: CorsConfig = CorsConfig(),
) {
    val isProduction: Boolean get() = appEnv.equals("production", ignoreCase = true)

    /**
     * Fails fast (before the server binds) if the process is flagged as production
     * but still carries an insecure or local-only value. Reports every problem at once.
     */
    fun requireProductionReady() {
        val problems = buildList {
            if (auth.usingInsecureDefaultSecret) add("JWT_SECRET is unset (using the insecure dev default)")
            if ("localhost" in database.url || "127.0.0.1" in database.url) add("DB_URL still points at localhost")
            if (database.password == "romcatalog") add("DB_PASSWORD is the dev default 'romcatalog'")
            if (storage.accessKey == "minioadmin") add("MINIO_ACCESS_KEY is the dev default 'minioadmin'")
            if (storage.secretKey == "minioadmin") add("MINIO_SECRET_KEY is the dev default 'minioadmin'")
            if ("localhost" in storage.publicEndpoint || "127.0.0.1" in storage.publicEndpoint) {
                add("MINIO_PUBLIC_ENDPOINT points at localhost — the phone will not reach it")
            }
            if (cors.anyHost) add("CORS_ALLOWED_ORIGINS is '*' — set the admin panel's exact origin(s)")
            if (cors.allowedOrigins.any { "localhost" in it || "127.0.0.1" in it }) {
                add("CORS_ALLOWED_ORIGINS still contains a localhost origin")
            }
        }
        if (problems.isNotEmpty()) {
            throw ConfigurationException(
                "Refusing to start with APP_ENV=production; fix these environment variables:\n" +
                    problems.joinToString("\n") { "  - $it" }
            )
        }
    }

    companion object {
        fun fromEnv(getenv: (String) -> String? = { System.getenv(it) }): AppConfig {
            fun value(key: String, default: String): String = getenv(key)?.takeIf { it.isNotBlank() } ?: default

            val minioEndpoint = value("MINIO_ENDPOINT", "http://localhost:9000")
            return AppConfig(
                host = value("HOST", "0.0.0.0"),
                port = value("PORT", "8080").toInt(),
                appEnv = value("APP_ENV", "local"),
                database = DatabaseConfig(
                    url = value("DB_URL", "jdbc:postgresql://localhost:5432/romcatalog"),
                    user = value("DB_USER", "romcatalog"),
                    password = value("DB_PASSWORD", "romcatalog"),
                    maxPoolSize = value("DB_MAX_POOL_SIZE", "5").toInt(),
                    connectionTimeoutMs = value("DB_CONNECTION_TIMEOUT_MS", "10000").toLong(),
                ),
                storage = StorageConfig(
                    endpoint = minioEndpoint,
                    publicEndpoint = value("MINIO_PUBLIC_ENDPOINT", minioEndpoint),
                    accessKey = value("MINIO_ACCESS_KEY", "minioadmin"),
                    secretKey = value("MINIO_SECRET_KEY", "minioadmin"),
                    bucket = value("MINIO_BUCKET", "roms"),
                    region = value("MINIO_REGION", "us-east-1"),
                    timeoutMs = value("STORAGE_TIMEOUT_MS", "10000").toLong(),
                ),
                download = DownloadConfig(
                    urlTtlSeconds = value("DOWNLOAD_URL_TTL_SECONDS", "900").toLong(),
                ),
                // Unset → no CORS at all. The mobile app is native (no CORS) and the
                // admin panel is served same-origin behind nginx, so CORS only matters
                // when you point a browser dev server straight at this API — set it then.
                cors = CorsConfig.parse(value("CORS_ALLOWED_ORIGINS", "")),
                auth = AuthConfig(
                    jwtSecret = value("JWT_SECRET", AuthConfig.DEV_INSECURE_SECRET),
                    jwtIssuer = value("JWT_ISSUER", "rom-catalog-api"),
                    jwtAudience = value("JWT_AUDIENCE", "rom-catalog-app"),
                    jwtRealm = value("JWT_REALM", "rom-catalog"),
                    tokenTtlHours = value("JWT_TTL_HOURS", "168").toLong(),
                    bcryptCost = value("BCRYPT_COST", "12").toInt(),
                    adminUsername = getenv("ADMIN_USERNAME")?.takeIf { it.isNotBlank() },
                    adminBootstrapPassword = getenv("ADMIN_BOOTSTRAP_PASSWORD")?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = 5,
    /** How long HikariCP waits for a connection before failing (ms). */
    val connectionTimeoutMs: Long = 10_000,
)

data class StorageConfig(
    /** Endpoint the API uses for control-plane calls (statObject, putObject). */
    val endpoint: String,
    /** Endpoint used to *sign* presigned URLs — must be reachable by the phone (D-02). */
    val publicEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    /** Set explicitly so presigning never needs a GetBucketLocation round-trip. */
    val region: String = "us-east-1",
    /** connect/read/write timeout for the MinIO HTTP client (ms). */
    val timeoutMs: Long = 10_000,
)

data class DownloadConfig(
    /** Lifetime of a presigned download URL (D-09). */
    val urlTtlSeconds: Long = 900,
)

/**
 * Browser CORS policy for the admin panel. The mobile app talks to the API from
 * native code and needs none of this. `CORS_ALLOWED_ORIGINS` is a comma-separated
 * list of exact origins (`https://rom-catalog-admin.example.com`), or `*` to allow any
 * (dev only — [AppConfig.requireProductionReady] rejects `*` and an explicit localhost
 * origin in production). Unset / empty → no CORS handling is installed.
 *
 * The no-arg default keeps `http://localhost:4200` only so test fixtures
 * (`AppDependencies.of`) exercise the CORS plugin; real startup resolves this from
 * the environment via [AppConfig.fromEnv], which defaults to empty.
 */
data class CorsConfig(
    val allowedOrigins: List<String> = listOf("http://localhost:4200"),
    val anyHost: Boolean = false,
) {
    companion object {
        fun parse(raw: String): CorsConfig {
            val trimmed = raw.trim()
            if (trimmed == "*") return CorsConfig(allowedOrigins = emptyList(), anyHost = true)
            val origins = trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            return CorsConfig(allowedOrigins = origins, anyHost = false)
        }
    }
}

/**
 * Auth settings. `POST /auth/login` issues tokens with `JWT_TTL_HOURS` lifetime;
 * the `issueToken` CLI stays as a break-glass path. Passwords are BCrypt-hashed.
 * On boot, if no admin exists and ADMIN_USERNAME/ADMIN_BOOTSTRAP_PASSWORD are set,
 * an admin account is created (see AdminBootstrap).
 */
data class AuthConfig(
    val jwtSecret: String = DEV_INSECURE_SECRET,
    val jwtIssuer: String = "rom-catalog-api",
    val jwtAudience: String = "rom-catalog-app",
    val jwtRealm: String = "rom-catalog",
    val tokenTtlHours: Long = 168,
    val bcryptCost: Int = 12,
    val adminUsername: String? = null,
    val adminBootstrapPassword: String? = null,
) {
    val usingInsecureDefaultSecret: Boolean get() = jwtSecret == DEV_INSECURE_SECRET
    val tokenTtl: java.time.Duration get() = java.time.Duration.ofHours(tokenTtlHours)

    companion object {
        /** Placeholder so `./gradlew run` works locally; MUST be overridden via JWT_SECRET in real use. */
        const val DEV_INSECURE_SECRET = "dev-only-insecure-secret-change-me"
    }
}
