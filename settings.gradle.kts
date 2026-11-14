pluginManagement {
    repositories {
        mavenLocal()
        // The dev.buijs convention plugins: their snapshots are published to Repsy.
        maven("https://repo.repsy.io/mvn/buijs-dev/maven") {
            mavenContent {
                snapshotsOnly()
                includeGroupByRegex("dev\\.buijs.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.buijs.conventions.settings") version "1.0.0-SNAPSHOT"
}

include(":rialto-cli")
include(":rialto-core")
include(":rialto-gradle-plugin")
