dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "corral"
include("corral-common", "corral-update", "corral-daemon", "corral-ui")
