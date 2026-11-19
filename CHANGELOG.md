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

- `ContractValidator.validate(file)` checks a contract and returns a `ValidationResult`: the typed `Contract` model and
  every finding. It never throws for an invalid contract. Use `valid` before you read the model.
- The checks, in order: the format version, the JSON Schema of the specification, the sources, then every reference
  (types, shapes, operations, events, views, and into sources), unique names and valid type expressions.
- Each finding is a `Diagnostic` with a standard code (`RIALTO-K…`, `RIALTO-S…`), the requirement it enforces, a
  message, an optional hint and a location: file, line, column and JSON Pointer. The codes are the specification's and
  are the same in rialto-dart. See [docs/diagnostics.md](docs/diagnostics.md).

**Contracts over several files**

- A root lists its fragments under `include` (`*` and `**`). Every command reads the merged document, and findings
  name the fragment and line they are in.
- `ContractFiles.bundle` writes that merged document and moves local source paths along with it.
- `ContractFiles.inputs` lists every file a result depends on, for build tools.

**Sources**

- References into OpenAPI and AsyncAPI documents (by JSON Pointer) and into SQL schemas (`table` or `table.column`).
- A SQL source can be a glob over migrations, applied in version order. No database is needed.
- Remote sources over `https`, pinned by SHA-256 in `rialto.lock` next to the contract and cached in
  `~/.cache/rialto` (or `RIALTO_CACHE`). A changed upstream document fails until `RemoteSources.update` (or
  `rialto sources update`) accepts it. `RemoteMode.OFFLINE` reads only the cache.
- `rialto.lock` and the cache are shared with rialto-dart. Both change the lock under the same file lock and replace it
  atomically. A lock that cannot be read is an error (`RIALTO-K010`), never an empty lock.

**Model**

- `Contract` and its sections mirror the document one to one, including the top-level `x-…` extension keys.
- `ContractWriter` writes a model back to YAML. Reading that YAML gives the same model.

**Command line** (`rialto-cli`)

- `rialto validate [--format json] [--offline] <contract>...`: text, or one JSON document for editors and CI.
- `rialto bundle --output <file> <contract>`.
- `rialto sources update <contract>`.
- Exit codes: `0` valid, `1` errors, `64` wrong usage.

**Gradle plugin** (`dev.buijs.rialto`)

- `rialto { contract.set(file("contract.rialto.yaml")) }`, with the tasks `rialtoValidate`, `rialtoBundle` (to
  `build/rialto/bundled.rialto.yaml`) and `rialtoSourcesUpdate`. Gradle's `--offline` reads remote sources from the
  cache only.
- `rialtoValidate` and `rialtoBundle` track the contract, its fragments, local sources, migrations and `rialto.lock`.
  They are cacheable and stay up to date until one of those changes.
