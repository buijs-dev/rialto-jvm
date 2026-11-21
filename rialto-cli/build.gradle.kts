plugins {
    id("dev.buijs.conventions.kotlin")
    id("dev.buijs.conventions.repositories")
    id("dev.buijs.conventions.formatting")
    id("dev.buijs.conventions.coverage")
    application
    // The test contracts of the specification.
    id("rialto-spec")
}

description = "Rialto command line"

dependencies {
    implementation(project(":rialto-core"))
    runtimeOnly("org.slf4j:slf4j-nop:${libs.versions.slf4j.get()}")

    testImplementation(libs.bundles.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    applicationName = "rialto"
    mainClass.set("dev.buijs.rialto.cli.MainKt")
    // A short-lived command: C1 only and the serial collector start faster.
    applicationDefaultJvmArgs =
        applicationDefaultJvmArgs + listOf("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC")
}

kover {
    reports {
        filters {
            // `main` only exits with the code of `run`, which the tests call in process; exiting would end the test JVM.
            excludes { classes("dev.buijs.rialto.cli.MainKt") }
        }

        // Every line and branch is tested: a gap fails `check`.
        verify {
            rule {
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}
