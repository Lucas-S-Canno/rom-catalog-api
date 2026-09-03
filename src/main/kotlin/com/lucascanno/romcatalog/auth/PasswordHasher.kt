package com.lucascanno.romcatalog.auth

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * BCrypt password hashing. The hash string (`$2b$<cost>$<salt+digest>`) is what
 * gets stored — it carries its own salt and cost, so verification needs nothing else.
 */
class PasswordHasher(private val cost: Int = DEFAULT_COST) {

    init {
        require(cost in 4..31) { "bcrypt cost must be 4..31, got $cost" }
    }

    fun hash(plain: CharArray): String = BCrypt.withDefaults().hashToString(cost, plain)

    fun hash(plain: String): String = hash(plain.toCharArray())

    fun verify(plain: CharArray, hash: String): Boolean =
        BCrypt.verifyer().verify(plain, hash.toCharArray()).verified

    fun verify(plain: String, hash: String): Boolean = verify(plain.toCharArray(), hash)

    companion object {
        const val DEFAULT_COST = 12
    }
}
