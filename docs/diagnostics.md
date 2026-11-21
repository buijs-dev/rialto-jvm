# Diagnostics

Every finding of Rialto tooling has a code `RIALTO-<area><nnn>`, a severity, a location (file, line and column, and a JSON Pointer into the document) and, where one applies, the specification requirement it enforces. The `K`, `S` and `P` codes are the specification's [standard diagnostic codes](https://github.com/buijs-dev/rialto-spec/blob/develop/spec/contract.md#standard-diagnostic-codes) (K1.10). Every implementation of Rialto tooling uses this table, so the same contract produces the same codes in every language.

Output format:

```text
error[RIALTO-K004] shipment.rialto.yaml:149:13 #/operations/approveShipment/emits/0: Unknown event 'ShipmentShipped' (K1.2)
  hint: …
```

| Area | Meaning |
|---|---|
| `K` | contract and schema |
| `S` | sources |
| `P` | profile rules |

| Code | Requirement | Meaning |
|---|---|---|
| `RIALTO-K001` | K1.7 | contract or fragment cannot be read or parsed, or is not a mapping |
| `RIALTO-K002` | K1.7 | contract is not valid against the JSON Schema, one diagnostic per violation at its pointer; also a mapping path key of more than two names, or one without `from` (M2.10), and a view `route` that is not `/` and segments of literal text or one `{parameter}` (U5.3) |
| `RIALTO-K003` | K1.1 | unsupported contract format version |
| `RIALTO-K004` | K1.2 | reference does not resolve |
| `RIALTO-K005` | K1.3 | name is used twice |
| `RIALTO-K006` | K2.1 | invalid type expression |
| `RIALTO-K007` | K5.2 | include path matches no file |
| `RIALTO-K008` | K5.2 | fragment declares info, sources or include |
| `RIALTO-K009` | K5.4 | section that belongs in one file appears in two |
| `RIALTO-K010` | K4.5 | rialto.lock cannot be read, or cannot be written when it must change |
| `RIALTO-S001` | K1.9 | referenced source cannot be read |
| `RIALTO-S002` | K1.2 | reference into a source does not resolve |
| `RIALTO-S003` | K4.3 | remote source can neither be fetched nor found in the cache |
| `RIALTO-S004` | K4.2 | remote source differs from its hash in rialto.lock |
| `RIALTO-S005` | K4.4 | remote source does not use https |
| `RIALTO-P001` | D2.2 | entity identity is not one of its properties |
| `RIALTO-P002` | D2.6 | union variants are not all entities or all value objects |
| `RIALTO-P003` | Q1.1 | query field or operator does not fit the output type |
| `RIALTO-P004` | Q1.3 | tiebreaker (a field, or the `field` of `{field, direction}`) is not a sortable query field |
| `RIALTO-P005` | E1.1 | operation returns an undeclared error code |
| `RIALTO-P006` | L1.2 | command property does not fit its relationship |
| `RIALTO-P007` | V1.4 | source version differs from the document version |
| `RIALTO-P008` | S1.1 | scope used by an operation is not declared |
| `RIALTO-P009` | Q1.4 | list wire element does not exist in the OpenAPI operation |
| `RIALTO-P010` | E1.3 | error wire pointer does not exist in the problem schema |
| `RIALTO-P011` | A1.1 | event subject is not a property of the event data |
| `RIALTO-P012` | K2.8 | a variant declares the discriminator with a type other than string |
| `RIALTO-P013` | Q1.6, Q3.1 | list wire or encodings do not fit a REST or local operation, or a list with `sort.fixed` declares a sort encoding or a `sort` wire parameter |
| `RIALTO-P014` | D3.5 | shape constraint weakens its source property's or does not apply to its type (`uniqueItems` applies to `list<T>` only, D3.8) |
| `RIALTO-P015` | R1.5, R1.7 | REST parameter is not mapped, its mapping does not resolve (against `subject`, `input`, `output` (`T` of `list<T>`), the transition's type or the link's owner; a path parameter of an operation with `subject` only against the subject), or a path through a list does not end at the identity of its element |
| `RIALTO-P016` | E2.2 | code types are not one template with {code}, or two codes or categories share a type |
| `RIALTO-P017` | V2.2 | deprecated operation is not marked deprecated in its OpenAPI operation |
| `RIALTO-P018` | E1.5 | with locationFormat path, a request body property and a parameter or header share a name |
| `RIALTO-P019` | Q5.2 | count operation does not take the list's filter, or requires a parameter the list does not have |
| `RIALTO-P020` | R6.1 | conditional operation is not a GET or HEAD with If-None-Match, an ETag on success and a 304 |
| `RIALTO-P021` | K2.11 | binary without maxSize, in a collection, as a query field, or carried as JSON |
| `RIALTO-P022` | S6.1 | apiKey scheme of an operation is not declared under auth.apiKeys, or not sent in a header |
| `RIALTO-P023` | L5.1, L5.9 | transition state is not a closed enum with a member default, a rule or a delete's `from` names a non-member, `transition` (or a rule of its `select`) names an unknown rule, or a command shape contains the state property |
| `RIALTO-P024` | L3.2 | idempotency on a safe method, or its header is not a header of the OpenAPI operation |
| `RIALTO-P025` | M2.8, M2.9, M2.10 | mapping names a property neither side has (each path of `from: {identity, command}` too), leaves a required property uncovered, or uses `version: stored` without a stored version; a path key `<p>.<q>` (M2.10, located at the key) has a `<p>` that is not a to-one owned child or a value object with properties of a contract side (a type, a projection included, or a shape), or a `<q>` its type lacks |
| `RIALTO-P026` | P1.5, P1.6 | stored property of an entity or projection has no or two places (a column, columns under its path, a child, a link, a blob or a computed value), shares a plain column, a key under `columns`, `children`, `links`, `blobs` or `computed` names no property, or its column type or nullability does not fit (P1.6); the `key` column of a join, in the joined table or the last table of a join of two steps, is checked by type family only; the `order` column of a list that is not an integer column, or that accepts null, at `order` (P4.13) |
| `RIALTO-P027` | L4.5 | batch wire pointer does not resolve in the OpenAPI operation, or is not an array: `items` and `results` (`""` is the body itself), and `value`, `problem`, `index` and `status` in an element of `results`, the last two an integer |
| `RIALTO-P028` | L1.6 | update command shape has an `immutable` property |
| `RIALTO-P029` | L6.1 | `link` does not name a to-many reference with a link table, its parameters do not map to the identity of owner and target, or the operation also declares `lifecycle`, `input` or `transition` |
| `RIALTO-P030` | P3.6, R1.6 | a `unique` key names a property without a plain column or a join of one step (a join of two steps or a computed value is not a column of the table), or the path parameters of an operation that addresses one entity (its `subject` when declared) without its whole identity map to properties that are not a declared unique key |
| `RIALTO-P031` | P6.3, P6.10, L3.7, R1.7 | `deleted` on an operation that is not a read (a GET whose output is one value or `list<T>`) or list operation, or on an entity without soft deletion; a delete that names no entity (no `input` of it, no `subject` and no `output` that is the entity, R1.7); a restore without `subject`, or whose subject's storage lacks `soft` or `restore: true`; a `subject` that is not an entity (with `by: actor` that is `RIALTO-P034`); or a command shape property that maps to the soft-delete marker or `by` column (L1.1). `by` or `restore` without `soft` is `RIALTO-K002` (the schema) |
| `RIALTO-P032` | L1.7 | `as: [identity, command]` outside a create command, on a property that is not a reference, or without `shape` naming the create command shape of its type; or a mapping of such a property without `from: {identity, command}`, or with that `from` on any other property |
| `RIALTO-P033` | R2.7 | `output: list<T>` without `maxItems`, on a `list` operation, or with a success response that is not an array whose `items` is `T`'s schema (the schema a mapping pairs with `T` in that source, both resolved through `$ref`, when there is one; else an object schema) |
| `RIALTO-P034` | R1.8, S4.1 | `subject: {type, by: actor}` whose type is not an entity, a parameter that maps to its identity, an input that carries it (the subject type itself, or a shape of it with an identity property), or an operation that needs no signed-in caller: its effective OpenAPI security is empty or has an empty `{}` alternative, or, for a local operation, the contract declares no `auth`; an input with a property with role `actor`, also in a nested shape, of an operation that needs no signed-in caller (S4.1, at `input`) |
| `RIALTO-P035` | P6.11 | a `storage.<type>.scope` that names a column twice, or a column that is not in its table (when the SQL source is read); a stored entity with a reference, managed link or to-many reference, also through a value object it holds (reported at the entity's property), to a type whose scope has a source that its own scope lacks (sources are compared, not columns; owned children are left out); storage entries on one table whose scopes differ, compared as sets of column and source (a projection is filtered as its source is); a command shape property that maps to a scope column, itself or through a path into a value object (`meta.owner`); or an operation that reaches a type with an `actor` entry and needs no signed-in caller, as for `RIALTO-P034`. An operation reaches a type when it is its `subject`, the source of its input, of its output or of the element of `list<T>`, the type of its transition, or the owner or target of its link; a count operation reaches what the lists that name it reach |
| `RIALTO-P036` | L5.6, L5.7, L5.8, L5.9, L5.10 | a `select` whose rules belong to several entities, whose operation has no `rest`, that does not point at a string (a `type` that is or includes `string`) in the request body of each referenced OpenAPI operation, or whose key is not in the `enum` there; a `sets` entry on an unknown property, the identity or the state property, `now` on a property whose type is not `instant`, `offsetDateTime` or `zonedDateTime` or a value type that wraps one, `null` on a non-nullable property, an input shape of an operation performing the rule that contains the property, or `actor` performed by an operation that needs no signed-in caller (as for `RIALTO-P034`); a create whose transition rule does not start from the default of the state property; `from` on an operation that is not a delete, or on a delete of an entity without `transitions`; or an operation that performs a rule with `emits` and lists one of its events in its own `emits` (L5.10). Each finding names its own requirement |
| `RIALTO-P037` | U3.9 | an action's `values` pointer that does not resolve in the request body of each referenced OpenAPI operation of its operation, a value its schema does not admit by `type` (an integer fits `number`; `nullable: true` admits null) or `enum`, or a value at the operation's `select` that is not a key of its `rules` |
| `RIALTO-P038` | D3.6, D3.7, D3.8 | per type property: `requiredWhen` or `absentWhen` on a non-nullable property (at the rule), naming a property that is not another enum property of the type (at `<rule>/<property>`), a member its enum lacks (at `<rule>/<property>/<index>`), or a member in both rules (at `absentWhen/<property>/<index>`); `minimumOf` or `maximumOf` naming no other property, or one whose scalar (a wrapper unwrapped) differs or is not ordered (at the key); `uniqueItems` on a type other than `list<T>` |
| `RIALTO-P039` | M3.11 | a `presence` entry in a `toSource` mapping, on a name the target lacks (also when the source of a `both` mapping has it), or on a target property that is not a non-nullable `boolean` (at the key); a presence property the source lacks (at `presence`); a source that is not a schema of an `openapi` source or a partial shape, such as a SQL table (at `source`, once per mapping) |
| `RIALTO-P040` | P2.11, P2.12, P2.13 | in the storage of an entity or projection, with a column path checked by the property it ends in: a join of two steps on a collection (at the column), under `generated` (at the generated key), or carried by a command shape, also through the value object on its path (at the shape property); a single join without `relation` on a type that is not a scalar or a wrapper of one (at the column); a `computed` property that is not a non-nullable `int32` or `int64` (at the entry), that counts a property that is not a `list<T>` or `set<T>` under `children` or `links` (at `count`), that is under `generated` (at the generated key), or that a command shape carries (at the shape property) |
| `RIALTO-P041` | U5.3, U5.9, U5.10, U5.11 | a route with a parameter twice, or a route parameter that fills properties of different scalars in the operations of its view (`load`, `submit`, `list.operation`, field `edit`s, actions) (at `route`); a `navigation` `home` or entry whose view has no route or route parameters (at `home` or the entry's `view`), a view in two entries (at the second), a `badge` that is not the `count.operation` (strategy `operation`) of the list operation of the entry's view, or that requires a parameter in OpenAPI (a path parameter or a `required` one) (at `badge`); a view with tabs but no route (at `tabs`); a tab without a route, with tabs of its own, with a route parameter its view's route lacks, or that fills a parameter with another scalar than its view (at the tab); a `link` to a view without a route or with a route of more than one parameter, a link on a view without data (no `load` and `submit`, or one that gives no single type), or an `open` to a view without a route; a value that cannot fill a route parameter: a path that does not resolve in the view's data or the row, goes through a collection, is not a scalar, enum or wrapper, or has another scalar than the parameter fills in the target view (at `link` or `open`). `open` fills a parameter from the list view's route when both views fill the same property with it, else from the row |
| `RIALTO-P042` | U3.10, U6.5, U6.6, U6.7 | a row action parameter without a source, or with more than one: a route parameter with its name or that fills the same property in an operation of the view, and the row property its path ends at when it ends in the row's type (at the action's `operation`); a declared source in `parameters` that names no parameter of the operation, no route parameter, no row property, a collection, or another scalar (at the entry of `parameters`); `parameters` on an action that is not a row action (at `parameters`); an action of a list view without `load` and `submit` whose parameter the route does not fill (at the action's `operation`); a `where` field that is no query field of the list operation (at `field`), an operator it does not declare (at `operator`), or a field that is not a `string` or `uuid` (at `field`); a column that does not resolve in the row, goes through a map or two collections, or does not end at a scalar, enum or wrapper (at the column); a `list.operation` that is neither a list operation nor an `output: list<T>` (at `operation`), or is the latter while the view declares `filters`, `where` or `pageSize` (at that key) |
| `RIALTO-P043` | U3.11 | per entry of `visibleWhen` or `enabledWhen` of an action, row action or field (at the entry): a path that does not resolve in the row (row action) or the view's data, goes through a collection, is not of an enum type, lists a member the enum lacks, or for `actor` is not a `string` or `uuid`; or a view without data (no `load` and `submit`, or a `load` without a single output or a `submit` without input) whose action or field declares a condition |
| `RIALTO-P044` | U2.11 | an `options` field that edits no input (no `edit` and no `submit`, at `options`), is not a to-one property of that input, or references no single entity (its type with `relation: reference`, or the value type of the single identity of one entity) (at `options`); an operation that is neither a list operation nor an `output: list<T>`, returns another type than the entity or a projection that carries its identity (under any name), has an `input`, or has a parameter that neither maps to `label` nor is filled by exactly one route parameter of the view of its scalar (at `operation`); a `label` that does not resolve to a scalar, enum or wrapper outside a collection (at `label`) |
| `RIALTO-P045` | P4.13 | in the storage of an entity or projection: a `list<T>` under `children` or `links` that declares neither `order` nor `orderBy`, or both (at the entry); `order` or `orderBy` on a `set<T>` or on a property that is not a list (at the key), or `order` on a link without a link table (at `order`); an `order` column that is not for the position alone: one that a property of the element maps to, the `parentKey`, the `ownerKey` or `targetKey` of the link, or the element's storage key, `version`, a column of its blobs, its scope or the `ownerKey` of its to-one children (at `order`); an `orderBy` entry that names a property twice, a property the element type lacks, or one that is not a `string` or an ordered scalar, directly or through a wrapper (at the entry, `orderBy/<index>`). An `order` column outside the `int32` and `int64` families, or one that accepts null, is `RIALTO-P026`, and one that the child's table or the link table lacks `RIALTO-S002`, both at `order`; an `orderBy` entry without `asc` or `desc`, or one listed twice, is `RIALTO-K002` |

The `K` and `S` codes are checked in full. The `P` codes are those of the profile rules: the table holds all of them, and
the checks of each profile come with its slice of the [road to 1.0](../README.md#status). The tooling's own codes (generation,
export, compatibility) come with the commands that report them.

The shared test contracts in `rialto-spec/examples/contracts` name the code each invalid contract must produce.

## JSON output

`rialto validate --format json <contract>...` prints one JSON document on stdout instead of text, with the exit
code unchanged (0 valid, 1 errors, 64 wrong usage, 70 when rialto itself fails). It follows the informative form of the specification
(contract.md, standard diagnostic codes).

```json
{
  "valid": false,
  "contracts": [{
    "file": "shipment.rialto.yaml",
    "valid": false,
    "diagnostics": [{
      "severity": "error", "code": "RIALTO-K004", "requirement": "K1.2",
      "file": "contract/shipment/operations.rialto.yaml", "line": 42, "column": 15,
      "pointer": "/operations/getShipment/output",
      "message": "Unknown type 'Shipmnt'", "hint": null
    }]
  }]
}
```
