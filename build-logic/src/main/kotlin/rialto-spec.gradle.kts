// The test contracts, the JSON Schema and the Markdown of the specification, for the tests of this module: the
// published artifact dev.buijs.rialto:rialto-spec at rialtoSpecVersion (gradle.properties), copied to
// build/rialto-spec/rialto-spec. That directory is on the test runtime classpath, so the tests read the specification
// as the resource /rialto-spec.
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

val copyRialtoSpec = tasks.register<Sync>("copyRialtoSpec") {
    description = "Copies the specification to the test classpath, as the resource /rialto-spec."
    if (checkout.isPresent) {
        from(checkout)
    } else {
        from(zipTree(rialtoSpec.elements.map { it.single().asFile }))
    }
    // Generated and downloaded directories of a checkout, which are not the specification's.
    listOf(".git", ".idea", "site", "build", ".gradle", ".dart_tool", "node_modules").forEach { exclude("**/$it/**") }
    into(layout.buildDirectory.dir("rialto-spec/rialto-spec"))
}

// Applied before or after the Java plugin: add to the test classpath once it exists.
pluginManager.withPlugin("java") {
    dependencies.add("testRuntimeOnly", files(layout.buildDirectory.dir("rialto-spec")).builtBy(copyRialtoSpec))
}
