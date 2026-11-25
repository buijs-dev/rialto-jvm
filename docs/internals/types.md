# Types: internals

How rialto-jvm reads and checks the types of a contract. What the types are, their JSON forms and why, is in the guide
[Types](https://github.com/buijs-dev/rialto-spec/blob/develop/docs/guides/types.md) and in
[K2](https://github.com/buijs-dev/rialto-spec/blob/develop/spec/contract.md#k2-types); this document is about the code.
All classes are in `rialto-core`.

rialto-jvm validates contracts. It parses type expressions and checks them; it does not encode or decode values. The
rules on values (K2.1 to K2.10: exact numbers, time zones, enum members, discriminators, `json` pass-through) are rules
for generated code.

## Overview

| Class | Package | Role |
|---|---|---|
| `TypeExpression` | `core.model` | The parser of type expressions, and its result: `Scalar`, `Named`, `ListOf`, `SetOf`, `MapOf` |
| `Property` | `core.model` | A property as declared: `type` as text, `nullable`, the constraints, `maxSize`, `mediaTypes`; `typeExpression` parses `type` |
| `TypeDecl.Enum`, `TypeDecl.Union` | `core.model` | An enum (`members`, `extensible`) and a union (`discriminator`, `variants`) |
| `TypeExpressionChecks` | `core.validation.format` | Reports an invalid type expression (`RIALTO-K006`), a named type that is not declared (`RIALTO-K004`) and a `binary` in a collection (`RIALTO-P021`) |
| `ContractTypes` | `core.validation` | Looks up the properties of a type and resolves property paths through named types, lists and sets |
| `ContractSchema` | `core.schema` | The JSON Schema of the specification; checks the structure. A type expression is only a non-empty string there (K1.7) |

A type goes through two steps of `ContractValidator.validate`:

```text
ContractValidator.validate
  ├─ 3. ContractSchema.check                  a type that is no string, or empty  → K002
  │                                           a count above 2147483647            → K002
  └─ 6. FormatChecks → TypeExpressionChecks   only when the schema found no error
       ├─ TypeExpression.parse(type) == null  → K006 "Invalid type '…'"
       ├─ a name of the expression undeclared → K004 "Unknown type '…'"
       ├─ a map key names a non-enum type     → K006 "Map key '…' must be an enum"
       └─ a binary element or map value       → P021 "A binary cannot be an element of a collection or a map value"
```

## The parser

`TypeExpression.parse(text)` returns the parsed expression, or null when `text` is not a valid type expression. It is
the one parser of type expressions: checks and generators read `Property.typeExpression` instead of taking the string
apart.

| Input | Result |
|---|---|
| A name in `SCALARS` (`boolean` … `binary`) | `Scalar(name)` |
| `decimal(p,s)` | `Scalar("decimal", p, s)`; null when `s > p`, or when `p` or `s` does not fit an `Int` |
| `[A-Z][A-Za-z0-9]*` | `Named(name)`; whether it is declared is checked elsewhere |
| `list<T>`, `list<T?>` | `ListOf(element, nullableElements)` |
| `set<T>`, `set<T?>` | `SetOf(element, nullableElements)` |
| `map<K, V>`, `map<K, V?>` | `MapOf(key, value, nullableValues)`; null when `K` is a scalar not in `MAP_KEYS` (`string`, `uuid`, `int32`, `int64`, `date`) |
| anything else | null, for example `string?`, `list<>`, `map<string>`, `list<list<int32>>`, a lowercase name |

- Collections do not nest: the element, key and value are parsed as a scalar or a name only.
- The text is taken as written: no space around a type or inside it, except one optional space after the comma of a
  map, as in `map<string, Shipment>`.
- `names()` gives the named types an expression refers to: the element of a list or set, the key and value of a map.
- `Property.typeExpression` throws on an invalid type. The KDoc says it is valid once the contract passed validation.

## The checks

`TypeExpressionChecks` checks every type expression (`TypeUse`): the types of properties under `types`
(`Contract.typeExpressions()`), and the `context` of each slot of a view (`Contract.slotExpressions()`):

| Where | Check | Code |
|---|---|---|
| `types/<T>/properties/<p>/type` of an entity or value object | `TypeExpression.parse` gives null | `RIALTO-K006` |
| the same | a name from `names()` is not under `types` | `RIALTO-K004` |
| the same | the key of a `map` is a declared type that is not an enum | `RIALTO-K006` |
| the same | `binary` is the element of a `list` or `set`, or the value of a `map` | `RIALTO-P021` |
| `views/<V>/slots/<s>/context` | the same four checks as for a property | as above |
| `types/<T>/wraps` of a value object | `TypeExpression.parse` gives null | `RIALTO-K006` |
| `types/<U>/variants/<v>` of a union | the variant is not under `types` | `RIALTO-K004` |

A map key that names no type is `RIALTO-K004` only; whether it is an enum is not asked then. `Code.TYPE_EXPRESSION`
(`RIALTO-K006`) names K2.1 as its requirement, as the table of standard codes does.

## What the JSON Schema covers

`$defs/typeExpression` is a non-empty string: the form of a type is checked by the tool (K1.7, K2.1), so a wrong
type is `RIALTO-K006` or `RIALTO-P021` at the property, and the other findings of the contract are reported in the
same run. Other parts of types the schema checks: an enum has at least one member and no member twice; a union has a
`discriminator` and at least two `variants`; `wraps` is a scalar from `$defs/scalar`, without `binary`. Counts such as
`minLength`, `maxLength`, `minItems`, `maxItems` and `maxSize` are integers up to 2147483647, the largest 32-bit
integer. The model reads them as `Int`, and `maxSize` as `Long`.


## What the tooling does not check

`FormatChecks` is the only set in `ContractValidator.checks`. `Code` defines `RIALTO-P002`, `RIALTO-P003`,
`RIALTO-P012`, as its table equals the standard codes of the specification, but no check reports them; `RIALTO-P021`
is reported only for a `binary` in a collection. So rialto-jvm does not report:

- a union variant that declares the discriminator with a type other than `string`, or a variant that is a union, or
  variants that mix entities and value objects;
- a `binary` without `maxSize`, as a query field, or in a type an operation carries as JSON;
- a `json` property as a query field.

## Failure handling

| Failure | Result |
|---|---|
| A type that is no string | `RIALTO-K002` at the `type`; the model and `FormatChecks` do not run |
| An integer above 2147483647 in a constraint | `RIALTO-K002` at that key |
| A type the parser rejects | `RIALTO-K006` "Invalid type '…'" at `/types/<T>/properties/<p>/type`; its names are not checked |
| A `decimal(p,s)` with more digits than an `Int` holds | `RIALTO-K006`, not an exception |
| An undeclared name in a type | `RIALTO-K004` "Unknown type '…'" at the `type` |
| A map key that is a declared non-enum type | `RIALTO-K006` "Map key '…' must be an enum" |
| A `binary` as an element or map value | `RIALTO-P021` at the `type` |

## Tests

| Test class | What it covers |
|---|---|
| `TypeExpressionTest` | Scalars, decimals, names, collections with nullable elements, `names()`, and invalid expressions: `decimal(2,3)`, too many digits, `map<boolean, string>`, `string?`, `list<>`, a lowercase name, nested collections, spaces |
| `ModelTest` | `Property.typeExpression` parses a type, and throws on an invalid one |
| `TypeExpressionChecksTest` | `RIALTO-K006` and `RIALTO-K004` at their pointers on the Shipment example: a non-enum map key, an unknown map key or value, `decimal(2,3)` on a property and on `wraps`, too many digits, a nested collection, `map<boolean, string>`, the context of a view slot; `RIALTO-P021` for `list<binary>`, `set<binary>` and `map<string, binary?>`; `map<Status, string>` is valid |
| `ContractTypesTest` | Paths through named types, lists and sets; a path does not resolve past a scalar or through an invalid type |
| `ContractValidatorTest` | The test contracts of the specification, including `invalid/map-key.yaml` and `invalid/type-expression.yaml` (K006), `invalid/binary-in-list.yaml` (P021), `invalid/unknown-type.yaml` (K004) and `invalid/integer-overflow.yaml` (K002) |
| `SpecSnippetsTest` | The YAML blocks of the specification, including those of the Types section, are valid against the JSON Schema |
