plugins {
    id("dev.buijs.conventions.gradle-plugin")
}

description = "Gradle plugin that validates and bundles Rialto contracts"

gradlePlugin {
    plugins {
        create("rialto") {
            id = "dev.buijs.rialto"
            implementationClass = "dev.buijs.rialto.gradle.RialtoPlugin"
        }
    }
}

dependencies {
    implementation(project(":rialto-core"))

    testImplementation(gradleTestKit())
    testImplementation(libs.bundles.test)
    testImplementation(kotlin("test"))
}

kover {
    reports {
        // Every line and branch is tested: a gap fails `check`.
        verify {
            rule {
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}
