pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "keystone"

include(":app")

include(":core:crypto")
include(":core:identity")
include(":core:trust")
include(":core:transport:api")
include(":core:transport:bluetooth")
include(":core:transport:wifidirect")
include(":core:transport:reticulum")
include(":core:sync")
include(":core:database")
include(":core:ui")
include(":core:currency")

include(":feature:onboarding")
include(":feature:vault")
include(":feature:marketplace")
include(":feature:coordination")
include(":feature:directory")
