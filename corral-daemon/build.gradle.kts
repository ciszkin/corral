plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":corral-common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.contentnegotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.mcp.server)
    testImplementation(kotlin("test"))
    testImplementation(libs.mcp)
    testImplementation(libs.mcp.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.testhost)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.contentnegotiation)
    testImplementation(libs.ktor.client.websockets)
}

tasks.test {
    // L1 hermeticity: the daemon's file-backed config must not read the developer's ~/.config.
    val testHome = layout.buildDirectory.dir("test-home")
    environment("CORRAL_RULES", testHome.get().asFile.resolve("rules").absolutePath)
    environment("CORRAL_ENV_FILE", testHome.get().asFile.resolve("daemon.env").absolutePath)
}

tasks.shadowJar {
    archiveBaseName.set("corral-daemon")
    manifest { attributes["Main-Class"] = "dev.corral.MainKt" }
}

tasks.processResources {
    from(rootProject.file("scripts/corral-rules"))
}
