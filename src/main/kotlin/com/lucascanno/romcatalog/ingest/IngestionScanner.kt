package com.lucascanno.romcatalog.ingest

import com.lucascanno.romcatalog.service.IngestionService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Walks a directory tree and feeds every recognised ROM to [IngestionService].
 * Reusable and testable without a `main`. Idempotent: files whose hash is already
 * in the catalog are reported as skipped.
 */
class IngestionScanner(private val ingestion: IngestionService) {

    data class Report(
        val added: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val errors: List<String> = emptyList(),
    ) {
        val addedCount get() = added.size
        val skippedCount get() = skipped.size
        val errorCount get() = errors.size

        override fun toString(): String = buildString {
            appendLine("Ingestion report: ${added.size} added, ${skipped.size} skipped, ${errors.size} error(s)")
            added.forEach { appendLine("  + $it") }
            skipped.forEach { appendLine("  · $it") }
            errors.forEach { appendLine("  ! $it") }
        }
    }

    suspend fun scan(root: Path, dryRun: Boolean = false): Report {
        val files = Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && !it.name.startsWith(".") }
                .sorted()
                .toList()
        }

        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (path in files) {
            val filename = path.name
            val system = SystemDetector.fromFilename(filename)
            if (system == null) {
                skipped += "$filename (unrecognised extension)"
                continue
            }
            try {
                val outcome = ingestion.ingestBytes(
                    name = filename.substringBeforeLast('.'),
                    system = system,
                    originalFilename = filename,
                    openSource = { path.inputStream() },
                    dryRun = dryRun,
                )
                when (outcome) {
                    is IngestionService.Outcome.Created -> added += "$filename -> ${outcome.rom.id}"
                    is IngestionService.Outcome.Planned -> added += "$filename (would add, ${outcome.sizeBytes} bytes)"
                    is IngestionService.Outcome.Duplicate -> skipped += "$filename (already in catalog)"
                }
            } catch (e: Exception) {
                errors += "$filename: ${e.message}"
            }
        }

        return Report(added, skipped, errors)
    }
}
