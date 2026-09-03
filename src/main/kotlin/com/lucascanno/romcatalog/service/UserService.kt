package com.lucascanno.romcatalog.service

import com.lucascanno.romcatalog.auth.CredentialRules
import com.lucascanno.romcatalog.auth.PasswordHasher
import com.lucascanno.romcatalog.domain.NewUser
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.domain.User
import com.lucascanno.romcatalog.error.ApiException
import com.lucascanno.romcatalog.repository.UserRepository
import io.ktor.http.HttpStatusCode
import java.util.UUID

/** Admin-panel user management. */
class UserService(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
) {
    suspend fun list(): List<User> = users.list()

    /** Creates an account with a temporary password; the user must change it on first login. */
    suspend fun create(username: String, password: String, roleRaw: String?): User {
        CredentialRules.requireValidUsername(username)
        CredentialRules.requireValidPassword(password)
        val role = when (roleRaw) {
            null, "user" -> Role.USER
            "admin" -> Role.ADMIN
            else -> throw ApiException(HttpStatusCode.BadRequest, "INVALID_ROLE", "role must be 'user' or 'admin'")
        }
        if (users.findByUsername(username) != null) {
            throw ApiException(HttpStatusCode.Conflict, "USERNAME_TAKEN", "That username is already in use")
        }
        return users.create(
            NewUser(
                username = username,
                passwordHash = hasher.hash(password),
                role = role,
                mustChangeCredentials = true,
            )
        )
    }

    suspend fun resetPassword(id: UUID, newPassword: String): User {
        CredentialRules.requireValidPassword(newPassword)
        val user = users.findById(id) ?: throw userNotFound(id)
        users.updateCredentials(
            id = user.id,
            newPasswordHash = hasher.hash(newPassword),
            mustChangeCredentials = true,
        )
        return users.findById(user.id)!!
    }

    suspend fun delete(id: UUID, actingUserId: UUID?) {
        val target = users.findById(id) ?: throw userNotFound(id)
        if (target.id == actingUserId) {
            throw ApiException(HttpStatusCode.Conflict, "CANNOT_DELETE_SELF", "You cannot delete your own account")
        }
        if (target.role == Role.ADMIN && users.countByRole(Role.ADMIN) <= 1) {
            throw ApiException(HttpStatusCode.Conflict, "LAST_ADMIN", "Cannot delete the only admin account")
        }
        users.delete(target.id)
    }

    private fun userNotFound(id: UUID) =
        ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "User '$id' not found")
}
