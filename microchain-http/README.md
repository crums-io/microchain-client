# microchain-http

`java.net.http` client library for all microchain REST endpoints.
Centralises HTTP handling so that the CLI and future rich clients share
the same networking code.

## Package

`io.crums.micro.http`  —  module `io.crums.micro.http`

## Classes

### ChainClient

Thin HTTP wrapper for every endpoint under `/{user}/{chain}/`.
Holds `HttpClient`, base URI, username, and chain name.

| Method | Endpoint | Auth | Status |
|---|---|---|---|
| `getInfo()` | `GET /info` | public | implemented |
| `getSalts(token)` | `GET /salts` | Bearer | implemented |
| `getSqlConfig(token)` | `GET /sql-config` | Bearer | implemented |
| `getState()` | `GET /state` | public | implemented |
| `getState(rows, plusLatest, compress)` | `GET /state?rows=...&latest=...&compress=0\|1` | public | implemented |
| `commit(token, CommitRequest)` | `POST /commit` | Bearer | implemented |

The same Bearer access token is used for `/salts`, `/sql-config`, and `/commit`.
A missing or invalid token raises `SecurityException` (unchecked).
A hash conflict on `/commit` raises `HashConflictException` (HTTP 409).

`getState()` with no arguments sends no query parameters (server defaults to
row 1 + latest, compressed).  The parameterised overload lets callers request
a path through specific row numbers with explicit `plusLatest` and `compress`
flags.

#### JSON parsing notes

- Object-returning endpoints (`/info`, `/sql-config`, `/state`, `/commit`)
  use each API type's `PARSER.toEntity(String)` directly — no intermediate
  `JSONObject` construction needed.
- `/salts` returns a JSON array; `TableSaltReader.READER.toTableSalt(JSONArray)`
  assembles all epoch seeds into a single `TableSalt`.
- The `/state` PathPack uses field names `stitch_rows`, `compression`, `hashes`
  with `enc=b64` (Base64_32), matched by the static `STATE_PARSER`.

### RemoteSkipLedger

Stub — extends `io.crums.sldg.SkipLedger` and will wrap `ChainClient`.

## Dependencies

- `microchain-api` (transitively re-exports `skipledger-base` and `jsonimple`)
- `java.net.http` (JDK built-in)
