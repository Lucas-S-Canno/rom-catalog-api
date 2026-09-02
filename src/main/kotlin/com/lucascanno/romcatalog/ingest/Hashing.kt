package com.lucascanno.romcatalog.ingest

import java.io.InputStream
import java.security.MessageDigest

/** sha256 (hex, lowercase) plus byte count, computed in a single streaming pass. */
data class Fingerprint(val sha256: String, val sizeBytes: Long)

object Hashing {

    /** Consumes and closes [input]. Never buffers the whole payload in memory. */
    fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return Fingerprint(digest.digest().toHex(), size)
    }

    fun fingerprint(bytes: ByteArray): Fingerprint = fingerprint(bytes.inputStream())

    private fun ByteArray.toHex(): String {
        val hex = CharArray(size * 2)
        val alphabet = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hex[i * 2] = alphabet[v ushr 4]
            hex[i * 2 + 1] = alphabet[v and 0x0F]
        }
        return String(hex)
    }
}
