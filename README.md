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

| Verb | Path | Description |
|---|---|---|
| GET | `/info` | Chain metadata: ledger type, title, salt scheme |
| GET | `/salts` | Salt seeds (secret, requires access token) |
| GET | `/sql-config` | JDBC queries and optional connection URL |
| GET | `/state` | Current skip-ledger state (PathPack JSON) — public |
| POST | `/commit` | Append row hashes — requires Bearer access token |

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
