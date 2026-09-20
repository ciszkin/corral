package dev.corral

import io.ktor.server.testing.ApplicationTestBuilder

/** One-call HTTP test app: guard + REST + MCP over fake devices, an injected clock and an optional db. */
fun ApplicationTestBuilder.testDaemon(
    devices: List<Device> = listOf(Device("dev1", "device")),
    now: () -> Long = { 1500L },
    db: Db? = null,
    adbPath: String? = null,
): AppEnv {
    val env = AppEnv({ devices }, LeaseManager({ devices }, db, now), adbPath)
    application { guardLocalHost(); corralModule(env); installMcp(env) }
    return env
}
