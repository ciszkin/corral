package dev.corral

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorralCliTtlTest {
    @Test fun claimUsesTheBoundedTtlAndWarnsForEachRejectedValue() {
        val seenTtl = AtomicLong(-1)
        val serverError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/leases") { exchange ->
            try {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                seenTtl.set(Json.decodeFromString<ClaimRequest>(body).ttlSeconds)
                val response = """{"serial":"dev1"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            } catch (e: Throwable) {
                serverError.set(e)
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val cases = listOf(
                Triple("9", 10L,
                    "corral: CORRAL_LEASE_TTL_SECONDS=9 is below the 10s floor; using 10s"),
                Triple("86401", 86_400L,
                    "corral: CORRAL_LEASE_TTL_SECONDS=86401 clamped to 86400s (max 86400)"),
                Triple("abc", 1_800L,
                    "corral: CORRAL_LEASE_TTL_SECONDS=abc is not a positive integer; using 1800s"),
                Triple("0", 1_800L,
                    "corral: CORRAL_LEASE_TTL_SECONDS=0 is not a positive integer; using 1800s"),
                Triple("99999999999999999999", 1_800L,
                    "corral: CORRAL_LEASE_TTL_SECONDS=99999999999999999999 is not a positive integer; using 1800s"),
                Triple("   ", 1_800L, ""),
            )
            for ((value, expectedTtl, expectedErr) in cases) {
                seenTtl.set(-1)
                val process = ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    "dev.corral.CorralCli", "claim")
                    .also {
                        it.environment()["CORRAL_URL"] = "http://127.0.0.1:${server.address.port}"
                        it.environment()["CORRAL_OWNER"] = "ttl-test"
                        it.environment()["CORRAL_LEASE_TTL_SECONDS"] = value
                    }
                    .start()
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "CLI hung for value '$value'")
                assertEquals(0, process.exitValue(), "CLI failed for value '$value': $stdout$stderr server=$serverError")
                assertEquals("dev1", stdout.trim(), "CLI did not print the claimed serial for '$value'")
                assertEquals(expectedTtl, seenTtl.get(), "wrong TTL for value '$value'")
                assertEquals(expectedErr.trim(), stderr.trim(), "wrong stderr for value '$value'")
            }
        } finally {
            server.stop(0)
        }
    }
}
