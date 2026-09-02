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
                auth = AuthConfig(
                    jwtSecret = value("JWT_SECRET", AuthConfig.DEV_INSECURE_SECRET),
                    jwtIssuer = value("JWT_ISSUER", "rom-catalog-api"),
                    jwtAudience = value("JWT_AUDIENCE", "rom-catalog-app"),
                    jwtRealm = value("JWT_REALM", "rom-catalog"),
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
 * JWT auth settings (D-01: HS256, single-user, tokens minted by the `issueToken` task).
 * `/health` stays public; every other route needs a `user`- or `admin`-scoped token,
 * and the admin routes need `admin`.
 */
data class AuthConfig(
    val jwtSecret: String = DEV_INSECURE_SECRET,
    val jwtIssuer: String = "rom-catalog-api",
    val jwtAudience: String = "rom-catalog-app",
    val jwtRealm: String = "rom-catalog",
) {
    val usingInsecureDefaultSecret: Boolean get() = jwtSecret == DEV_INSECURE_SECRET

    companion object {
        /** Placeholder so `./gradlew run` works locally; MUST be overridden via JWT_SECRET in real use. */
        const val DEV_INSECURE_SECRET = "dev-only-insecure-secret-change-me"
    }
}
