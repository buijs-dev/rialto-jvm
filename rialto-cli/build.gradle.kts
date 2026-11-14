plugins {
    id("buijs.kotlin")
    id("buijs.repositories")
    id("buijs.formatting")
    application
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
    systemProperty("rialto.spec", rootDir.resolve("../rialto-spec").absolutePath)
    inputs.dir(rootDir.resolve("../rialto-spec/examples")).withPropertyName("specExamples")
}

application {
    applicationName = "rialto"
    mainClass.set("dev.buijs.rialto.cli.MainKt")
    // A short-lived command: C1 only and the serial collector start faster.
    applicationDefaultJvmArgs =
        applicationDefaultJvmArgs + listOf("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC")
}
