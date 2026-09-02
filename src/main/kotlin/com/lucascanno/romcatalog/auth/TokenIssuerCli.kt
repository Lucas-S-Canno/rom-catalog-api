package com.lucascanno.romcatalog.auth

import com.lucascanno.romcatalog.config.AppConfig
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Prints a signed JWT to stdout. Meant to be run via:
 *
 *   ./gradlew -q issueToken --args="--scope admin --ttl-days 365"
 *
 * Signs with the same JWT_SECRET the server reads from the environment.
 */
fun main(args: Array<String>) {
    val scopeArg = args.value("--scope") ?: "user"
    val scope = Scope.fromClaim(scopeArg) ?: run {
        System.err.println("error: --scope must be 'user' or 'admin' (got '$scopeArg')")
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

    val token = JwtService(auth).issue(scope, Duration.ofDays(ttlDays))
    println(token)
}

private fun Array<String>.value(name: String): String? {
    val index = indexOf(name)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}
