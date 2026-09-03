package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.auth.CredentialRules
import com.lucascanno.romcatalog.auth.JwtService
import com.lucascanno.romcatalog.auth.PasswordHasher
import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.domain.User
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.repository.UserRepository
import io.ktor.http.HttpStatusCode
import java.util.UUID

/** Login and self-service credential changes. */
class AuthService(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val jwt: JwtService,
    private val config: AuthConfig,
) {
    data class Session(val user: User, val token: String, val expiresInSeconds: Long)

    /** @throws ApiException 401 INVALID_CREDENTIALS for unknown user OR wrong password (same response — no enumeration). */
    suspend fun login(username: String, password: String): Session {
        val user = users.findByUsername(username)
        if (user == null || !hasher.verify(password, user.passwordHash)) {
            throw ApiException(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "Invalid username or password")
        }
        return Session(user, jwt.issue(user, config.tokenTtl), config.tokenTtl.seconds)
    }

    suspend fun me(userId: UUID): User =
        users.findById(userId) ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "User no longer exists")

    /**
     * Changes the caller's own username and/or password. Requires the current
     * password. Clears `mustChangeCredentials`. Returns a fresh session (the
     * subject/username may have changed).
     */
    suspend fun changeCredentials(
        userId: UUID,
        currentPassword: String,
        newUsername: String?,
        newPassword: String?,
    ): Session {
        val user = users.findById(userId)
            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "User no longer exists")

        if (!hasher.verify(currentPassword, user.passwordHash)) {
            throw ApiException(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "Current password is wrong")
        }

        val wantsUsername = newUsername != null && newUsername != user.username
        val wantsPassword = !newPassword.isNullOrEmpty()
        if (!wantsUsername && !wantsPassword) {
            throw ApiException(HttpStatusCode.BadRequest, "NOTHING_TO_CHANGE", "Provide newUsername and/or newPassword")
        }

        if (wantsUsername) {
            CredentialRules.requireValidUsername(newUsername!!)
            if (users.findByUsername(newUsername) != null) {
                throw ApiException(HttpStatusCode.Conflict, "USERNAME_TAKEN", "That username is already in use")
            }
        }
        if (wantsPassword) {
            CredentialRules.requireValidPassword(newPassword!!)
        }

        users.updateCredentials(
            id = user.id,
            newUsername = if (wantsUsername) newUsername else null,
            newPasswordHash = if (wantsPassword) hasher.hash(newPassword!!) else null,
            mustChangeCredentials = false,
        )

        val updated = users.findById(user.id)!!
        return Session(updated, jwt.issue(updated, config.tokenTtl), config.tokenTtl.seconds)
    }
}
