package com.lucascanno.romcatalog.auth

import com.lucascanno.romcatalog.config.AppConfig
import com.lucascanno.romcatalog.domain.Role
import java.time.Duration
import kotlin.system.exitProcess

/**
 * BREAK-GLASS token minter. Prints a signed JWT with no backing DB user — use it
 * to get back in if you lock yourself out of the account system. Run via:
 *
 *   ./gradlew -q issueToken --args="--scope admin --ttl-days 365"
 *
 * Normal auth is `POST /auth/login`. Signs with the server's JWT_SECRET.
 */
fun main(args: Array<String>) {
    val roleArg = args.value("--scope") ?: "user"
    val role = Role.fromClaim(roleArg) ?: run {
        System.err.println("error: --scope must be 'user' or 'admin' (got '$roleArg')")
        exitProcess(2)
    }
    val ttlDays = (args.value("--ttl-days") ?: "3650").toLongOrNull() ?: run {
        System.err.println("error: --ttl-days must be an integer")
        exitProcess(2)
    }

    val auth = AppConfig.fromEnv().auth
    if (auth.usingInsecureDefaultSecret) {
        System.err.println("warning: JWT_SECRET is not set — signing with the insecure dev default. Do not use this token in production.")
    }

    val token = JwtService(auth).issueBreakGlass(role, Duration.ofDays(ttlDays))
    println(token)
}

private fun Array<String>.value(name: String): String? {
    val index = indexOf(name)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}
