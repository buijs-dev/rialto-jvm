# Changelog

All notable changes for users of this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

The first release, slice 0: the whole Rialto contract format 1.0.0, without the rules of the profiles.

### Requirements

- Java 25 or later.
- `dev.buijs.rialto:rialto-core` exposes Jackson 3 (`tools.jackson`) in its API: the model and the documents carry
  `JsonNode` values.

### Added

**Validation** (`rialto-core`)

- `ContractValidator.validate(file)` checks a contract and returns an `Outcome`: `Ok` with the valid contract (the
  typed `Contract` model, its document and its loaded sources) and any warnings, or `Nok` with every finding. It never
  throws for an invalid contract, and the model is only reachable through `Ok`.
- Every function that can find mistakes returns an `Outcome` the same way: `ContractFiles.read` and `bundle`,
  `RemoteSources.update`.
- The checks, in order: the format version, the JSON Schema of the specification, the sources, then every reference
  (types, shapes, operations, events, views, and into sources), unique names and valid type expressions, also the
  `context` of a view slot. An invalid type is `RIALTO-K006` at the property, a `binary` in a collection
  `RIALTO-P021`.
- A YAML contract or fragment with a tag beyond the core schema, an anchor, an alias or a key that is not a string is
  `RIALTO-K001` at its line (K1.11). OpenAPI and AsyncAPI sources may use all of YAML.
- Fragments and migrations without a version are read in code point order, as the specification says.
- A `rest` binding must name an OpenAPI operation, `<source>#/paths/<path>/<method>`: one that resolves to a path item
  or to an element inside an operation is `RIALTO-S002`.
- `ExternalRef`, `RestOperation`, `Sources.table` and `Sources.element` are public: a generator looks up a reference
  of a valid contract the way validation resolved it.
- Each finding is a `Diagnostic` with a standard code (`RIALTO-K…`, `RIALTO-S…`), the requirement it enforces, a
  message, an optional hint and a location: file, line, column and JSON Pointer. The codes are the specification's. See
  [docs/diagnostics.md](docs/diagnostics.md).

**Contracts over several files**

- A root lists its fragments under `include` (`*` and `**`). Every command reads the merged document, and findings
  name the fragment and line they are in.
- `ContractFiles.bundle` writes that merged document and moves local source paths along with it.
- `ContractFiles.inputs` lists every file a result depends on, for build tools.

**Sources**

- References into OpenAPI and AsyncAPI documents (by JSON Pointer) and into SQL schemas (`table` or `table.column`).
- A SQL source can be a glob over migrations, applied in version order. No database is needed.
- Remote sources over `https`, pinned by SHA-256 in the lock of the contract (`shipment.rialto.lock` next to
  `shipment.rialto.yaml`, naming the contract it belongs to) and cached in
  `~/.cache/rialto` (or `RIALTO_CACHE`). A changed upstream document fails until `RemoteSources.update` (or
  `rialto sources update`) accepts it. `RemoteMode.OFFLINE` reads only the cache.
- Runs that work on the same contract change its lock under a file lock and replace it atomically. Contracts in one
  directory each have their own lock. A lock that cannot be read, or belongs to another contract, is an error
  (`RIALTO-K010`), never an empty lock.

**Model**

- `Contract` and its sections mirror the document one to one, including the top-level `x-…` extension keys.
- `ContractWriter` writes a model back to YAML. Reading that YAML gives the same model.

**Command line** (`rialto-cli`)

- `rialto validate [--format json] [--offline] <contract>...`: text, or one JSON document for editors and CI.
- `rialto bundle --output <file> <contract>`.
- `rialto sources update <contract>`.
- `rialto conformance --spec <dir> [--jobs <n>] [--verbose] -- <command>...`: runs a command once per test contract of
  the specification and reports which it passes. The command can be this command line or the tool of someone else.
- Exit codes: `0` success, `1` errors or a file that cannot be written, `64` wrong usage, `70` when rialto itself
  fails.

**Conformance** (`rialto-core`)

- `Conformance.run(spec, command)` is the conformance runner as a library: one `ConformanceResult` per test contract.

**Gradle plugin** (`dev.buijs.rialto`)

- `rialto { contract.set(file("contract.rialto.yaml")) }`, with the tasks `rialtoValidate`, `rialtoBundle` (to
  `build/rialto/bundled.rialto.yaml`) and `rialtoSourcesUpdate`. Gradle's `--offline` reads remote sources from the
  cache only.
- `rialtoValidate` and `rialtoBundle` track the contract, its fragments, local sources, migrations and the lock.
  They are cacheable and stay up to date until one of those changes.
