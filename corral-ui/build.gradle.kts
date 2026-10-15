plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.shadow)
}

kotlin { jvmToolchain(17) }

compose.resources {
    packageOfResClass = "dev.corral.ui.resources"
}

dependencies {
    implementation(project(":corral-common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.compose.desktop)
    implementation(compose.components.resources)
    implementation(libs.koin.core)
    runtimeOnly(compose.desktop.currentOs)
    // Bundle non-host Skiko natives: shadowJar otherwise carries only the build host's runtime
    // (linux-x64), so the console dies on Windows with LibraryLoadException (skiko-windows-x64.dll).
    runtimeOnly(libs.skiko.awt.runtime.windows.x64)
    runtimeOnly(libs.skiko.awt.runtime.macos.arm64)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.testhost)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.contentnegotiation)
    testImplementation(libs.ktor.server.websockets)
}

tasks.shadowJar {
    archiveBaseName.set("corral-ui")
    // Keep the stable `corral-ui-all.jar` name the launcher/installer reference; the version
    // rides in the manifest and the corral-version resource instead.
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "dev.corral.ui.CorralUiKt"
        attributes["Implementation-Version"] = project.version
    }
}

// The console carries its OWN version: the two jars are installed and updated separately and can
// disagree, so the UI must be able to report the version baked into the UI jar.
tasks.processResources {
    val corralVersion = project.version.toString()
    inputs.property("corralVersion", corralVersion)
    val resourcesDir = destinationDir
    doLast { resourcesDir.resolve("corral-version").writeText(corralVersion) }
}
