# Rialto JVM

The JVM tooling for [Rialto](https://github.com/buijs-dev/rialto-spec): a library, a command line tool and a Gradle plugin
that read, check and bundle Rialto contracts.

A **Rialto contract** describes a service once: its domain types, its operations, its events, how it is stored and how
it is shown. It does not repeat what OpenAPI, AsyncAPI, SQL, etc. already say; it points into those documents. Generators
in any language read the same contract and produce code that agrees on identity, constraints, wire formats and columns.

This repository makes sure such a contract is **correct before anything is generated from it**. A contract that passes
`rialto validate` has a valid structure, and every name and every reference into its sources resolves. Each finding
comes with a code, a file, a line and a hint.

```shell
rialto validate shipments.rialto.yaml
#error[RIALTO-S002] shipments.rialto.yaml:25:30 #/storage/Shipment/columns/note: 'shipment.delivery_notes' does not exist in migration (K1.2)
```

## Contents

- [Quick start](#quick-start)
- [Features](#features)
- [Architecture](#architecture)
- [Development](#development)
- [Guide for contributors and AI agents](#guide-for-contributors-and-ai-agents)
- [Roadmap](#roadmap)

## Quick start
Use Gradle to install the rialto cli tool locally:
```shell
./gradlew :rialto-cli:installDist

alias rialto=$PWD/rialto-cli/build/install/rialto/bin/rialto
```

Then use it to run commands, for example validate a contract:
```shell
rialto validate ../rialto-spec/examples/contracts/valid/minimal.yaml
```

A small example contract that builds on an OpenAPI document and a set of SQL migrations:

```yaml
rialto: "1.0.0"
info: {name: shipments, version: 1.0.0}

sources:
  api: {kind: openapi, path: openapi.json, version: 1.0.0, status: current}
  db: {kind: sql, path: "db/migration/*.sql", dialect: postgresql}

types:
  Shipment:
    kind: entity
    identity: [id]
    properties:
      id: {type: uuid}
      note: {type: string, nullable: true}

operations:
  getShipment:
    rest: api#/paths/~1shipments~1{id}/get   # an operation in the OpenAPI document
    parameters: {id: id}
    output: Shipment

storage:
  Shipment:
    table: db#shipment                       # a table in the SQL schema
    columns: {id: public_id, note: delivery_note}
```

## Features

| Feature | What it is for | CLI | Gradle |
|---|---|---|---|
| Validation | Stop a broken contract before it reaches a generator | `rialto validate` | `rialtoValidate` |
| Contracts over several files | Split a large contract for its authors; tools still see one document | (every command) | (every task) |
| Bundling | Publish that one document | `rialto bundle` | `rialtoBundle` |
| Local sources | Check references into OpenAPI, AsyncAPI and SQL, including migrations | (every command) | (every task) |
| Remote sources | Build on external API, pinned to the reviewed version | `rialto sources update` | `rialtoSourcesUpdate` |
| Machine-readable findings | Editors and CI annotate the exact place of each finding | `validate --format json` | |
| Conformance | Prove that a tool, this one or your own, judges the test contracts of the specification as it must | `rialto conformance` | |

### Validation

`rialto validate <contract>...` checks each contract and exits with `0` when all are valid, `1` when one has errors,
`64` on wrong usage and `70` when rialto itself fails. It checks, in this order:

1. the format version (`rialto: "1.0.0"`);
2. the JSON Schema of the contract format;
3. the sources the contract refers to;
4. every reference: to types, shapes, operations, events and views, and into the sources (`api#/paths/…`,
   `db#table.column`), plus unique names and valid type expressions (`list<Shipment?>`, `decimal(8,3)`).

From slice 1 on, each profile adds its own rules to this list (see [Roadmap](#roadmap)).

For tools, `--format json` prints one JSON document instead of text:

```console
$ rialto validate --format json unknown-type.yaml
{
  "contracts" : [ {
    "file" : "unknown-type.yaml",
    "valid" : false,
    "diagnostics" : [ {
      "severity" : "error", "code" : "RIALTO-K004", "requirement" : "K1.2",
      "file" : "unknown-type.yaml", "line" : 11, "column" : 15,
      "pointer" : "/types/Thing/properties/owner/type",
      "message" : "Unknown type 'Person'", "hint" : null
    } ]
  } ],
  "valid" : false
}
```

Every code (`K` for the format, `S` for sources, `P` for profile rules) is listed in
[docs/diagnostics.md](docs/diagnostics.md). The codes are those of the specification, so CI and editors can match on
them.

### Contracts over several files

A root lists its fragments under `include`, with `*` (within a folder) and `**` (across folders):

```yaml
rialto: "1.0.0"
info: {name: shipments, version: 1.0.0}
include: [parts/**/*.rialto.yaml]
```

Every command reads the merged, bundled form, and findings still point at the fragment and line they are in. A name
may be declared in only one file. `rialto bundle --output bundled.rialto.yaml contract.rialto.yaml` writes the merged
document and rewrites local source paths so that they still resolve from the new location.

### Sources

| Kind | Referenced as | Example |
|---|---|---|
| `openapi`, `asyncapi` | JSON Pointer into the document | `api#/paths/~1shipments/get` |
| `sql` | table, or table and column (any case) | `db#shipment`, `db#shipment.public_id` |

A SQL source can be a single schema file or a glob over migrations (`db/migration/*.sql`). The migrations are applied
in version order (`V1__`, `V2__`, `V1.10__`). The schema is read from the DDL itself, so no database is needed.

### Remote sources

A source path may be an `https` URL. The first run fetches it and pins its SHA-256 in `rialto.lock` next to the
contract. **Commit that file.** Later runs read the pinned copy from the cache (`~/.cache/rialto`, or `RIALTO_CACHE`).
When the upstream document changes, the build fails until someone accepts the new version on purpose:

```shell
rialto validate shipments.rialto.yaml
# error[RIALTO-S004] shipments.rialto.yaml:5:11 #/sources/api/path: https://… changed since it was locked in rialto.lock (K4.2)
# hint: Run `rialto sources update` (or rialtoSourcesUpdate) to accept the new version

# update it
rialto sources update shipments.rialto.yaml
# https://…/openapi.json: 3f1a… -> 9c2e…
# 1 remote source(s) updated in rialto.lock
```

`--offline` (Gradle: `--offline`) reads only the cache. Runs that work on the same contract, such as Gradle and the
command line, share the cache and change `rialto.lock` under a file lock, replacing it atomically. A lock that cannot be read is an error (`RIALTO-K010`);
it is never treated as an empty lock.

### Gradle plugin
Apply the plugin and point to a rialto contract file.
```kotlin
plugins { 
    id("dev.buijs.rialto") version "<version>" 
}

rialto { 
    contract.set(file("contract.rialto.yaml")) 
}
```

The Gradle plugin adds tasks for managing the contract:

```shell

./gradlew rialtoValidate                     # fails the build on errors
./gradlew rialtoBundle                       # build/rialto/bundled.rialto.yaml
./gradlew rialtoSourcesUpdate                # accept new versions of remote sources

```

The tasks track every input (contract, fragments, local sources, migrations and `rialto.lock`), so they are up to
date, or come from the build cache, until one of those changes.

### Conformance

The specification comes with test contracts: small contracts that are valid, or invalid with the codes they must be
rejected with (`# expect: RIALTO-…` in their first lines). `rialto conformance` runs a command once per test contract
and reports which it passes:

```sh
rialto conformance --spec ../rialto-spec --jobs 8 -- rialto validate {contract}
```

```text
PASS valid/minimal.yaml
PASS invalid/unknown-type.yaml
FAIL invalid/map-key.yaml: missing RIALTO-K006

21 passed, 1 failed; command: rialto validate {contract}
```

- `--spec <dir>` is a checkout of rialto-spec or its unpacked artifact: the directory that holds `examples/contracts`.
- The command after `--` is the tool under test, in any language. `{contract}` is the path of the test contract;
  without it the path comes last. The command runs in the directory of the test contract.
- A valid test contract passes when the command exits with `0`. An invalid one passes when the command exits with
  another status and its output names every expected code.
- `--jobs <n>` runs `<n>` test contracts at a time; `--verbose` prints the output of each failing run.
- The exit code is `0` when every test contract passes, and `1` otherwise.

### Library

```kotlin
when (val outcome = ContractValidator.validate(Path.of("shipments.rialto.yaml"))) {
    is Outcome.Ok -> {
        val contract: Contract = outcome.value.contract     // the typed model of the whole document
    }
    is Outcome.Nok -> {
        outcome.diagnostics.forEach(::println)
    }
}
```

Every function that can find mistakes returns an `Outcome`: `Ok` with the value and any warnings, or `Nok` with the
findings, of which at least one is an error. The value is only reachable through `Ok`, so a caller cannot go on with
the result of a step that failed.

## Architecture

| Module | Role |
|---|---|
| `rialto-core` | Everything that matters: the contract model, reading, bundling, sources, the checks and the diagnostics. Packages under `dev.buijs.rialto.core`: `diagnostics`, `document`, `model`, `schema`, `files`, `sources`, `validation`, `conformance` and `utilities`. |
| `rialto-cli` | The `rialto` command; a thin layer that parses arguments and prints results. |
| `rialto-gradle-plugin` | Plugin `dev.buijs.rialto`; the same rialto-cli commands but as Gradle tasks, with their inputs tracked. |

The CLI and the plugin hold no rules of their own, so they always give the same verdict. All validation flows through
one pipeline in `ContractValidator.validate`. Each step starts only when the steps before it found no error:

```text
file ──► ContractFiles.read ──► version ──► ContractSchema ──► model (Contract)
            (bundles include)                                       │
                                                                    ▼
   Outcome<ValidContract> ◄── ContractChecks (FormatChecks, …) ◄── Sources.load
   (Ok: contract, Nok: findings)                                     (local, remote, SQL)
```

| Class | Responsibility |
|---|---|
| `ContractValidator` | Entry point: runs the pipeline, returns `Outcome<ValidContract>` |
| `ContractFiles` | Reads a root with its fragments as one document; bundling; build inputs |
| `ContractSchema` | The JSON Schema of the format (equal byte for byte to the specification's) |
| `LocatedDocument` | A YAML/JSON tree that remembers file, line and column per JSON Pointer |
| `Contract` (`*Model.kt`) | The typed model, one to one with the document, in one file per section |
| `Sources`, `SqlSchema` | Loading sources and resolving `<source>#<location>` references |
| `RemoteSources`, `RialtoLock`, `RialtoCache` | Fetching, pinning and caching remote sources |
| `ContractChecks`, `FormatChecks` | Rule sets over the model; each profile adds one |
| `Outcome`, `Diagnostic`, `Code` | What a step returns (`Ok` or `Nok`), a finding, and the shared table of codes |
| `ContractWriter`, `ContractMapper` | Writing the model back to YAML; the one Jackson configuration of every reader and writer |

## Development

The tests of `rialto-core` and `rialto-cli` read the test contracts, the JSON Schema and `contract.md` of
[rialto-spec](https://github.com/buijs-dev/rialto-spec) from its published artifact `dev.buijs.rialto:rialto-spec`, at
`rialtoSpecVersion` in `gradle.properties`, on the test classpath as the resource `/rialto-spec` (see the `rialto-spec`
plugin in `build-logic`). For a slice that changes the specification too, test against a checkout:
`./gradlew check -Prialto.spec=../rialto-spec`. The tests of the Gradle plugin have their own
contracts in `src/test/resources`; they test the tasks, not the rules.

```sh
./gradlew check kotlinStyleCheck        # tests, 100% coverage gate, style
./gradlew kotlinStyleApply              # fix style
./gradlew :rialto-cli:installDist       # build the rialto command

# the specification's conformance suite, run against the installed command (needs a checkout of rialto-spec)
rialto="$PWD/rialto-cli/build/install/rialto/bin/rialto"
"$rialto" conformance --spec ../rialto-spec --jobs 8 -- "$rialto" validate {contract}
```

- **Tests:** JUnit 6 and AssertJ; MockK only where a JDK call has to fail.
- **Coverage:** `check` requires 100% of lines and branches in every module (Kover). The only exclusion is `main` of
  the CLI, which just exits with the code of `run`.
- **Build:** the `dev.buijs.conventions.settings` and `dev.buijs.kotlin-style` plugins, resolved from Maven Local or
  Repsy.

## Guide for contributors and AI agents

- **The specification is the source of truth.** Behavior follows [rialto-spec](https://github.com/buijs-dev/rialto-spec).
  Code and KDoc cite its requirement ids (`K1.2`, `P4.13`). The bundled schema and the `Code` table must equal the
  specification's; tests enforce both.
- **Add a rule as a check, not in the pipeline.** A profile's rules are a new `ContractChecks` added to
  `ContractValidator.checks`. They read the model through `CheckContext` and report through it. They never throw and
  never read files.
- **Report, never throw.** Every problem with a contract becomes a `Diagnostic` with a standard code and a location.
  New codes come from the specification first.
- **Keep the CLI and the plugin thin.** Behavior belongs in `rialto-core`, so both front ends stay in step.
- **Definition of done:** `./gradlew check kotlinStyleCheck` is green (tests, 100% coverage, style), the conformance
  suite passes, and public classes have KDoc that explains their purpose.

## Roadmap

Rialto reaches 1.0.0 in slices. Each slice adds one part of the specification to the specification itself, to this
tooling and to the generators.

| Slice | Scope | Adds to this repository | Status |
|---|---|---|---|
| 0 | Foundation | The whole contract format: model, reading, include and bundle, local and remote sources, format checks; `validate`, `bundle`, `sources update` | ✅ current |
| 1 | Domain and mapping | Profile checks, annotations, `export` | planned |
| 2 | REST and errors | Profile checks | planned |
| 3 | Persistence | Profile checks | planned |
| 4 | Query | Profile checks | planned |
| 5 | Lifecycle | Profile checks | planned |
| 6 | Auth | Profile checks | planned |
| 7 | Events | Profile checks | planned |
| 8 | Presentation | Profile checks | planned |
| 9 | Versions and compatibility | `diff` between contract versions | planned |
| 10 | Ecosystem | Generator API and protocol, `generate`, generator pins in `rialto.lock`, registry, Maven resolver | planned |
