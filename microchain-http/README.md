# microchain-http

`java.net.http` client library for all microchain REST endpoints.
Centralises HTTP handling so that the CLI and future rich clients share
the same networking code.

## Package

`io.crums.micro.http`  —  module `io.crums.micro.http`

## Maven coordinates

```xml
<dependency>
  <groupId>io.crums</groupId>
  <artifactId>microchain-http</artifactId>
</dependency>
```

Version omitted for now — not yet published anywhere resolvable outside this reactor.

## Classes

### ChainClient

Thin HTTP wrapper for every endpoint under `/{user}/{chain}/`.
Holds `HttpClient`, base URI, username, and chain name.

| Method | Endpoint | Auth | Status |
|---|---|---|---|
| `getInfo()` | `GET /info` | public | implemented |
| `getSalts(token)` | `GET /salts` | Bearer | implemented |
| `getSqlConfig(token)` | `GET /sql-config` | Bearer | implemented |
| `getColumns()` | `GET /columns` | public | implemented |
| `putColumns(token, List<ColumnInfo>)` | `PUT /columns` | Bearer | implemented |
| `getState()` | `GET /state` | public | implemented |
| `getState(rows, plusLatest, compress)` | `GET /state?rows=...&latest=...&compress=0\|1` | public | implemented |
| `commit(token, CommitRequest)` → `RowCount` | `POST /commit` | Bearer | implemented |

The same Bearer access token is used for `/salts`, `/sql-config`, `PUT
/columns`, and `/commit`. A missing or invalid token raises
`SecurityException` (unchecked). A hash conflict on `/commit` raises
`HashConflictException` (HTTP 409).

`getState()` with no arguments sends no query parameters (server defaults to
row 1 + latest, compressed).  The parameterised overload lets callers request
a path through specific row numbers with explicit `plusLatest` and `compress`
flags.

#### JSON parsing notes

- Object-returning endpoints (`/info`, `/sql-config`, `/state`, `/commit`)
  use each API type's `PARSER.toEntity(String)` directly — no intermediate
  `JSONObject` construction needed.
- `/salts` and `/columns` each return a JSON array; `/salts` goes through
  `TableSaltReader.READER.toTableSalt(JSONArray)`, which assembles all epoch
  seeds into a single `TableSalt`, while `/columns` uses
  `ColumnInfo.PARSER.toEntityList(JSONArray)`.
- The `/state` PathPack uses field names `stitch_rows`, `compression`, `hashes`
  with `enc=b64` (Base64_32), matched by the static `STATE_PARSER`.

### RemoteSkipLedger

Stub — extends `io.crums.sldg.SkipLedger` and will wrap `ChainClient`.

## Dependencies

- `microchain-api` (transitively re-exports `skipledger-base` and `jsonimple`)
- `java.net.http` (JDK built-in)
