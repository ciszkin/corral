dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "corral"
include("corral-common", "corral-daemon", "corral-ui")
