# rialto-jvm

Rialto tooling for the JVM: loading, bundling and validating [Rialto contracts](https://github.com/buijs-dev/rialto-spec),
the `rialto` command line and the Gradle plugin.

| Module | Purpose |
|---|---|
| `rialto-core` | the package `contract`: the model of the whole contract format, loading of YAML and JSON with positions, JSON Schema validation, contracts over several files (`include`) and their bundled form, local sources (OpenAPI, AsyncAPI, SQL) and remote sources pinned in `rialto.lock`, the checks of references and names, the diagnostics and the writer |
| `rialto-cli` | `rialto validate` (`--format json` for tools), `rialto bundle` and `rialto sources update` |
| `rialto-gradle-plugin` | plugin `dev.buijs.rialto`: `rialtoValidate`, `rialtoBundle` and `rialtoSourcesUpdate` |

```sh
./gradlew check kotlinStyleCheck
./gradlew :rialto-cli:installDist
rialto-cli/build/install/rialto/bin/rialto validate ../rialto-spec/examples/contracts/valid/minimal.yaml
rialto-cli/build/install/rialto/bin/rialto validate --format json <contract>...
rialto-cli/build/install/rialto/bin/rialto bundle --output <file> <contract>
rialto-cli/build/install/rialto/bin/rialto sources update <contract>
```

```kotlin
plugins { id("dev.buijs.rialto") version "<version>" }

rialto { contract.set(file("contract.rialto.yaml")) }
```

**Validation.** `ContractValidator.validate` reads the contract (the bundled form, for a root with `include`), checks
the format version and the JSON Schema, loads the sources, and then runs each set of checks over the model, in order.
Slice 0 has one set: the checks of the format itself (references to types, shapes, operations, events, views and into
sources, names, type expressions). Each profile adds its own set to `ContractValidator.checks`.

**Contracts over several files.** A root with `include` (K5) is read as its bundled form by every command and task;
diagnostics point at the fragment and line. `rialto bundle --output <file> <root>` (Gradle: `rialtoBundle`) writes
that one document, for publishing or for tools that read a single file.

**Remote sources.** A source `path` may be an `https` URL (K4). The first run fetches it and pins its SHA-256 in
`rialto.lock` next to the contract; commit that file. Later runs read the pinned copy from the cache
(`~/.cache/rialto`, or `RIALTO_CACHE`), and a changed upstream document fails with `RIALTO-S004` until
`rialto sources update <contract>` (Gradle: `rialtoSourcesUpdate`) accepts it. `--offline` (Gradle: `--offline`) reads
the cache only. The JVM and the Dart tooling change `rialto.lock` under a shared file lock (in `~/.cache/rialto/locks`)
and replace it atomically. A lock that cannot be read, or cannot be written when it must change, fails with
`RIALTO-K010` (K4.5); it is never read as an empty lock. This release writes the `sources` section only; the generator pins come with slice 10.

Diagnostic codes are listed in [docs/diagnostics.md](docs/diagnostics.md).

**Tests.** The tests read the test contracts and the JSON Schema of a checkout of
[rialto-spec](https://github.com/buijs-dev/rialto-spec) next to this one (`../rialto-spec`): the bundled schema must
equal the specification's byte for byte, and the code table must equal its standard diagnostic codes. The conformance
runner of the specification runs the installed command line over every test contract:

```sh
python3 ../rialto-spec/tools/conformance.py --jobs 8 -- "$PWD/rialto-cli/build/install/rialto/bin/rialto" validate {contract}
```

The build uses the `dev.buijs.conventions.settings` and `dev.buijs.kotlin-style` plugins (gradle-conventions and kotlin-style),
resolved from Maven Local or the snapshots on Repsy.

## Status

Rialto reaches 1.0.0 in slices: each slice adds one part of the specification to every repository. This is slice 0,
the foundation: the whole contract format, without the rules of the profiles.

| Slice | Adds here |
|---|---|
| 0 | the contract format: model, loading, bundling, sources, `validate`, `bundle`, `sources update` |
| 1 to 8 | per profile (domain and mapping, REST and errors, persistence, query, lifecycle, auth, events, presentation): its checks; slice 1 also the annotations and `export` |
| 9 | versions and compatibility: `diff` |
| 10 | the ecosystem: the generator API and protocol, `generate`, generator pins in `rialto.lock`, the registry and the Maven resolver |
