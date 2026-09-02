package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.domain.GameSystem
import com.lucascanno.romcatalog.domain.NewRom
import com.lucascanno.romcatalog.domain.Rom
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.ingest.Hashing
import com.lucascanno.romcatalog.ingest.SystemDetector
import com.lucascanno.romcatalog.repository.RomRepository
import com.lucascanno.romcatalog.storage.StorageClient
import io.ktor.http.HttpStatusCode
import java.io.InputStream

/**
 * The one place that turns a ROM file into a catalog entry. Shared by
 * `POST /admin/roms` and the directory-scan CLI so the rules can't diverge.
 * Dedup key is the sha256 [Rom.hash].
 */
class IngestionService(
    private val roms: RomRepository,
    private val storage: StorageClient,
) {
    sealed interface Outcome {
        /** The ROM was (or would be) added. */
        data class Created(val rom: Rom) : Outcome

        /** A ROM with this hash is already in the catalog; nothing was written. */
        data class Duplicate(val existing: Rom) : Outcome

        /** dry-run only: not a duplicate, would have been created. */
        data class Planned(
            val name: String,
            val system: GameSystem,
            val hash: String,
            val sizeBytes: Long,
        ) : Outcome
    }

    /**
     * Mode A — caller provides the bytes. [openSource] must be re-openable: it is
     * read once to fingerprint and once to upload, so nothing is buffered.
     */
    suspend fun ingestBytes(
        name: String,
        system: GameSystem,
        originalFilename: String,
        openSource: () -> InputStream,
        coverUrl: String? = null,
        dryRun: Boolean = false,
    ): Outcome {
        val fingerprint = Hashing.fingerprint(openSource())

        roms.findByHash(fingerprint.sha256)?.let { return Outcome.Duplicate(it) }
        if (dryRun) {
            return Outcome.Planned(name, system, fingerprint.sha256, fingerprint.sizeBytes)
        }

        val extension = SystemDetector.extensionOf(originalFilename)
            .ifEmpty { SystemDetector.defaultExtension(system) }
        val storageKey = "${system.api}/${fingerprint.sha256}.$extension"
        storage.putObject(storageKey, openSource(), fingerprint.sizeBytes, "application/octet-stream")

        val rom = roms.create(
            NewRom(
                name = name,
                system = system,
                sizeBytes = fingerprint.sizeBytes,
                hash = fingerprint.sha256,
                storageKey = storageKey,
                coverUrl = coverUrl,
            )
        )
        return Outcome.Created(rom)
    }

    /**
     * Mode B — the object is already in the bucket (uploaded out of band). We
     * fingerprint it and verify the caller's `hash`/`sizeBytes` before registering.
     */
    suspend fun ingestExistingObject(
        name: String,
        system: GameSystem,
        storageKey: String,
        expectedHash: String,
        expectedSizeBytes: Long,
        coverUrl: String? = null,
    ): Outcome {
        if (!storage.objectExists(storageKey)) {
            throw ApiException(
                HttpStatusCode.UnprocessableEntity,
                "OBJECT_NOT_FOUND",
                "No object at storage key '$storageKey'",
            )
        }

        val fingerprint = Hashing.fingerprint(storage.openObject(storageKey))
        if (fingerprint.sha256 != expectedHash) {
            throw ApiException(
                HttpStatusCode.UnprocessableEntity,
                "HASH_MISMATCH",
                "Provided hash does not match the stored object",
            )
        }
        if (fingerprint.sizeBytes != expectedSizeBytes) {
            throw ApiException(
                HttpStatusCode.UnprocessableEntity,
                "SIZE_MISMATCH",
                "Provided sizeBytes ($expectedSizeBytes) does not match the stored object (${fingerprint.sizeBytes})",
            )
        }

        roms.findByHash(expectedHash)?.let { return Outcome.Duplicate(it) }

        val rom = roms.create(
            NewRom(
                name = name,
                system = system,
                sizeBytes = fingerprint.sizeBytes,
                hash = fingerprint.sha256,
                storageKey = storageKey,
                coverUrl = coverUrl,
            )
        )
        return Outcome.Created(rom)
    }
}
