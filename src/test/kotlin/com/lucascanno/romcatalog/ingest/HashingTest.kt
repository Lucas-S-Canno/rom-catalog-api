package com.lucascanno.romcatalog.ingest

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class HashingTest {

    @Test
    fun `sha256 of a known string matches the reference value`() {
        // echo -n "hello" | sha256sum
        val fp = Hashing.fingerprint("hello".toByteArray())

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", fp.sha256)
        assertEquals(5, fp.sizeBytes)
    }

    @Test
    fun `empty input hashes to the well-known empty digest`() {
        val fp = Hashing.fingerprint(ByteArray(0))

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", fp.sha256)
        assertEquals(0, fp.sizeBytes)
    }

    @Test
    fun `streaming a large payload gives the same digest and size as hashing it whole`() {
        val payload = ByteArray(5 * 1024 * 1024) { (it * 31 % 251).toByte() }

        val streamed = Hashing.fingerprint(ByteArrayInputStream(payload))
        val whole = Hashing.fingerprint(payload)

        assertEquals(whole.sha256, streamed.sha256)
        assertEquals(payload.size.toLong(), streamed.sizeBytes)
    }
}
