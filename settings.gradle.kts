pluginManagement {
    repositories {
        mavenLocal()
        // The dev.buijs build tooling (convention and style plugins): releases and snapshots on Repsy.
        maven("https://repo.repsy.io/buijs-dev/maven2") {
            mavenContent {
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
