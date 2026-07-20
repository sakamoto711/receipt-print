rootProject.name = "receipt-print"

include(":receipt-core")
include(":receipt-command-escpos")
include(":receipt-command-star")
include(":receipt-receiptline")

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
