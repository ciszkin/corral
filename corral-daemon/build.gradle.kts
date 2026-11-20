plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

kotlin { jvmToolchain(17) }

// kotlin-sdk 0.16.0 (and the ktor 3.5.2 it pulls) drag kotlin-stdlib 2.4.21 in, whose metadata the
// project's Kotlin 2.2 compiler cannot read. 2.3.21 is the newest it can, and matches ktor's own
// kotlin-reflect — pinning any lower breaks kotlin-logging's reflection.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}

dependencies {
    implementation(project(":corral-common"))
    implementation(project(":corral-update"))
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
    // Keep the stable `corral-daemon-all.jar` name the shims/installer reference; the version
    // rides in the manifest and the corral-version resource instead.
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "dev.corral.MainKt"
        attributes["Implementation-Version"] = project.version
    }
}

tasks.processResources {
    from(rootProject.file("scripts/corral-rules"))
    val corralVersion = project.version.toString()
    inputs.property("corralVersion", corralVersion)
    val resourcesDir = destinationDir
    doLast { resourcesDir.resolve("corral-version").writeText(corralVersion) }
}
