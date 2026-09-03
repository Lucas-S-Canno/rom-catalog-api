package com.lucascanno.romcatalog.auth

import com.lucascanno.romcatalog.config.AuthConfig
import com.lucascanno.romcatalog.domain.NewUser
import com.lucascanno.romcatalog.domain.Role
import com.lucascanno.romcatalog.repository.UserRepository
import org.slf4j.LoggerFactory

/**
 * Creates the first admin account from ADMIN_USERNAME / ADMIN_BOOTSTRAP_PASSWORD
 * (env → Secret, never in git). Idempotent: does nothing once any admin exists,
 * so it can run on every boot and survives cluster rebuilds.
 */
object AdminBootstrap {
    private val log = LoggerFactory.getLogger(AdminBootstrap::class.java)

    suspend fun run(users: UserRepository, hasher: PasswordHasher, config: AuthConfig) {
        if (users.countByRole(Role.ADMIN) > 0) return

        val username = config.adminUsername
        val password = config.adminBootstrapPassword
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            log.warn(
                "No admin account and ADMIN_USERNAME/ADMIN_BOOTSTRAP_PASSWORD not set — " +
                    "create one with a break-glass token + POST /admin/users, or set those vars and restart.",
            )
            return
        }

        runCatching {
            CredentialRules.requireValidUsername(username)
            CredentialRules.requireValidPassword(password)
        }.onFailure {
            log.error("ADMIN_USERNAME/ADMIN_BOOTSTRAP_PASSWORD are invalid: {}", it.message)
            return
        }

        if (users.findByUsername(username) != null) {
            log.warn("User '{}' already exists but is not an admin — not touching it.", username)
            return
        }

        users.create(
            NewUser(
                username = username,
                passwordHash = hasher.hash(password),
                role = Role.ADMIN,
                mustChangeCredentials = false,
            )
        )
        log.info("Bootstrapped admin account '{}'. You can now remove ADMIN_BOOTSTRAP_PASSWORD from the environment.", username)
    }
}
