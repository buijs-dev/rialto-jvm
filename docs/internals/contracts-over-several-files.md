# Contracts over several files: internals

How rialto-jvm reads, merges and bundles a contract over several files. What a tool must do, and why, is in the guide
[Contracts over several files](https://github.com/buijs-dev/rialto-spec/blob/develop/docs/guides/contracts-over-several-files.md)
and in [K5](https://github.com/buijs-dev/rialto-spec/blob/develop/spec/contract.md#k5-contract-over-several-files);
this document is about the code. The classes are in `rialto-core`, package `dev.buijs.rialto.core.files`, unless the
table says otherwise.

## Overview

| Class | Role |
|---|---|
| `ContractFiles` | Reads the root with its fragments as one document (`read`), writes the bundled form (`bundle`), lists the files a run depends on (`inputs`) |
| `ContractFiles.Bundle` | The merge: the root with each fragment added in reading order, and who declared what |
| `Glob` (`core.utilities`) | The path patterns of `include` and of an SQL source over migrations: `files` and `regex` |
| `LocatedDocument`, `Position` (`core.document`) | A document as a tree, with the file, line and column of every element by JSON Pointer |
| `ContractSchema` (`core.schema`) | The schemas cut from the contract schema: `contract`, `include` and `fragment` |
| `ContractValidator` (`core.validation`) | Step 1 of `validate` is `ContractFiles.read`; every step after it works on the bundled form |
| `Commands.kt` (rialto-cli) | `rialto bundle --output <file> <contract>` |
| `BundleTask`, `RialtoPlugin` (rialto-gradle-plugin) | `rialtoBundle`, and the inputs of `rialtoValidate` and `rialtoBundle` |

A read goes through `ContractFiles.read`:

```text
ContractValidator.validate / ContractFiles.bundle
  └─ ContractFiles.read(root)
       ├─ LocatedDocument.readMapping(root)       cannot be read, not a mapping → K001
       ├─ no include                              → the root as it is
       ├─ ContractSchema.include at /include      invalid → K002, no fragment is read
       ├─ matches(root, include)                  per pattern: Glob.files, below the root's directory, not the root
       ├─ unmatched(...)                          a pattern without a match → K007 at /include/<index>
       ├─ inReadingOrder(...)                     all matches, in code point order of their path, each file once
       └─ Bundle(root).add(fragment)              once per fragment
            ├─ readMapping(fragment)              → K001, fragment skipped
            ├─ rialto ≠ the root's                → K003, fragment skipped
            ├─ info, sources or include           → K008 per key, fragment skipped
            ├─ ContractSchema.fragment            → K002, fragment skipped
            └─ per top-level key
                 ├─ rialto                        ignored
                 ├─ a keyed section               per name; a name already declared → K005, that name left out
                 ├─ mappings                      appended
                 └─ any other key                 one file only; already declared → K009, that key left out
```

The result is `Outcome.of(bundle.document(), unmatched + bundle.diagnostics)`: `Nok` when any finding is an error.
The findings come in that order: first `K007` per pattern, then those of the fragments in reading order.

## Reading the root

`read` reads the root with `LocatedDocument.readMapping`. A root without `include` is returned as it is, so a contract
in one file takes the same path as one over several files.

The bundled form drops `include`, so the schema of the whole contract never sees it. `read` checks it first against
`ContractSchema.include`, the `include` property cut from the contract schema, with the findings at `/include`. That
schema asks for a list of at least one unique, non-empty string: `include: "parts/*.yaml"`, `[]`, `[42]` and
`[a.yaml, a.yaml]` are all `K002`. With an invalid `include`, no fragment is read.

## Include paths

`ContractFiles.matches` resolves each pattern with `Glob.files(base, pattern)`, where `base` is the directory of the
root. It keeps only files below `base` and never the root itself; a pattern such as `../outside/*.yaml` or `*.yaml`
next to the root therefore matches nothing, and is `K007`. The matches are kept as paths relative to `base`, with `/`
as separator (`relativePath`).

`Glob.files` walks from the directory before the first segment with `*`, so it reads only the directories the pattern
can match. When that directory does not exist the pattern matches nothing. It takes regular files whose relative
path matches `Glob.regex(pattern)`:

| In the pattern | Regex | Matches |
|---|---|---|
| `*` within a segment | `[^/]*` | any characters within one segment |
| `**` as a segment, not the last | `(?:[^/]+/)*` | any number of segments, also none: `a/**/b/*.yaml` matches `a/b/c.yaml` |
| `**` as the last segment | `(?:[^/]+/)*[^/]+` | every file below its directory: `x/**` matches `x/a.yaml` and `x/a/b.yaml` |
| anything else | `Regex.escape` | itself: `?`, `[ab]`, `+` and `(` are literal |

`inReadingOrder` flattens the matches of all patterns into one list of relative paths without duplicates, so a file
that two patterns match is read once, sorts it with `codePointOrder` (`core.utilities`), the code point order of the
specification rather than the UTF-16 order of `String.compareTo`, and resolves the paths against `base` again. `ContractFiles.fragments(root)` gives the same
list for a root on disk; it is empty without `include` or when the root cannot be read.

## Merging

`Bundle` starts from a deep copy of the root without `include`, and the root's positions without those under
`/include`. Its `owner` map remembers who declared what: `"types/Thing"` for each element of a keyed section
(`types`, `shapes`, `operations`, `events`, `subscriptions`, `streams`, `storage`, `views`), and the key itself for
every other top-level key except `mappings`. The root is entered first, so a name in the root and in a fragment is
reported in the fragment.

`add(file)` checks a fragment on its own before it merges anything:

1. It reads the fragment with `readMapping`, as YAML or, for a name ending in `.json`, as JSON.
2. It compares `rialto` with the root's `rialto` as strings. A fragment without `rialto` compares as `""`, so it is
   `K003` too: "Fragment has format '2.0.0', the root '1.0.0'".
3. It reports each of `info`, `sources` and `include` as `K008`: "'sources' belongs in the root, not in a fragment".
4. It checks the fragment against `ContractSchema.fragment`: the definitions of the contract schema with
   `$ref: #/$defs/fragment` at the top.

A fragment that fails one of these adds nothing to the bundle. Otherwise each top-level key goes to one of three
functions:

| Function | For | What it does |
|---|---|---|
| `section` | a keyed section | Adds each element by name. A name with an owner is `K005` at the element in the fragment, "'Thing' is already declared under types in contract.rialto.yaml"; that element is left out, the others are added |
| `mappings` | `mappings` | Appends each mapping to the list of the bundle, at the next index |
| `single` | `errors`, `auth`, `navigation`, extension keys | Takes the value when the key has no owner; otherwise `K009` at the key in the fragment, naming the file that declared it first |

The file in a message is relative to the root's directory, as a contract author reads it. Sections that are new to
the bundle are added after the keys of the root, in the order they are first met.

When a section of the root has the wrong type, such as `types: 42` or `mappings: none`, `section` and `mappings`
leave it as it is: the schema of the whole contract reports it in the root afterward, and replacing it by the
elements of a fragment would hide the mistake (`invalid/fragment-root-section/`).

## Locations per file

`LocatedDocument` keeps a `Position` (file, line, column) per JSON Pointer of the document it read. The bundle is a
`LocatedDocument` whose `file` is the root, and whose positions are those of the root plus those copied from each
fragment. `Bundle.copy` copies the positions of an element and everything below it from the fragment's pointer to the
pointer in the bundle; for `mappings` that is a new index, so `/mappings/0` of a fragment can become `/mappings/1`
of the bundle and still point at its line in the fragment.

`locate(pointer)` returns the position of the pointer, or of its nearest ancestor with a position. Every check after
reading reports through `locate`, so a finding about an element of a fragment names that fragment and line (K5.5),
without the check knowing there were several files. `validate --format json` writes that file as `file`
([diagnostics](../diagnostics.md)).

## The bundled form

`ContractFiles.bundle(root, output)` reads the contract with `read`. When that is `Nok`, it returns the findings and
writes nothing. Then it checks the bundled form with `ContractSchema.checkContract`, the same check as steps 2 and 3
of `ContractValidator.validate`: the format version (`K003`), then the contract schema (`K002`, K5.6). With a
finding it writes nothing. Otherwise it creates the directory of `output`, and writes the bundled document as YAML
with `ContractMapper.yaml`.

Local source paths are relative to the contract, so they follow it to its new place: `bundle` rewrites the `path` of
every local source relative to the directory of `output` (`api.json` becomes `../../api.json` for
`out/deeper/bundled.rialto.yaml`). A remote source is left as it is; after the schema check every source is a mapping
with a `path`. `bundle` loads no source, so a remote source keeps its URL and bundling needs neither the network nor
the cache. References, names and sources are checked by `ContractValidator.validate`, not by `bundle`.

An `IOException` on writing is thrown, not returned: it is not a finding about the contract.

| Entry point | Output | On errors |
|---|---|---|
| `rialto bundle --output <file> <contract>` | The findings, then `<contract>: bundled into <file>`, exit code 0 | The findings, exit code 1; `error: cannot write <file>: …` and exit code 1 when the file cannot be written; the usage and exit code 64 without `--output` or with not exactly one contract |
| `rialtoBundle` | `build/rialto/bundled.rialto.yaml` | The findings in the log, and a `GradleException` "Rialto found N error(s); see the messages above." |

`BundleTask` is a `@CacheableTask`. Its `contractLocation` input is the path of the contract seen from the directory
of the output: the bundle writes source paths relative to the output, so that layout belongs in the cache key.

## Inputs for build tools

`ContractFiles.inputs(root)` lists every file a run over the contract depends on, in this order:

1. the root;
2. its lock (`RialtoLock.of(root).file`, K4.6);
3. its fragments, in reading order (`fragmentsOf`);
4. its local sources, a migrations glob (`*` in the `path`) as each file it matches (`migrations`).

A remote source is tracked through its hash in the lock. A root that does not exist yields only itself; one that
cannot be read yields itself and its lock, so a build still notices when either changes.

`RialtoPlugin` hands this list to the `contractInputs` of `rialtoValidate` and `rialtoBundle`
(`@InputFiles`, `@PathSensitive(RELATIVE)`), so a task is up to date until one of those files changes. A change in a
fragment runs `rialtoValidate` again.

## Failure handling

| Failure | Result |
|---|---|
| The root cannot be read or is not a mapping | `K001` on the root; nothing else is checked |
| `include` violates its schema | `K002` at `/include` or an entry; no fragment is read |
| A pattern matches no file, also one outside the root's directory or only the root | `K007` at its entry in `include`; the other patterns are still read |
| A fragment cannot be read, has another `rialto`, declares a root-only key, or violates `$defs/fragment` | `K001`, `K003`, `K008` or `K002` in the fragment; the fragment adds nothing, the other fragments are still merged |
| A name declared twice | `K005` in the file read second; the first declaration stays |
| A single-file key in two files | `K009` in the file read second; the first one stays |
| Any error of `read` | `Nok`: `ContractValidator.validate` returns these findings without the steps after reading, and `bundle` writes nothing |
| The output of `bundle` cannot be written | `IOException`; the command line prints it and exits with 1 |

Because the fragments are all read before `read` returns, one run reports the findings of every fragment, not only
those of the first.

## Tests

| Test class | What it covers |
|---|---|
| `IncludeTest` | Merging keyed sections and `mappings` in path order, positions in fragments, the schema of `include`, `K007` per entry, a final `**`, the root never including itself, an include outside the root's directory, `K008` per key, `K009` and `K005` naming the first file, `K003` also without a version, `K002` and `K001` in a fragment, a finding of another check in a fragment (K5.5), a root section of the wrong type, `bundle` and its source paths, `Glob.regex`, `inputs` |
| `ContractValidatorTest` | The test contracts of the specification, including `valid/fragments/`, `valid/fragments-recursive/` and every `invalid/fragment-*/`; the split Shipment bundles to the single-file Shipment; the bundled form written elsewhere is valid |
| `CommandsTest` | `rialto bundle`: a valid bundled form, a contract with errors, an output that cannot be written, wrong usage |
| `RialtoPluginTest` | `rialtoBundle` and its output, its `contractLocation`, the tracked inputs, a contract that does not exist or cannot be read |
| `RialtoPluginFunctionalTest` | `rialtoValidate` runs again when a fragment changes; `rialtoBundle` in a real build |
