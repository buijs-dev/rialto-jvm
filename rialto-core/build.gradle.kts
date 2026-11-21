plugins {
    id("dev.buijs.conventions.library")
    // The test contracts and the JSON Schema of the specification.
    id("rialto-spec")
}

description = "Rialto tooling: the contract model, loading, bundling and validation of contracts"

dependencies {
    // Exposed: the contract model and diagnostics carry Jackson 3 nodes.
    api(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.json.schema.validator)

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
