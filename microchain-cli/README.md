# microchain-cli

`picocli`-based command-line tool for committing on-premises SQL rows to a
remote microchain and for inspecting source ledger rows locally.

## Package

`io.crums.micro.cli`  —  module `io.crums.micro.cli`

## Commands

| Command | Description |
|---|---|
| `mchain commit <chain-url>` | Hash new source rows locally and POST them to the chain |
| `mchain status <chain-url>` | Print the current committed row count |
| `mchain info <chain-url>` | Print chain metadata (salted, title, description) |
| `mchain rows` | Query source rows from the local SQL ledger |
| `mchain config <file> set` | Create or update a local config JSON file |
| `mchain receipt generate` | Generate a draft receipt from a `receipt_templates` entry |
| `mchain receipt anchor` | Attach a skipledger commitment path to a draft receipt |

Run any command with `--help` for the full option list.

## `mchain rows` quick reference

```sh
mchain rows -c local-config.json --row 42          # fetch row 42
mchain rows -c local-config.json -r 42             # same, short form
mchain rows -c local-config.json --where "order_id = 7"  # WHERE clause query
mchain rows -c local-config.json -w "order_id = 7" # same, short form
mchain rows -c local-config.json --count           # print total row count
mchain rows -c local-config.json -n                # same, short form
mchain rows -c local-config.json -w "x = 42" --pretty   # pretty-print
```

## Dependencies

- `microchain-api`
- `microchain-http`
- `picocli` 4.7.5

See the [top-level README](../README.md) for full documentation including the
local config file format, dynamic JDBC driver loading, and native-access warnings.
