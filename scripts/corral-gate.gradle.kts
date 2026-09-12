// corral gradle gate - pre-flight lease check for device-touching tasks.
// Kotlin DSL init script; written by install.sh with @CORRAL_SHIM@ substituted
// to the absolute corral shim path.
// The check runs at TASK EXECUTION (doFirst), not during configuration, so gated
// builds stay configuration-cache compatible. The doFirst action captures only
// plain strings (via registerGate's parameters) - never the script instance - so
// the configuration cache can serialize it.
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.invocation.Gradle

val patterns: List<Regex> = run {
    val envPatterns = System.getenv("CORRAL_GRADLE_TASKS")
    if (!envPatterns.isNullOrBlank()) {
        envPatterns.split("[|,]".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }
    } else {
        val cfg = File(System.getProperty("user.home"), ".config/corral/gradle-tasks")
        if (cfg.exists()) cfg.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        else emptyList()
    }
}.ifEmpty { listOf("install.*", "connected.*AndroidTest") }.map { Regex(it) }

val serial = System.getenv("ANDROID_SERIAL")
val ownerProp = gradle.startParameter.projectProperties["corralOwner"]
val taskHint = gradle.startParameter.taskNames.joinToString(" ")
val corralBin = "@CORRAL_SHIM@"

fun registerGate(
    gradle: Gradle,
    patterns: List<Regex>,
    corralBin: String,
    ownerProp: String?,
    serial: String?,
    taskHint: String
) {
    gradle.allprojects {
        tasks.configureEach {
            if (patterns.any { name.matches(it) }) {
                doFirst {
                    if (!File(corralBin).canExecute()) {
                        throw GradleException("corral: gate disabled - corral shim missing at $corralBin")
                    }
                    // -PcorralOwner (injected by `corral lease --all`) is the guaranteed owner
                    // path; without it, corral check resolves the owner itself from the
                    // forwarded client env.
                    val checkArgs =
                        if (ownerProp != null) arrayOf(corralBin, "check", "--owner", ownerProp)
                        else arrayOf(corralBin, "check")
                    val args = if (serial != null) checkArgs + serial else checkArgs
                    val p = ProcessBuilder(*args).redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText()
                    val rc = p.waitFor()
                    if (rc != 0) {
                        val leaseHint =
                            if (serial != null) "Lease $serial first:  corral claim $serial"
                            else "Lease the device(s) first:  corral lease --all -- $taskHint"
                        throw GradleException("corral: device lease gate failed\n$out\n$leaseHint")
                    }
                }
            }
        }
    }
}

registerGate(gradle, patterns, corralBin, ownerProp, serial, taskHint)