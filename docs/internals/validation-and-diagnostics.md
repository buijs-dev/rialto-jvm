# Validation and diagnostics: internals

How rialto-jvm validates a contract and reports its findings. What a tool must do, and why, is in the guide
[Validation and diagnostics](https://github.com/buijs-dev/rialto-spec/blob/develop/docs/guides/validation-and-diagnostics.md)
and in [C5](https://github.com/buijs-dev/rialto-spec/blob/develop/spec/core.md#c5-validation-configuration-and-diagnostics)
and [K1](https://github.com/buijs-dev/rialto-spec/blob/develop/spec/contract.md#k1-document); the codes, their
requirements and the JSON output are in [diagnostics](../diagnostics.md). This document is about the code. All classes
are in `rialto-core`, package `dev.buijs.rialto.core`, unless the table names another module.

## Overview

| Class | Role |
|---|---|
| `validation.ContractValidator` | The entry point: `validate(file, remote)` runs the steps in order; `SUPPORTED_VERSION`, `checks`, `model` |
| `validation.ValidContract` | A contract that passed: the model, its `LocatedDocument` and its loaded `Sources`; only inside an `Outcome.Ok` |
| `validation.ContractChecks`, `CheckContext` | One set of checks over the model, and what it reads and reports into |
| `validation.FormatChecks` | The checks of the format: references (K1.2), names (K1.3) and type expressions (K2.1), section by section |
| `validation.format.*References` | Per section, the references it holds, as `Reference` values; read the model only |
| `validation.format.ReferenceChecks` | Reports a `Reference` that does not resolve: `RIALTO-K004`, `RIALTO-S002` |
| `validation.format.TypeExpressionChecks` | `TypeUse` and the type expressions of `types` and of view slots: `RIALTO-K006`, `RIALTO-P021` |
| `validation.format.PropertyPathChecks`, `NameChecks` | The paths of projections and shapes (`RIALTO-K004`); a name that is both a type and a shape (`RIALTO-K005`) |
| `schema.ContractSchema` | The JSON Schema of the format, and `check`, which turns each violation into a `RIALTO-K002` |
| `document.LocatedDocument`, `Position` | A document as a tree, with the file, line and column of every element by JSON Pointer |
| `document.ContractMapper` | The Jackson factories and mappers: strict JSON, YAML 1.2 |
| `document.JsonYaml` | K1.11: a YAML contract or fragment uses only what JSON can express |
| `files.ContractFiles` | Step 1: the file, or the bundled form of a contract over several files (K5) |
| `sources.Sources` | Step 5: the OpenAPI, AsyncAPI and SQL sources, local or remote |
| `diagnostics.Diagnostic`, `Location`, `Severity` | One finding: what, where, how to fix it, and whether it fails the run |
| `diagnostics.Diagnostics` | Collects the findings inside one step (internal) |
| `diagnostics.Outcome` | `Ok` with a value and warnings, or `Nok` with at least one error |
| `diagnostics.Code` | The standard diagnostic codes, each with its first requirement and a summary |
| `rialto-cli`: `Commands.kt` | `rialto validate`, as text or `--format json`, and the exit code |
| `rialto-gradle-plugin`: `ValidateTask` | `rialtoValidate`: logs the findings and fails the build on an error |

A run goes through one call of `ContractValidator.validate`:

```text
rialto validate / rialtoValidate
  └─ ContractValidator.validate(file, remote)
       ├─ 1. ContractFiles.read(file)                     Nok → stop
       │      K001; for fragments also K002, K003, K005, K007, K008, K009
       ├─ 2. rialto == SUPPORTED_VERSION ("1.0.0")?       no → K003 at /rialto, stop
       ├─ 3. ContractSchema.check(contract, root)         any finding → K002 each, stop
       ├─ 4. model(document)                              JacksonException → one K002, stop
       ├─ 5. Sources.load(contract, document, remote)     findings kept, go on
       └─ 6. checks.forEach { it.run(context) }           findings collected in CheckContext
       → Outcome.of(ValidContract(contract, document, sources), findings)
```

Steps 2 and 3 are `ContractSchema.checkContract`, which `ContractFiles.bundle` also runs before it writes the
bundled form (K5.6).

`findings` holds every finding in the order the steps made them. A step that succeeds can only hand on warnings, so
the findings before a stop are warnings only.

## The steps

The first four steps stop the run on an error; each next step relies on what they guarantee. The last two go on, so
the author sees every mistake they can find in one run.

| Step | On an error | Why (from the KDoc) |
|---|---|---|
| 1. Read | Stop | Everything after reading works on one document, the bundled form |
| 2. Format version | Stop | A contract of another version is not judged by this schema |
| 3. JSON Schema | Stop | The document must have the structure the model expects |
| 4. Model | Stop | The sources and the checks read the model |
| 5. Sources | Go on | A source that cannot be loaded is a finding, but the checks still run |
| 6. Checks | Go on | Each set of checks reports every finding through `CheckContext` |

- **The version check** reads `rialto` as a string, with `""` when it is missing. Anything other than
  `SUPPORTED_VERSION` is `RIALTO-K003` at `/rialto`, with the message `Contract format '<version>' is not supported` and
  the hint `This tool reads format 1.0.0`.
- **The JSON Schema** is the resource `contract-1.0.0.schema.json` in the jar, read once; a test requires it to equal
  the specification's byte for byte. It is validated as JSON Schema 2020-12, with format assertions on and JSON Pointer
  locations. `check` sorts the violations by place and returns one `RIALTO-K002` per violation, at
  `document.locate(at + instanceLocation)`. `ContractSchema` also holds the schema of `include` and of a fragment
  (`$defs/fragment`), which `ContractFiles` uses.
- **The model** is read with `ContractMapper.json.treeToValue<Contract>`. A document that passes the schema should
  always fit; when it does not, that is a gap between the schema and the model. `model` then returns one
  `RIALTO-K002` at the element Jackson rejects, from the path of the exception, with the message of its deepest
  Jackson cause: `the contract passes the JSON Schema but does not fit the contract model at /<path>: …`. A gap gives
  a finding, not a crash.
- **The checks** are `ContractValidator.checks`, a list of `ContractChecks`; it holds `FormatChecks` only. A new rule
  is a new `ContractChecks`, not a change to `validate`. A set of checks only looks at the model and reports through
  `CheckContext.error(code, message, pointer, hint, requirement)`; it does not read files or decide whether the run
  fails. `FormatChecks` walks the sections in the order of the document: each section lists its references
  and type expressions (`Contract.storageReferences()`, `Contract.typeExpressions()`, …), and `ReferenceChecks`,
  `TypeExpressionChecks`, `PropertyPathChecks` and `NameChecks` judge them. What it checks in each is in the guides of
  [sources and references](https://github.com/buijs-dev/rialto-spec/blob/develop/docs/guides/sources-and-references.md)
  and [types](https://github.com/buijs-dev/rialto-spec/blob/develop/docs/guides/types.md).

`validate` does not throw on an invalid contract: every finding is a `Diagnostic` in the `Outcome`.

## Reading a document

`LocatedDocument.parse(file, text)` picks the parser by the extension: `.json`, in any case, is read with
`ContractMapper.jsonFactory`, every other file with `ContractMapper.yamlFactory`.

- **JSON** is plain JSON: no comments, trailing commas or single quotes.
- **YAML** is YAML 1.2 with its core schema (`YAMLSchema.CORE`), and `PARSE_OCTAL_NUMBERS` is off: `~` and `Null` are
  null, `yes` is a string, `0444` is 444 and `0o14` is 12. YAML 1.1 would read `yes` as true and `0444` as an octal
  number, which would change what an author wrote.
- **One tree reader for both.** `TreeReader` builds the tree from the tokens of the parser, because Jackson's own
  trees forget where an element was read. It throws (`require`) on what would silently drop what the author wrote:

| Input | Message |
|---|---|
| A key used twice in one mapping, in YAML or JSON | `Duplicate key '<name>' at line <l>, column <c>` |
| Content after the first document: a second YAML document, or a second JSON value | `Only one document is expected, but more follows at line <l>, column <c>` |
| A top level that is not a mapping | `The document is not a mapping but a list` (or `null`, or `a single value`) |

A document start or end marker alone (`---`, `...`) is no second document. An empty text is an empty mapping, so an
empty contract gets `RIALTO-K003` for its missing `rialto`. Integers are read as `BigInteger` and other numbers as
`BigDecimal`, so a value such as `99999999999999` keeps its value and the JSON Schema reports it at its pointer.

`LocatedDocument.readMapping(file)` is how every contract and fragment is read. It catches any exception and returns
`Outcome.Nok` with one `RIALTO-K001` at `Location(file)`, with the exception's message, or `cannot be read` when it
has none. That covers a missing file, a parse error, and each case of the table above.

For a contract or fragment in YAML, `readMapping` then runs `JsonYaml.check` over the text (K1.11). It reads the
events of snakeyaml-engine, the parser under Jackson, because Jackson's tokens no longer say how a key was written:
`1:` and `"1":` both arrive as the name "1". It runs after Jackson, so a document that is no valid YAML keeps Jackson's
message. Its finding is `RIALTO-K001` at the line and column of the element, with requirement K1.11:

| Input | Message |
|---|---|
| A tag other than `!!str`, `!!int`, `!!float`, `!!bool`, `!!null`, `!!map`, `!!seq`, such as `!shipment` or `!!binary` | `The tag '<tag>' has no JSON form` |
| An anchor, such as `&info` | `The anchor '&<name>' has no JSON form` |
| An alias, such as `*info` | `The alias '*<name>' has no JSON form` |
| A plain key that the core schema reads as no string, such as `1`, `true` or `~`, or a key tagged other than `!!str` | `The key '<key>', which is not a string, has no JSON form` |

A mapping or a list as a key is rejected by Jackson itself, as a parse error. `LocatedDocument.parse` and `read`,
which also read OpenAPI and AsyncAPI sources, do not run the check: a source document may use all of YAML.

## Locations

`TreeReader` records a `Position` (file, line, column) per JSON Pointer while it reads. The position of a property is
that of its name; the position of a list element is that of its value. For a contract over several files, each
element keeps the file of the fragment it came from (K5.5), so `Position.file` can differ from `LocatedDocument.file`.

`locate(pointer)` gives a `Location` with the pointer as asked, at the position of that pointer or of its nearest
ancestor that has one. A missing property is so reported at the element that should have it; a key the schema does
not allow is reported at the mapping that holds it. A pointer without any known position, as in an empty document,
gets the file only.

`Location.toString()` is the place as a person reads it: the file name, `:<line>:<column>` when the line is known
(column 1 when only the line is), and ` #<pointer>` when the pointer is not empty:

```text
unknown-type.yaml:11:15 #/types/Thing/properties/owner/type
```

A `RIALTO-K001` has no line: the reader failed, so it has no tree to point into. Its message holds the line and
column when the reader knows them.

## Diagnostics and outcomes

- **`Diagnostic`** holds `severity`, `code`, `message`, `location`, an optional `hint`, and an optional
  `requirementOverride`. `requirement` is the override, else `code.requirement`; `id` is `code.id`. `toString()` is the
  text form that the command line and the Gradle plugin print:
  `<severity>[<id>] <location>: <message> (<requirement>)`, and `\n  hint: <hint>` when there is a hint.
- **`Severity`** is `ERROR` (`error`) or `WARNING` (`warning`). An error fails the run; a warning is reported and the
  contract stays valid. `hasErrors()` on a list of diagnostics tells whether one is an error.
- **`Outcome`** is the return type of every step that can find mistakes. `Ok(value, diagnostics)` requires that no
  diagnostic is an error, `Nok(diagnostics)` that at least one is; each throws `IllegalArgumentException` otherwise. A
  caller reaches the value only through `Ok`, with a `when`, so it cannot go on with the value of a step that failed.
  `Outcome.of(value, diagnostics)` is `Nok` as soon as one finding is an error.
- **`Diagnostics`** collects findings inside one step, for steps that find many in a deep call tree, such as a set of
  checks. It is internal: whoever creates one also reads it and hands the findings on in an `Outcome` or a list.
- **`Code`** has an `id`, the first `requirement` it enforces and a `summary` per code. A test requires the ids to
  equal the codes of the specification's standard table, in order, and each requirement to be one of that code's
  requirements. No code of the main sources reports a `P` code: `checks` holds `FormatChecks` only.

## Output

`rialto validate [--offline] [--format text|json] <contract>...` validates each contract in turn:

| Output | Where |
|---|---|
| An error, as `Diagnostic.toString()` | stderr |
| A warning, as `Diagnostic.toString()` | stdout |
| `<file>: valid`, for each valid contract, after its warnings | stdout |
| `error: <file> not found`, for a file that does not exist; the validator is not called | stderr |
| With `--format json`: one JSON document for all contracts, nothing else ([diagnostics](../diagnostics.md#json-output)) | stdout |

In the JSON form, `valid` comes first, then `contracts`, as in the form of the specification. `file` of a contract and
of a diagnostic is a path, not only the file name as in the text form; `pointer` is `""` at the root; `line`,
`column` and `hint` are null when unknown. A file that does not exist is an entry with `valid: false` and one
diagnostic with every field: `code` and `requirement` null, because it is no finding of a contract, and the message
`<file> not found`.

| Exit code | When |
|---|---|
| `0` | Every contract is valid |
| `1` | A contract has an error, or does not exist |
| `64` | Wrong usage, such as no contract, or a `--format` other than `text` or `json` |
| `70` | An exception no command expects: printed as a bug in rialto, with its stack trace |

`rialtoValidate` (`ValidateTask`) calls the same `ContractValidator.validate`, so it gives the same verdict. It logs
each finding at the level of its severity (`ERROR` or `WARN`). On `Outcome.Nok` it throws a `GradleException`
`Rialto found <n> error(s); see the messages above.`, where `<n>` counts the errors only; warnings never fail a build.
On success it writes the marker file `build/rialto/validate/valid`, so Gradle can keep the task up to date and cache it.

## Failure handling

| Failure | Result |
|---|---|
| The contract or a fragment cannot be read, is no single document, has a duplicate key or is no mapping | One `K001` for that file at `Location(file)`; the run stops after step 1 |
| An error in step 1 to 4 | `Outcome.Nok` with the findings so far; the next steps do not run |
| The model rejects a document the schema accepted | One `K002` at the rejected element, not a crash |
| A source cannot be loaded | Its finding is kept; the checks still run |
| An invalid contract | `Outcome.Nok`; `validate` does not throw |
| An `Outcome` built against its invariant | `IllegalArgumentException`: an `Ok` with an error or a `Nok` without one is a bug |
| An unexpected exception in the command line | `error: rialto failed unexpectedly. This is a bug in rialto; please report it:`, the stack trace, exit `70` |

## Tests

| Test class | What it covers |
|---|---|
| `ContractValidatorTest` | Every valid test contract of the specification has no findings; every invalid one produces the codes of its `# expect:` lines; line, column and pointer of a finding; a finding in a fragment names it; `Code` equals the standard table; the bundled schema equals the specification's; `checks` holds `FormatChecks` only; a misfit of the model stops before the sources |
| `LoadingTest` | Positions in YAML and JSON; the nearest ancestor; `K001` for a duplicate key, a second document, a parse error, no mapping and a missing file; `K003` with its hint; `K002` at its pointer and at the root; an integer beyond 32 bits; a misfit; the text form of a diagnostic and a location; the invariants of `Outcome` |
| `LocatedDocumentTest` | The parser by extension; JSON in a YAML file read as YAML; numbers JSON keeps that YAML would read otherwise; no mapping; an empty document |
| `JsonYamlTest` | K1.11: each kind of tag, anchor, alias and key that is not a string, at its line and column; the core tags and quoted keys pass; a source document and a JSON contract are not checked |
| `ContractMapperTest` | Strict JSON; YAML 1.2 with the core schema, without octal numbers; how the mappers write YAML |
| `FormatChecksTest` | The Shipment example gives nothing; the findings of all rules follow the order of the document |
| `format.*Test` | Per file of `validation.format`, on the Shipment example (`ShipmentExample`): each reference or type that points at nothing gives its code at its pointer, and changes that stay valid give nothing; `ReferencesTest`, `TypeReferencesTest` and `StorageReferencesTest` also check the references a section lists, without validating |
| `SpecSnippetsTest` | Every contract snippet in the Markdown of the specification is valid against the JSON Schema |
| `CommandsTest` | Codes and exit 1 for an invalid contract; `<file>: valid`; a missing file; wrong usage (64); an unexpected failure (70); errors to stderr and warnings to stdout |
| `JsonFormatTest` | `--format json`: a valid contract, the fields of a diagnostic, null when unknown, a missing file, an unknown format |
| `RialtoPluginTest`, `RialtoPluginFunctionalTest` | `rialtoValidate` writes its marker on a valid contract and fails with the count of errors; warnings are logged and do not fail; an invalid contract shows its diagnostic in the build output |
