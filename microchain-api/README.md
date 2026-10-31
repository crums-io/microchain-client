# microchain-api

REST API contract layer: entity types and JSON parsers shared between
`microchain-cli` (client) and server.

## Package

`io.crums.micro.api` (+ `io.crums.micro.api.receipt` subpackage) — module `io.crums.micro.api`

## Maven coordinates

```xml
<dependency>
  <groupId>io.crums</groupId>
  <artifactId>microchain-api</artifactId>
</dependency>
```

Version omitted for now — not yet published anywhere resolvable outside this reactor.

## Contents

| Class | Role |
|---|---|
| `ApiConstants` | JSON field-name constants for all endpoints |
| `ChainInfo` | Response record for `GET /{user}/{chain}/info` |
| `ColumnInfo` | One column's decorative name/description for `GET`/`PUT /{user}/{chain}/columns` |
| `SqlConfig` | Response record for `GET /{user}/{chain}/sql-config` |
| `DbConfig` | JDBC connection parameters (URL, driver class/jar, properties); embedded in `SqlConfig`, also usable standalone before a chain's SQL queries are known |
| `LocalConfig` | The `--local-config` JSON file format: shared defaults plus one `LocalConfig.ChainConfig` entry per chain |
| `CommitRequest` | Request body for `POST /{user}/{chain}/commit` |
| `RowCount` | `{"row_count": N}` — response for `POST /commit` and for a plain `SourceLedger` row-count read |
| `io.crums.micro.api.receipt.Receipt` | One or more related chains' revealed rows — the data behind a "receipt" |
| `io.crums.micro.api.receipt.ChainReceipt` | One chain's contribution to a `Receipt`: a `SourceReceipt` tied to a `chainUri()` |
| `io.crums.micro.api.receipt.SourceReceipt` | Revealed source rows, optionally anchored with a commitment path; enforces hash-consistency at construction |
| `io.crums.micro.api.receipt.ReceiptGeneratorTemplate` | A named, reusable recipe (ordered `RowSelector`s) for generating a `Receipt` — the local config file's `receipt_templates` entries |
| `io.crums.micro.api.receipt.RowSelector` | One chain's row-selection step within a `ReceiptGeneratorTemplate` (`where` + `args`, referencing earlier steps by alias) |

Each record has a nested `Parser` that implements
`JsonEntityParser<T>` (from **jsonimple**) for both serialisation and
deserialisation.

## Endpoint → type mapping

```
GET  /{user}/{chain}/info       → ChainInfo
GET  /{user}/{chain}/columns    → List<ColumnInfo>
PUT  /{user}/{chain}/columns    → List<ColumnInfo>
GET  /{user}/{chain}/salts      → List<EpochSeed>  (EpochSeedParser, from source-ledger)
GET  /{user}/{chain}/sql-config → SqlConfig
GET  /{user}/{chain}/state      → Path             (PathPackParser,  from skipledger-base)
POST /{user}/{chain}/commit     → CommitRequest / RowCount
POST /{user}/{chain}/verify     → receipt.ChainReceipt
POST /_verify                   → receipt.Receipt   (multi-chain)
```

`/state` uses a parser from **skipledger-base**; `/salts` uses one from
**source-ledger** (`io.crums.sldg.src.json`) instead — both modules are
re-exported transitively via `sql-ledger`, so no extra dependency is
needed by callers either way.

## SqlConfig / DbConfig: opening a local SqlLedger

`SqlConfig` is the bridge between the server and the client's local
database. It carries the parameterised SQL queries the server uses, plus
an optional `DbConfig` (JDBC URL, driver class/jar, connection
properties) — `DbConfig` is its own type specifically so connection
details can be carried (and dry-run tested) independently, before a
chain's SQL queries are even known. Given the relevant endpoint
responses, a local `SqlLedger` can be opened to hash rows before
committing them:

```java
SqlConfig cfg  = SqlConfig.PARSER.toEntity(sqlConfigJson);
TableSalt salt = ChainInfo.PARSER.toEntity(infoJson).salted()
    ? TableSaltReader.READER.toTableSalt(saltsArr) : null;

try (Connection conn   = cfg.openConnection();
     SqlLedger  ledger = cfg.open(conn, salt)) {
    // hash rows via ledger.getSourceRow(rowNumber)
}
```

`openConnection()` delegates to the embedded `DbConfig`'s connection
factory, which uses whatever JDBC URL/driver/properties it carries; use
the overload `openConnection(Properties)` to pass credentials the server
should not see.

## CommitRequest invariants

- `startRow` is 1-based and may not be greater than the current committed
  row count + 1 (the endpoint is idempotent but does not allow gaps).
- Each entry in `inputHashes` is a 43-character Base64_32-encoded SHA-256 hash.

## Receipts (`io.crums.micro.api.receipt`)

A `Receipt` groups one or more chains' revealed rows (each a
`ChainReceipt`, wrapping a `SourceReceipt`) — the shared shape produced by
`mchain receipt generate`/`anchor`/`redact`, and consumed by `/verify`,
`/_verify`, and the server's named-template `/render` endpoints. A fresh
`Receipt` is a *draft* (every entry's commitment path is empty); attaching
a path per entry (from each chain's `GET /state`, or in one round trip via
`/_verify`) upgrades it to *anchored*. `ChainReceipt#chainUri()` is any
URI, not necessarily dereferenceable — a chain hosted at a microchain web
service has an `https://` identifier, but an informally-maintained ledger
can use any other scheme as a stable identifier; `chainUrl()` is the
narrower, derived view, present only when `chainUri()` is actually an
`http`/`https` URL.

A `ReceiptGeneratorTemplate` is a named, reusable recipe for producing a
`Receipt`: an ordered list of `RowSelector`s, the first ("driver") fed
directly by the caller's generation-time input, each later one deriving
its own lookup value(s) — via `ROW_NO(:alias)` or `:alias.column` — from a
row already resolved by an earlier step. See the top-level README's
[Receipt templates](../README.md#receipt-templates) section for the full
worked example.

## Dependencies

- `skipledger-base` (transitively re-exported, for `Path`/`PathPack`)
- `sql-ledger` (for `SqlLedger` factory methods on `SqlConfig`, and
  transitively re-exports `source-ledger`'s `TableSalt`/`EpochSeedParser`)
- `util-common`
- `jsonimple` (JSON parsing/serialisation; transitively available)
