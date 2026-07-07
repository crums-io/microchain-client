# microchain-client

Client-side tooling for committing rows from an on-premises SQL database into a
remote [SkipLedger](../skipledger/) microchain hosted by a
`microchain-webservices` instance.

## How it works

A microchain records the tamper-evident history of a SQL table (or view).
Rather than sending raw data to the server, the client computes a SHA-256 hash
of each row locally and submits only those hashes.  The server appends them to
a SkipLedger, a hash-pointer data structure that lets any third party verify
the chain's integrity with a logarithmic-sized proof.

The commit workflow:

1. Fetch chain metadata from the server (`/info`, `/salts`, `/sql-config`).
2. Open a local `SqlLedger` with the returned JDBC queries and salt config.
3. Query `/state` to find the current committed row count.
4. Hash the next batch of rows locally via `SqlLedger`.
5. POST the hashes to `/commit`; receive the updated row count.

## Modules

| Module | Artifact | Purpose |
|---|---|---|
| `microchain-api` | `io.crums:microchain-api` | REST contract — DTOs and JSON parsers shared with the server |
| `microchain-http` | `io.crums:microchain-http` | `java.net.http` client implementing `SkipLedger` over the REST API |
| `microchain-cli` | `io.crums:microchain-cli` | `picocli` command-line tool that drives the commit workflow |

## REST API surface

All endpoints are namespaced under `/{user}/{chain}/`:

| Verb | Path | Auth | Description |
|---|---|---|---|
| GET | `/info` | — | Chain metadata: ledger type, title, salt scheme |
| GET | `/salts` | Bearer | Salt seeds (secret — owner only) |
| GET | `/sql-config` | Bearer | JDBC queries, optional JDBC URL, timezone, default lag |
| GET | `/state` | — | Current skip-ledger state (PathPack JSON) |
| POST | `/commit` | Bearer | Append row hashes, receive updated row count |

### curl examples

Replace `https://micro.crums.io/alice/sales` with your chain URL and
`$TOKEN` with the Bearer token shown on the chain's manage page.

**GET /info** (public)
```sh
curl https://micro.crums.io/alice/sales/info
```

**GET /state** (public)
```sh
curl https://micro.crums.io/alice/sales/state
```

**GET /sql-config** (token required)
```sh
curl -H "Authorization: Bearer $TOKEN" \
     https://micro.crums.io/alice/sales/sql-config
```

**GET /salts** (token required)
```sh
curl -H "Authorization: Bearer $TOKEN" \
     https://micro.crums.io/alice/sales/salts
```

**POST /commit** (token required)

The request body is JSON with a `start_row` (1-based) and an `input_hashes`
array of Base64-encoded SHA-256 hashes (43 characters each, URL-safe alphabet).
The server requires `start_row` to be ≤ the current committed count + 1 and
rejects any hash that conflicts with an already-recorded value.

```sh
curl -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "start_row": 42,
       "input_hashes": [
         "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
         "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
       ]
     }' \
     https://micro.crums.io/alice/sales/commit
```

In practice the hashes are produced by the `mchain commit` command or the
`commit.java` JBang script; computing them by hand is only useful for testing.

## Dependencies

- **skipledger-base** (`io.crums.sldg.base`) — SkipLedger, PathPack, SaltScheme, TableSalt
- **sql-ledger** (`io.crums.sldg.src.sql`) — SqlLedger (local row hashing over JDBC)
- **jsonimple** (`io.crums.jsonimple`) — lightweight JSON library used for all parsing (except in the Quarkus server)
- **picocli** 4.7.5 — CLI framework (cli module only)

## Build

```
mvn package
```

Java 22+, Maven 3.9+.
