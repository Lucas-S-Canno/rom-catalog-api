package com.lucascanno.romcatalog.db

import com.lucascanno.romcatalog.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig as ExposedDatabaseConfig

/**
 * Owns the connection pool and the Exposed [Database] handle. On [init] it also
 * runs the Flyway migrations, so a freshly started process always has an
 * up-to-date schema.
 */
object DatabaseFactory {

    fun init(config: DatabaseConfig): DatabaseResources {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                connectionTimeout = config.connectionTimeoutMs
                validationTimeout = minOf(config.connectionTimeoutMs, 5_000L)
                validate()
            }
        )

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val database = Database.connect(
            dataSource,
            databaseConfig = ExposedDatabaseConfig {
                // Don't silently retry failed writes (e.g. unique-constraint violations).
                defaultMaxAttempts = 1
            },
        )
        return DatabaseResources(database, dataSource)
    }
}

class DatabaseResources(
    val database: Database,
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    override fun close() = dataSource.close()
}
