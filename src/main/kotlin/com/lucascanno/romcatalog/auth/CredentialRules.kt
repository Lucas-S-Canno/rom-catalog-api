package com.lucascanno.romcatalog.auth

import com.lucascanno.romcatalog.error.ApiException
import io.ktor.http.HttpStatusCode

/** Shared validation for usernames and passwords. */
object CredentialRules {
    const val MIN_PASSWORD_LENGTH = 8
    private val USERNAME = Regex("^[A-Za-z0-9._-]{3,64}$")

    fun requireValidUsername(username: String) {
        if (!USERNAME.matches(username)) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "INVALID_USERNAME",
                "username must be 3-64 characters: letters, digits, '.', '_' or '-'",
            )
        }
    }

    fun requireValidPassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "WEAK_PASSWORD",
                "password must be at least $MIN_PASSWORD_LENGTH characters",
            )
        }
    }
}
