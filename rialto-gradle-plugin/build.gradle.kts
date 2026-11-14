plugins {
    id("buijs.gradle-plugin")
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

tasks.test {
    systemProperty("rialto.spec", rootDir.resolve("../rialto-spec").absolutePath)
    inputs.dir(rootDir.resolve("../rialto-spec/examples")).withPropertyName("specExamples")
}
