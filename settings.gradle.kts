import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

rootProject.name = "Input Bridge"
include(":app")
include(":protocol")
include(":android-studio-plugin")
include(":core:datastore")
include(":core:designsystem")
include(":core:data")
include(":core:framing")
include(":core:connection-client")
include(":core:connection-server")
