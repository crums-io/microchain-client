# microchain-cli

`picocli`-based command-line tool for committing on-premises SQL rows to a
remote microchain.

## Package

`io.crums.micro.cli`  —  module `io.crums.micro.cli`

## Entry point

`io.crums.micro.cli.Main` — invoked as `microchain-cli [options] <command>`.

## Planned command structure

```
microchain-cli
  commit    Fetch chain config, hash new rows locally, POST /commit
  status    Print the current committed row count (GET /state)
  info      Print chain metadata (GET /info, /sql-config)
```

## Commit workflow (what the `commit` command will do)

1. Accept: server URL, username, chain name, Bearer access token, optional
   JDBC credentials.
2. `GET /info` → `ChainInfo` (salt scheme).
3. `GET /salts` → `TableSalt` (secret seeds; token required).
4. `GET /sql-config` → `SqlConfig` (JDBC queries, optional connection URL).
5. `GET /state` → current committed row count N via `PathPack`.
6. Open local `SqlLedger` via `SqlConfig.open(...)`.
7. Hash rows N+1 … M locally (M = `SqlConfig.rowCountQuery` result).
8. `POST /commit` with `CommitRequest(startRow=N+1, inputHashes=[...])`.
9. Print `CommitResponse.rowCount()`.

Steps 2–5 use `RemoteSkipLedger` (microchain-http); steps 6–7 use
`SqlLedger` (sql-ledger, accessed via microchain-api's `SqlConfig`).

## Status

**Stub** — `Main.run()` only prints a usage hint.  Subcommands are not yet
implemented.

## Dependencies

- `microchain-api`
- `microchain-http` (implicitly — CLI calls into http layer)
- `sql-ledger` (for local row hashing)
- `picocli` 4.7.5
