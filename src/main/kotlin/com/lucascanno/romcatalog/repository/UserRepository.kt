package com.lucascanno.romcatalog.repository

import com.lucascanno.romcatalog.db.UsersTable
import com.lucascanno.romcatalog.db.toUser
import com.lucascanno.romcatalog.domain.NewUser
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.domain.User
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class UserRepository(private val database: Database) {

    suspend fun create(command: NewUser): User = dbQuery {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val id = UsersTable.insertAndGetId {
            it[username] = command.username
            it[passwordHash] = command.passwordHash
            it[role] = command.role.claim
            it[mustChangeCredentials] = command.mustChangeCredentials
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        User(id, command.username, command.passwordHash, command.role, command.mustChangeCredentials, now, now)
    }

    suspend fun findById(id: UUID): User? = dbQuery {
        UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.toUser()
    }

    /** Case-sensitive lookup — `Lucas` and `lucas` are different accounts. */
    suspend fun findByUsername(username: String): User? = dbQuery {
        UsersTable.selectAll().where { UsersTable.username eq username }.singleOrNull()?.toUser()
    }

    suspend fun list(): List<User> = dbQuery {
        UsersTable.selectAll().orderBy(UsersTable.createdAt to SortOrder.ASC).map { it.toUser() }
    }

    suspend fun countByRole(role: Role): Long = dbQuery {
        UsersTable.selectAll().where { UsersTable.role eq role.claim }.count()
    }

    /** Updates the mutable fields. Only non-null args are written. Always bumps `updated_at`. */
    suspend fun updateCredentials(
        id: UUID,
        newUsername: String? = null,
        newPasswordHash: String? = null,
        mustChangeCredentials: Boolean? = null,
    ): Boolean = dbQuery {
        val changed = UsersTable.update({ UsersTable.id eq id }) {
            newUsername?.let { v -> it[username] = v }
            newPasswordHash?.let { v -> it[passwordHash] = v }
            mustChangeCredentials?.let { v -> it[UsersTable.mustChangeCredentials] = v }
            it[updatedAt] = Instant.now().truncatedTo(ChronoUnit.MICROS)
        }
        changed > 0
    }

    suspend fun delete(id: UUID): Boolean = dbQuery {
        UsersTable.deleteWhere { UsersTable.id eq id } > 0
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }
}
