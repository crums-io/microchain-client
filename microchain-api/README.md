# microchain-api

REST API contract layer: entity types and JSON parsers shared between
`microchain-cli` (client) and `microchain-webservices` (server).

## Package

`io.crums.micro.api`  —  module `io.crums.micro.api`

## Contents

| Class | Role |
|---|---|
| `ApiConstants` | JSON field-name constants for all endpoints |
| `ChainInfo` | Response record for `GET /{user}/{chain}/info` |
| `SqlConfig` | Response record for `GET /{user}/{chain}/sql-config` |
| `CommitRequest` | Request body for `POST /{user}/{chain}/commit` |
| `CommitResponse` | Response record for `POST /{user}/{chain}/commit` |

Each record has a nested `Parser` that implements
`JsonEntityParser<T>` (from **jsonimple**) for both serialisation and
deserialisation.

## Endpoint → type mapping

```
GET  /{user}/{chain}/info       → ChainInfo
GET  /{user}/{chain}/salts      → List<EpochSeed>  (EpochSeedParser, from skipledger-base)
GET  /{user}/{chain}/sql-config → SqlConfig
GET  /{user}/{chain}/state      → Path             (PathPackParser,  from skipledger-base)
POST /{user}/{chain}/commit     → CommitRequest / CommitResponse
```

`/salts` and `/state` use parsers from **skipledger-base**, which this module
re-exports transitively — no extra dependency needed for callers.

## SqlConfig: opening a local SqlLedger

`SqlConfig` is the bridge between the server and the client's local database.
It carries the parameterised SQL queries the server uses, plus an optional JDBC
URL.  Given the three read-only endpoint responses, a local `SqlLedger` can be
opened to hash rows before committing them:

```java
SqlConfig  cfg    = SqlConfig.PARSER.toEntity(sqlConfigJson);
SaltScheme scheme = ChainInfo.PARSER.toEntity(infoJson).saltScheme();
TableSalt  salt   = TableSaltReader.READER.toTableSalt(saltsArr);

try (Connection conn   = cfg.openConnection();
     SqlLedger  ledger = cfg.open(conn, scheme, salt)) {
    // hash rows via ledger.getSourceRow(rowNumber)
}
```

`openConnection()` uses the JDBC URL stored in `SqlConfig`; use the overload
`openConnection(Properties)` to pass credentials the server should not see.

## CommitRequest invariants

- `startRow` is 1-based and must equal the current ledger size + 1 (the
  endpoint is idempotent but does not allow gaps).
- Each entry in `inputHashes` is a 43-character Base64_32-encoded SHA-256 hash.

## Dependencies

- `skipledger-base` (transitively re-exported)
- `sql-ledger` (for `SqlLedger` factory methods on `SqlConfig`)
- `jsonimple` (JSON parsing/serialisation)
