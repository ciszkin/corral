plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.shadow)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":corral-common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.compose.desktop)
    runtimeOnly(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.testhost)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.contentnegotiation)
    testImplementation(libs.ktor.server.websockets)
}

tasks.shadowJar {
    archiveBaseName.set("corral-ui")
    manifest { attributes["Main-Class"] = "dev.corral.ui.CorralUiKt" }
}
