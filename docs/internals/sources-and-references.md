# Sources and references: internals

How rialto-jvm loads the sources of a contract and resolves `<source>#<location>` references into them. What a tool
must do, and why, is in the guide
[Sources and references](https://github.com/buijs-dev/rialto-spec/blob/develop/docs/guides/sources-and-references.md)
and in [Sources and references](https://github.com/buijs-dev/rialto-spec/blob/develop/spec/contract.md#sources-and-references)
of the specification; this document is about the code. Remote sources have their own document,
[Remote sources: internals](remote-sources.md). All classes are in `rialto-core`, package
`dev.buijs.rialto.core.sources`, unless the table says otherwise.

## Overview

| Class | Role |
|---|---|
| `Sources` | The sources of one contract: `load` reads them, `resolve` resolves a reference, `table` and `element` give what a reference points at |
| `Sources.Resolution` | The result of `resolve`: `Resolved`, `UnknownSource`, `Unloaded` or `Missing(source)` |
| `LoadedSource` | A source that could be read: `Document` (OpenAPI or AsyncAPI, a `LocatedDocument`) or `Sql` (tables by lower-case name) |
| `ExternalRef` | A reference taken apart: `source` before `#`, `location` after it; for SQL `table` and `column`, in lower case |
| `RestOperation` | A REST binding taken apart: the `source`, the `path` template and the `method` of `<source>#/paths/<path>/<method>` |
| `SourceLoader` | Loads one source at a time and reports `RIALTO-S001`; private to `Sources.kt` |
| `migrations(base, path)` | The files a migrations path matches, in version order |
| `SqlSchema`, `SqlTable`, `SqlColumn` | A reader of DDL, and the tables and columns it gives |
| `LocatedDocument` (`core.document`) | Reads an OpenAPI or AsyncAPI document as a tree with the position of every element |
| `Glob` (`core.utilities`) | The path patterns of `include` and of migrations |
| `FormatChecks` (`core.validation`) | Checks every reference of the contract, section by section |
| `Reference`, `ReferenceCollector` (`core.validation.format`) | A reference and where it stands; the `*References()` functions build them per section |
| `ReferenceChecks` (`core.validation.format`) | Resolves each `Reference`: a name against the declared elements of its `Kind`, an external one against the sources |

A run goes through the steps of `ContractValidator.validate`; sources are step 5:

```text
ContractValidator.validate
  ├─ 1–4. read, version, JSON Schema, model    any error stops here
  ├─ 5. Sources.load(contract, document, remote)
  │    └─ per entry under sources, in order: SourceLoader.load(name, source)
  │         ├─ sql and '*' in path          → loadMigrations: migrations(...), SqlSchema.read
  │         │    └─ no file                 → S001 "matches no migration"
  │         ├─ "://" in path                → RemoteSession.resolve (S003, S004, S005)
  │         ├─ otherwise                    → the path relative to the contract
  │         ├─ file does not exist          → S001 "not found"
  │         ├─ sql                          → SqlSchema.read(file text)
  │         ├─ openapi, asyncapi            → LocatedDocument.read(file)
  │         └─ exception while reading      → S001 "cannot be read"
  │    └─ session.finish()                  the lock, see remote sources
  └─ 6. FormatChecks: ReferenceChecks.check(Reference.External) for every reference
       └─ sources.resolve(reference, declared)
            ├─ UnknownSource                → K004 "Unknown source …"
            ├─ Missing                      → S002 "'<location>' does not exist in <file>"
            └─ Resolved, Unloaded           → nothing
```

Loading never fails as a whole. A source that cannot be read is missing from `Sources.loaded` and is a finding in
`Sources.diagnostics`; the other sources are still loaded and step 6 still runs, so one run shows every mistake. A
reference into a source that did not load resolves to `Unloaded`, which is not reported again.

## Loading a source

- **The path.** `RemoteSources.isRemote` takes a path with `://` as remote. A local path is resolved against the
  directory of the contract file (`toAbsolutePath().parent`) and normalized. For a contract over several files that
  is the root: `Sources.load` gets the bundled document, and only the root has `sources`.
- **The location of `RIALTO-S001`** is `/sources/<name>/path` in the contract, with the name escaped as a JSON
  Pointer segment.
- **A document source** is read by `LocatedDocument.read`: as JSON when the file name ends in `.json`, otherwise as
  YAML. A remote source is read from its cached copy, which keeps the file name of the URL (`sha256/<hash>/<name>`),
  so the same rule applies. The text must be one document and a mapping, without a key used twice; anything else
  throws and is `S001` "cannot be read" with the message of the exception. An empty file is an empty mapping.
- **An SQL source** is read by `SqlSchema.read` on the text of the file. The reader skips what it does not
  understand, so an SQL file is `S001` only when it does not exist or cannot be read.
- **`dialect`** is read into the model (`Source.dialect`); the loader and `SqlSchema` do not use it.

## Resolving a reference

`ExternalRef.of(reference)` splits on the first `#`. `Sources.resolve(reference, declared)` then:

1. returns `UnknownSource` when the name is not in `declared`, the keys of `sources` of the contract;
2. returns `Unloaded` when the source is declared but not in `loaded`;
3. looks up the location: for a `Document`, `LocatedDocument.at(location)`; for an `Sql` source, the table and, when
   the location has a `.`, the column;
4. returns `Resolved`, or `Missing(source)` with the loaded source, so the message can name its file.

- **JSON Pointer.** `LocatedDocument.at` returns null for a location that is not empty and does not start with `/`,
  so `api#components/schemas/X` is `Missing`. Otherwise it uses Jackson's `JsonNode.at`, which decodes `~1` and `~0`
  (RFC 6901); a missing node is null.
- **SQL.** `ExternalRef.table` is the part before the first `.`, `column` the part after it, both in lower case. The
  tables and columns of `SqlSchema` are keyed in lower case too, so `db#THING` resolves against `CREATE TABLE Thing`.
- **The message of `S002`** names `source.file.fileName`. For migrations, `LoadedSource.Sql.file` is the directory of
  the first file in version order, so the message names that directory.

A `Reference.External` is made for every place where the contract holds a reference: the `rest`
operations of an operation, the `channel` of an event and of a stream, the `deadLetter` of a subscription, the
`source` and `target` of a mapping when they contain `#`, the `scheme` of `auth.scopes` and `auth.apiKeys`, and in
`storage` the `table` and every column the entry names (`columns`, joins and their keys, `children`, `links`,
`version`, `delete`, `storageKey`, `blobs`). A column is checked as `<table>.<column>`, with `<table>` the reference
of the table it belongs to. A `rest` operation is a `Reference.External` with `operation = true`: besides resolving,
its location must be `/paths/<path>/<method>` with an HTTP method of OpenAPI (`RestOperation.of`), else it is `S002`,
because a path item or an element inside an operation is no operation to bind to. `Contract.sourceReferences()` makes the `successor` of a source a `Reference.Name` of `Kind.SOURCE` (`K004`).

`Sources.table(reference)` and `Sources.element(reference)` give the `SqlTable` or the `JsonNode` a reference points
at, or null. They, `ExternalRef` and `RestOperation` are public, so a generator that reads a `ValidContract` looks a
reference up the way validation resolved it, without parsing it again.

## Migrations

`SourceLoader` treats an `sql` source with `*` in its `path` as migrations; `**` contains `*`, so it counts too.

- **Matching.** `Glob.files(base, path)` walks from the directory before the first segment with `*`; when that
  directory does not exist, nothing matches. A file matches when its path relative to `base` matches `Glob.regex`:
  `*` is `[^/]*`, `**` any number of segments, a final `**` every file below its directory.
- **Order.** `migrations` sorts the matches with one comparator. `versionOf` reads the version with
  `^V(\d+(?:[._]\d+)*)__` on the file name: `.` and `_` separate parts, and each part is a `BigInteger`, so a
  timestamp such as `V20261120120000` is a version. Two versions compare part by part; when one is a prefix of the
  other, the shorter comes first. A file with a version comes before one without. Files without a version compare by
  `Path.toString()` in `codePointOrder`, the code point order of the specification. The directory of a file plays no part in the version, so `db/nested/V3__x.sql` comes between
  `db/V2__` and `db/V10__`.
- **One schema.** `loadMigrations` joins the texts of the files with `";\n"`, so a statement never spans two files,
  and reads the result with `SqlSchema.read`.
- **Build inputs.** `ContractFiles.inputs` uses the same `migrations` function, so the Gradle plugin tracks every
  matched file as an input of its tasks.

## The SQL reader

`SqlSchema.read(sql)` applies the statements in order to a map of tables and gives `Map<String, SqlTable>`. It is a
reader of DDL, not a full SQL parser.

**Statements.** `statements` splits the text on `;` outside single-quoted strings, double-quoted identifiers
(a doubled quote is an escaped quote), dollar-quoted bodies (`$$` or `$tag$`), `--` comments and `/* */` comments.
The comments are left out. A string, body or comment that does not end runs to the end of the text.

**Names.** A table or column name is read without the schema (the part after the last `.`), without double quotes,
and in lower case: `public."T"` is `t`.

**What a statement does:**

| Statement | Effect |
|---|---|
| `CREATE [GLOBAL \| LOCAL] [TEMP \| TEMPORARY \| UNLOGGED] TABLE name (…)` | Adds the table with its columns; replaces a table of that name |
| `CREATE TABLE IF NOT EXISTS name (…)` | As above, but leaves an existing table as it is |
| `ALTER TABLE [IF EXISTS] [ONLY] name RENAME TO other` | Renames the table |
| `ALTER TABLE [IF EXISTS] [ONLY] name action, action, …` | Applies each action below, in order; nothing when the table is unknown |
| `DROP TABLE [IF EXISTS] a, b [CASCADE]` | Removes each named table |
| Any other statement | Skipped, such as `CREATE INDEX`, `CREATE FUNCTION`, `CREATE EXTENSION`, `INSERT` and `CREATE TABLE … AS SELECT` |

**In the body of `CREATE TABLE`**, each definition separated by a top-level comma is a table constraint when it
starts with `PRIMARY`, `FOREIGN`, `UNIQUE`, `CHECK`, `CONSTRAINT`, `EXCLUDE` or `LIKE`. Of those, only
`[CONSTRAINT name] PRIMARY KEY (a, b)` has an effect: it marks the named columns as primary key and not null, also
when it stands before them. Any other definition with a name and a type is a column; one with only a name is
skipped.

**Actions of `ALTER TABLE`:**

| Action | Effect |
|---|---|
| `ADD [COLUMN] [IF NOT EXISTS] column` | Adds the column; replaces one of that name, except with `IF NOT EXISTS` |
| `ADD [CONSTRAINT name] PRIMARY KEY (…)` | Marks the columns as primary key and not null |
| `ADD` another table constraint | Nothing |
| `DROP [COLUMN] [IF EXISTS] name` | Removes the column |
| `DROP CONSTRAINT …` | Nothing |
| `RENAME [COLUMN] a TO b` | Renames the column |
| `RENAME CONSTRAINT …` | Nothing |
| `ALTER [COLUMN] name SET NOT NULL` / `DROP NOT NULL` | Sets or clears not null |
| `ALTER [COLUMN] name SET DEFAULT …` / `DROP DEFAULT` | Sets or clears `hasDefault` |
| `ALTER [COLUMN] name DROP IDENTITY` | Clears `hasDefault` |
| `ALTER [COLUMN] name ADD GENERATED …` | Sets not null and `hasDefault` |
| `ALTER [COLUMN] name SET DATA TYPE t` / `TYPE t [USING …]` | Changes the type |
| Any other action, such as `OWNER TO` or `SET STATISTICS`, or one on an unknown column | Nothing |

**What a column records** (`SqlColumn`):

| Field | Value |
|---|---|
| `name` | The name, in lower case |
| `type` | The words up to the first column constraint (`NOT`, `NULL`, `PRIMARY`, `DEFAULT`, `REFERENCES`, `CHECK`, `UNIQUE`, `CONSTRAINT`, `GENERATED`, `COLLATE`) or `USING`, in lower case, without parenthesized groups: `VARCHAR(40)` is `varchar`, `timestamp(3) with time zone` is `timestamp with time zone`, `VARCHAR(50)[]` is `varchar[]` |
| `notNull` | `NOT NULL`, `PRIMARY KEY`, `GENERATED … IDENTITY`, or a serial type (`serial`, `bigserial`, `smallserial`, `serial2`, `serial4`, `serial8`) |
| `primaryKey` | `PRIMARY KEY` on the column, or a primary key table constraint that names it |
| `hasDefault` | `DEFAULT`, any `GENERATED` (identity or expression), or a serial type |

`acceptsNull` is true when the column is neither `notNull` nor part of the primary key. A column `GENERATED ALWAYS
AS (expression) STORED` has a default but accepts null. `NULL`, `REFERENCES`, `CHECK`, `UNIQUE` and `COLLATE` on a
column are skipped.

## Failure handling

| Failure | Result |
|---|---|
| A local source file does not exist | `S001` "Source '<name>' not found: <path>" at `/sources/<name>/path`; the source is not loaded |
| A document source is not valid YAML or JSON, not a mapping, has two documents or a duplicate key | `S001` "Source '<name>' cannot be read: <message>" |
| Any other exception while reading a file | `S001` "cannot be read", with the message of the exception |
| A migrations path matches no file | `S001` "Source '<name>' matches no migration: <path>" |
| A remote source fails | The codes of `RemoteSession`; see [Remote sources: internals](remote-sources.md) |
| A reference names an undeclared source | `K004` "Unknown source '<source>' in '<reference>'" at the pointer of the reference |
| A reference into a loaded source does not resolve | `S002` "'<location>' does not exist in <file>" at the pointer of the reference |
| A reference into a source that did not load | Nothing: the `S001` of the source is the finding |
| An SQL statement or action the reader does not understand | Skipped, without a finding |

## Tests

| Test class | What it covers |
|---|---|
| `SourcesTest` | `S001` for a missing or broken source and for migrations that match nothing, `S002` by JSON Pointer and by table and column in any case, `K004` for an undeclared source and a `successor`, no second finding for a source that did not load, the version order of migrations (subdirectories, timestamps, files without a version), `ExternalRef` |
| `SqlSchemaTest` | Type names, `NOT NULL`, primary keys (also as a constraint before the columns), defaults, identity, generated and serial columns, every `ALTER TABLE` action and the ones that are skipped, `DROP TABLE`, temporary tables, statements without columns, comments, strings and dollar-quoted bodies, `acceptsNull` |
| `format.*ReferencesTest` | Each place a reference can stand, set to an element that does not exist, gives `S002` or `K004` at its pointer, against the Shipment example in the test resources (`openapi.json`, `asyncapi.json`, `schema.sql`) |
| `LocatedDocumentTest`, `LoadingTest` | Reading JSON and YAML, positions, one document, a mapping, duplicate keys, an empty document |
| `JsonPointerTest` | Escaping segments |
| `IncludeTest` | `Glob.regex`, and the migrations of a source as inputs of a build (`ContractFiles.inputs`) |
| `RialtoPluginTest` | The Gradle plugin tracks local sources and each migration as inputs |
| `ContractValidatorTest` | The test contracts of the specification, including `invalid/missing-source.yaml` (`S001`) and `invalid/unresolved-external.yaml` (`S002`) |
