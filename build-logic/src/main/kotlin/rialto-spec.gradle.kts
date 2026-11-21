// The test contracts, the JSON Schema and contract.md of the specification, for the tests of this module: the
// published artifact dev.buijs.rialto:rialto-spec at rialtoSpecVersion (gradle.properties), unpacked in
// build/rialto-spec. The tests read the directory from the system property rialto.spec.
//
// For a slice that changes the specification too, test against a checkout instead:
//   ./gradlew check -Prialto.spec=../rialto-spec

val rialtoSpec = configurations.create("rialtoSpec") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    rialtoSpec("dev.buijs.rialto:rialto-spec:${property("rialtoSpecVersion")}@zip")
}

val checkout = providers.gradleProperty("rialto.spec").map { rootProject.file(it) }

val unpackRialtoSpec = tasks.register<Sync>("unpackRialtoSpec") {
    description = "Unpacks the test contracts of the specification for the tests."
    from(rialtoSpec.elements.map { files -> files.map { zipTree(it) } })
    into(layout.buildDirectory.dir("rialto-spec"))
}

// Applied before or after the Java plugin: configure the test task once it exists.
pluginManager.withPlugin("java") {
    tasks.named<Test>("test") {
        val spec =
            if (checkout.isPresent) {
                checkout.get()
            } else {
                dependsOn(unpackRialtoSpec)
                layout.buildDirectory.dir("rialto-spec").get().asFile
            }
        systemProperty("rialto.spec", spec.absolutePath)
        inputs.dir(spec).withPropertyName("rialtoSpec")
    }
}
