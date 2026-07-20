rootProject.name = "receipt-print"

include(":receipt-core")
include(":receipt-command-escpos")
include(":receipt-command-star")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
