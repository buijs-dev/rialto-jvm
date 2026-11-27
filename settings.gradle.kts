pluginManagement {
    includeBuild("build-logic")

    repositories {
        mavenLocal()
        maven("https://repo.repsy.io/mvn/buijs-dev/maven")
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
