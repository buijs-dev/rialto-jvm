# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `rialto-core`, package `contract`: the model of the whole contract format 1.0.0; loading of YAML and JSON with
  line and column for every element; validation against the JSON Schema of the specification (a copy, checked byte
  for byte against rialto-spec), `include` included; contracts over several files (`include`, K5) and their bundled
  form; local sources (OpenAPI, AsyncAPI and SQL migrations) and remote sources over `https`, pinned in the `sources`
  section of `rialto.lock` (K4), with `RIALTO-K010` for a lock that cannot be read or written (K4.5); the checks of
  the format: references (`RIALTO-K004`, `RIALTO-S002`), names (`RIALTO-K005`) and type expressions
  (`RIALTO-K006`); the diagnostics with the full table of standard codes; the contract writer.
- The checks over the model run as a list of check sets (`ContractValidator.checks`); each profile adds its own.
- `rialto-cli`: `rialto validate` (with `--format json` and `--offline`), `rialto bundle` and `rialto sources update`;
  any other command prints the usage.
- `rialto-gradle-plugin` (`dev.buijs.rialto`): `rialtoValidate`, `rialtoBundle` and `rialtoSourcesUpdate`.
- CI: build, tests with coverage and conformance against rialto-spec; snapshots from `develop`; releases to Maven
  Central through prepare-release and publish-release.
