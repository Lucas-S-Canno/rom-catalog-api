package com.lucascanno.romcatalog.ingest

import com.lucascanno.romcatalog.config.AppConfig
import com.lucascanno.romcatalog.db.DatabaseFactory
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.service.IngestionService
import com.lucascanno.romcatalog.storage.MinioStorageClient
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

/**
 * Scans a directory and ingests every ROM it finds, straight into Postgres + MinIO
 * (no running server, no token needed). Idempotent.
 *
 *   ./gradlew -q ingest --args="--dir /path/to/roms"
 *   ./gradlew -q ingest --args="--dir /path/to/roms --dry-run"
 */
fun main(args: Array<String>) {
    val dir = args.value("--dir") ?: run {
        System.err.println("error: --dir <path> is required")
        exitProcess(2)
    }
    val dryRun = args.contains("--dry-run")
    val root = Path.of(dir)
    if (!root.isDirectory()) {
        System.err.println("error: '$dir' is not a directory")
        exitProcess(2)
    }

    val config = AppConfig.fromEnv()
    val database = DatabaseFactory.init(config.database)
    val storage = MinioStorageClient.create(config.storage).also { it.ensureBucket() }
    val scanner = IngestionScanner(IngestionService(RomRepository(database.database), storage))

    val report = try {
        runBlocking { scanner.scan(root, dryRun = dryRun) }
    } finally {
        database.close()
    }

    print(report)
    exitProcess(if (report.errorCount > 0) 1 else 0)
}

private fun Array<String>.value(name: String): String? {
    val index = indexOf(name)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}
