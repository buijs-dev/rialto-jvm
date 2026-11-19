plugins {
    id("dev.buijs.conventions.library")
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

// The test contracts and the JSON Schema live in the specification repository.
val spec = rootDir.resolve("../rialto-spec")

tasks.test {
    systemProperty("rialto.spec", spec.absolutePath)
    inputs.dir(spec.resolve("examples")).withPropertyName("specExamples")
    inputs.file(spec.resolve("spec/contract-1.0.0.schema.json")).withPropertyName("specSchema")
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
