package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.storage.StorageClient
import com.lucascanno.romcatalog.web.dto.CheckResult
import com.lucascanno.romcatalog.web.dto.ReadinessResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Duration

/**
 * Readiness aggregation. Each dependency probe is injected so the logic is fully
 * unit-testable; [forInfra] wires the real Postgres + MinIO checks.
 *
 * Liveness (`/health`) is intentionally NOT here — it must never touch a dependency.
 */
class HealthService(
    private val dbCheck: suspend () -> Unit,
    private val storageCheck: suspend () -> Boolean,
    private val timeout: Duration = Duration.ofSeconds(2),
) {
    suspend fun readiness(): ReadinessResponse = coroutineScope {
        val db = async { probe { dbCheck(); true } }
        val storage = async { probe { storageCheck() } }
        val checks = linkedMapOf("db" to db.await(), "storage" to storage.await())
        val status = if (checks.values.all { it.status == UP }) UP else DOWN
        ReadinessResponse(status, checks)
    }

    private suspend fun probe(block: suspend () -> Boolean): CheckResult = try {
        when (withTimeoutOrNull(timeout.toMillis()) { block() }) {
            true -> CheckResult(UP)
            false -> CheckResult(DOWN, "probe returned false")
            null -> CheckResult(DOWN, "timed out after ${timeout.toMillis()}ms")
        }
    } catch (e: Exception) {
        CheckResult(DOWN, (e.message ?: e::class.simpleName ?: "error").take(200))
    }

    companion object {
        const val UP = "UP"
        const val DOWN = "DOWN"

        fun forInfra(
            database: Database,
            storage: StorageClient,
            timeout: Duration = Duration.ofSeconds(2),
        ): HealthService = HealthService(
            dbCheck = { newSuspendedTransaction(Dispatchers.IO, database) { exec("SELECT 1") } },
            storageCheck = { withContext(Dispatchers.IO) { storage.bucketExists() } },
            timeout = timeout,
        )
    }
}
