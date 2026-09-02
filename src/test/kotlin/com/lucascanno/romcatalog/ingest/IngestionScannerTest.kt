package com.lucascanno.romcatalog.ingest

import com.lucascanno.romcatalog.service.IngestionService
import com.lucascanno.romcatalog.support.IntegrationTestBase
import com.lucascanno.romcatalog.support.TestInfra
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestionScannerTest : IntegrationTestBase() {

    private val scanner get() = IngestionScanner(IngestionService(romRepository, storage))

    private fun writeFile(dir: Path, name: String, content: String) {
        Files.write(dir.resolve(name), content.toByteArray())
    }

    private fun romRowCount(): Int =
        TestInfra.query("SELECT count(*) FROM roms") { rs -> rs.getInt(1) }.first()

    @Test
    fun `ingests known roms and skips everything else`(@TempDir dir: Path) = runBlocking {
        writeFile(dir, "alpha.gba", "alpha")
        writeFile(dir, "beta.nds", "beta")
        writeFile(dir, "gamma.3ds", "gamma")
        writeFile(dir, "readme.txt", "not a rom")
        writeFile(dir, ".DS_Store", "junk")

        val report = scanner.scan(dir)

        assertEquals(3, report.addedCount, report.toString())
        assertEquals(1, report.skippedCount, report.toString()) // readme.txt; the dotfile is filtered earlier
        assertEquals(0, report.errorCount, report.toString())
        assertEquals(3, romRowCount())
        assertEquals(3, TestInfra.objectKeys().size)
    }

    @Test
    fun `a second run adds nothing and skips the already-ingested roms`(@TempDir dir: Path) = runBlocking {
        writeFile(dir, "alpha.gba", "alpha")
        writeFile(dir, "beta.nds", "beta")

        val first = scanner.scan(dir)
        val second = scanner.scan(dir)

        assertEquals(2, first.addedCount)
        assertEquals(0, second.addedCount, second.toString())
        assertEquals(2, second.skippedCount)
        assertEquals(2, romRowCount())
    }

    @Test
    fun `dry-run reports what it would do but writes nothing`(@TempDir dir: Path) = runBlocking {
        writeFile(dir, "alpha.gba", "alpha")
        writeFile(dir, "beta.nds", "beta")

        val report = scanner.scan(dir, dryRun = true)

        assertEquals(2, report.addedCount)
        assertTrue(report.added.all { it.contains("would add") })
        assertEquals(0, romRowCount())
        assertEquals(0, TestInfra.objectKeys().size)
    }
}
