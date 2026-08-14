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

## Command-line tools

Two tools drive the commit workflow:

| Tool | How to run | Best for |
|---|---|---|
| `mchain` | `mchain commit <chain-url> …` | Recurring scheduled commits, full CLI |
| `commit.java` | `jbang run commit.java <chain-url> …` | Quick one-off use, no install required |

Both tools share the same options.  Run with `--help` for the full list.

### Core options

```
<chain-url>              Full chain URL: https://micro.crums.io/{user}/{chain}
--token, -t <token>      Bearer access token (may be omitted when --local-config supplies it)
--local-config, -c <file>  Local JSON config file (see below)
--jdbc-url <url>         JDBC connection URL (overrides server config and local-config value)
--jdbc-user <user>       JDBC username
--jdbc-pass [<pass>]     JDBC password (omit value to be prompted interactively)
--jdbc-driver-jar <jar>  Path to a JDBC driver JAR to load dynamically (see below)
--timezone, --tz <tz>    IANA timezone for hashing DATE/TIMESTAMP columns
--from <n>               First row to submit (default: current chain length)
--to <n>                 Last row to submit, inclusive (mutually exclusive with --lag)
--lag <n>                Trailing rows to withhold (mutually exclusive with --to)
--batch, -b <n>          Max rows per commit request (default: 1000)
```

### Dynamic JDBC driver loading (`--jdbc-driver-jar`)

When a JDBC driver is not on the classpath, pass its JAR directly:

```sh
mchain commit https://micro.crums.io/alice/sales \
    --local-config ~/.config/crums/sales.json \
    --jdbc-driver-jar /path/to/sqlite-jdbc-3.50.3.0.jar
```

The JAR can also be specified in the local config file with the `jdbc_driver_jar`
key (relative paths are resolved against the config file's directory):

```json
{
  "jdbc_url":        "jdbc:sqlite:/data/sales.db",
  "jdbc_driver_jar": "drivers/sqlite-jdbc-3.50.3.0.jar",
  "by_no_query":     "SELECT amt, ts FROM sales_ledger WHERE rn = ?",
  "row_count_query": "SELECT MAX(rn) FROM sales_ledger"
}
```

The JAR is loaded into a JPMS named module at runtime, so it stays isolated from
the application's own module graph.

#### Native-access warnings

Some JDBC drivers bundle a native library (`.so` / `.dylib`) that they extract
and load via `System::load` at connect time.  SQLite is the most common example.
From Java 21 onwards, calling restricted methods such as `System::load` from a
named module without explicit permission prints a JVM warning to stderr:

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by org.sqlite.SQLiteJDBCLoader
         in module org.xerial.sqlitejdbc ...
WARNING: Use --enable-native-access=org.xerial.sqlitejdbc to avoid a warning
WARNING: Restricted methods will be blocked in a future release unless native
         access is enabled
```

The warning is about the **driver**, not about `mchain` itself — `mchain` uses
no native code of its own.

**Suppressing the warning** via `JAVA_OPTS` (the module name comes from the JAR's
`Automatic-Module-Name` manifest attribute, or `module-info.class`):

```sh
# SQLite
JAVA_OPTS="--enable-native-access=org.xerial.sqlitejdbc" mchain rows -c cfg.json -n

# Any other native driver — replace the module name accordingly
JAVA_OPTS="--enable-native-access=<module-name>" mchain commit ...
```

**Future Java compatibility:** a future JDK release will promote this warning to a
hard failure — the native library will not load at all without the flag.  If you use
a JDBC driver that bundles native code, add the appropriate `--enable-native-access`
value to your `JAVA_OPTS` now to be forward-compatible.

---

### Local config file (`--local-config`)

The `--local-config <file>` option reads chain configuration from a local JSON file
instead of (or in addition to) fetching it from the server.  There are two main
reasons to use it:

**1. Dry-run / pre-registration.**  You can configure and test the commit workflow
entirely locally — writing SQL queries, verifying the JDBC connection, and checking
that row hashes are computed correctly — before the chain exists on the server at all.
Once the results look right, register the chain on the website and start committing
for real.

**2. Keeping sensitive values out of shell history.**  Bearer tokens, salt seeds, and
JDBC connection strings passed as command-line arguments appear in shell history and
process listings.  Storing them in a file (with `chmod 600`) avoids that exposure.

#### File format

A JSON object combining the fields from `GET /sql-config` and `GET /salts`, plus an
`access_token` field.  All fields are optional — absent ones are fetched from the
server as normal.

**Fully configured — unsalted chain:**

```json
{
  "access_token":      "mcw_...",
  "by_no_query":       "SELECT item_count, price, txn_date FROM sales WHERE row_no = ?",
  "row_count_query":   "SELECT  MAX(row_no) FROM sales",
  "jdbc_url":          "jdbc:postgresql://db.example.com:5432/mydb",
  "jdbc_driver_class": "org.postgresql.Driver",
  "timezone":          "America/New_York",
  "default_lag":       5
}
```

**Fully configured — salted chain** (adds the `salts` array; the `seed` is the
32-byte secret encoded as a 43-character Base64_32 string):

```json
{
  "access_token":      "mcw_...",
  "by_no_query":       "SELECT item_count, price, txn_date FROM sales WHERE row_no = ?",
  "row_count_query":   "SELECT MAX(row_no) FROM sales",
  "jdbc_url":          "jdbc:postgresql://db.example.com:5432/mydb",
  "jdbc_driver_class": "org.postgresql.Driver",
  "timezone":          "America/New_York",
  "default_lag":       5,
  "salts": [
    { "seedStart": 1, "seed": "<43-char Base64_32>" }
  ]
}
```

A chain with multiple salt epochs has one entry per epoch, ordered by `seedStart`.
Each epoch covers rows from its `seedStart` up to (but not including) the next
epoch's `seedStart`.

Protect the file:

```sh
chmod 600 ~/.config/crums/sales.json
```

#### Precedence

| Priority | Source |
|---|---|
| 1 (highest) | CLI flag (`--token`, `--jdbc-url`, `--timezone`, `--lag`, …) |
| 2 | Local config file |
| 3 | Server response (`GET /sql-config`, `GET /salts`) |

When the file supplies `by_no_query` + `row_count_query`, the `GET /sql-config` call
is skipped entirely.  When the file supplies a `salts` array, `GET /salts` is skipped.

Note the file also supports a `chain_url` setting. When set, the command line
argument (chain URL) need not be supplied. 

### Designing and setting up a microchain

A recommended workflow for turning an existing SQL table (or view) into a
microchain, from first query to first commit. `mchain config <file> set
<options>` creates or updates the local config file one field at a time —
options mirror the commit command flags, and any option not supplied is left
unchanged, so the file can be built up incrementally across these steps.

1. **Design the ledger query first.**  Before anything exists on the server,
   define the JDBC connection and the two required queries:

   ```sh
   mchain config ~/.config/crums/sales.json set \
       --jdbc-url jdbc:postgresql://db.example.com:5432/mydb
   mchain config ~/.config/crums/sales.json set \
       --by-no-query "SELECT item_count, price, txn_date FROM sales WHERE row_no = ?"
   mchain config ~/.config/crums/sales.json set \
       --row-count-query "SELECT MAX(row_no) FROM sales"
   ```

   `by_no_query` must return **at most one row**; an empty result set is
   interpreted as a "null" row rather than an error. The column values it
   returns for a given row number must **never change** — a microchain
   records the immutable history of the query's *results*, so editing an
   already-hashed row afterward silently invalidates that row's commitment.

   Give each selected column a deliberate name via SQL `AS`, e.g.
   `SELECT order_id, item_count AS Count, unit_price AS Price FROM sale_items WHERE sale_item_id = ?`.
   `mchain rows --pretty` falls back to whatever the JDBC driver reports as
   the column's label when no name has been configured server-side (see step
   7), so a query written with clear `AS` aliases displays sensibly even
   before column metadata exists.

2. **Test the queries**, entirely offline, before registering the chain
   anywhere:

   ```sh
   mchain status -c ~/.config/crums/sales.json
   mchain rows   -c ~/.config/crums/sales.json --row 1 --pretty
   mchain rows   -c ~/.config/crums/sales.json --where "row_no = 42" --pretty
   ```

3. **Declare the chain URL on the server** — register the chain on the
   website, e.g. as `https://micro.crums.io/alice/sales`.

4. **Set the chain URL** in the config file, then confirm it resolves:

   ```sh
   mchain config ~/.config/crums/sales.json set \
       --chain-url https://micro.crums.io/alice/sales
   mchain status -c ~/.config/crums/sales.json
   ```

5. **Define the salt scheme** — preferably on the website, since the scheme
   itself is public information a verifier needs (see `GET /info` below). If
   the scheme salts any cells, the server generates a random salt seed
   automatically. That seed *is* sensitive — a chain owner who'd rather manage
   it themselves (to back it up independently, or precompute it before
   registering) can instead set the scheme and seed locally:

   ```sh
   mchain config ~/.config/crums/sales.json set --salt-scheme all
   mchain config ~/.config/crums/sales.json set --salt-seed
   ```

   `--salt-scheme` follows the `<base>[:<except-indices>]` format (`all`,
   `none`, `all:0,2`, …) — see `mchain config set --help`. `--salt-seed`
   without a value generates a cryptographically random 32-byte seed via
   `SecureRandom` and prints it; it sets (or replaces) the single salt epoch
   starting at row 1. Back the seed up securely — it's required to reproduce
   any row's input hash.

6. **Retrieve the chain's access token** from its manage page on the website
   and store it, then protect the file (it now holds secrets):

   ```sh
   mchain config ~/.config/crums/sales.json set --token mcw_...
   chmod 600 ~/.config/crums/sales.json
   ```

7. **Optionally name the columns** — decorative metadata (it's never part of
   the commitment hash) that `mchain rows --pretty` displays in place of bare
   positional indices, and that future features (`/verify` receipts,
   Qute-rendered templates) will use to label cells for recipients who hold a
   source row but no bearer token. Unlike the rest of the chain's
   configuration, this is public and is set directly via the CLI — no manual
   server-side step required:

   ```sh
   mchain columns -c ~/.config/crums/sales.json \
       --name 0="Order ID" \
       --name 1="Count" \
       --name 2="Price" --description 2="Unit price, pre-tax"
   ```

   Options not supplied are left unchanged — the command fetches the current
   list, merges in the given `--name` / `--description` / `--clear`
   overrides, and PUTs the full merged list back. Run without any of those
   options to view the current metadata:

   ```sh
   mchain columns -c ~/.config/crums/sales.json
   ```

   Column indices are 0-based, matching `--salt-scheme`. As noted in step 1,
   a query written with clear `AS` aliases already displays sensibly via the
   JDBC fallback, so this step is most useful for names the query itself
   can't express well, or for adding a longer `--description`.

8. **Re-test**, if appropriate, to confirm the server-side configuration
   (salt scheme, token, column names) works end-to-end:

   ```sh
   mchain rows -c ~/.config/crums/sales.json --row 1 --pretty
   ```

9. **Commit** — a few rows first as a sanity check, then everything:

   ```sh
   mchain commit -c ~/.config/crums/sales.json --to 10
   mchain commit -c ~/.config/crums/sales.json
   ```

### Commit examples

```sh
# No chain config on the server — all config from a local file
mchain commit --local-config ~/.config/crums/sales.json

# JBang equivalent:
jbang run --deps org.postgresql:postgresql:42.7.3 commit.java \
    --local-config ~/.config/crums/sales.json

# Override the JDBC URL at run time (e.g. to test against a staging DB):
mchain commit https://micro.crums.io/alice/sales \
    --local-config ~/.config/crums/sales.json \
    --jdbc-url jdbc:postgresql://staging-db:5432/mydb
```

---

## REST API reference

All chain endpoints are namespaced under `/{user}/{chain}/`.  In the examples
below, replace `https://micro.crums.io/alice/sales` with your chain URL and
`$TOKEN` with the Bearer token shown on the chain's manage page.

### Endpoint summary

| Verb | Path | Auth | Description |
|---|---|---|---|
| GET | `/{user}/_chains` | — | List all chains for a user |
| GET | `/{user}/{chain}/info` | — | Chain metadata: ledger type, title, salt scheme |
| GET | `/{user}/{chain}/columns` | — | Decorative column names/descriptions |
| PUT | `/{user}/{chain}/columns` | Bearer | Replace column names/descriptions |
| GET | `/{user}/{chain}/state` | — | Skipledger commitment path (PathPack) |
| GET | `/{user}/{chain}/salts` | Bearer | Salt epoch seeds (owner only) |
| GET | `/{user}/{chain}/sql-config` | Bearer | JDBC queries, connection URL, timezone, default lag |
| POST | `/{user}/{chain}/commit` | Bearer | Append row hashes; receive updated row count |

---

### `GET /{user}/_chains` — list chains (public)

```sh
curl https://micro.crums.io/alice/_chains
```

Returns a JSON array of chain summaries for the user.  Each entry has:
`name`, `title`, `description`, `ledger_type` (1 = SQL ledger),
`has_salt` (boolean), `created_at` (ISO-8601 timestamp).

---

### `GET /info` — chain metadata (public)

```sh
curl https://micro.crums.io/alice/sales/info
```

Response fields:

| Field | Type | Description |
|---|---|---|
| `ledger_type` | int | `1` = SQL-backed ledger |
| `title` | string | Display title (omitted if not set) |
| `description` | string | (omitted if not set) |
| `salt_scheme` | object | `{ "salt_code": 0\|1, "cell_indices": [...] }` |

`salt_code` 0 means row cells are hashed as-is; 1 means each cell value is
salted with a per-cell secret derived from the chain's salt seed (required to
reproduce the input hash from a revealed row).  `cell_indices` lists which
column positions within a row are salted.

---

### `GET` / `PUT /columns` — column metadata (public read, Bearer write)

Decorative per-column metadata: a human-readable name and optional
description, keyed by 0-based column index (matching `salt_scheme.cell_indices`
and `SourceRow.cells()` ordering). Unlike `/sql-config`, `GET` requires no
token — this data is meant to label cells for recipients who hold a source
row but no bearer token (e.g. future `/verify` receipts, Qute-rendered
templates).

```sh
# View (public)
curl https://micro.crums.io/alice/sales/columns

# Replace (Bearer; full-list replace — see `mchain columns` below for
# incremental edits that preserve entries you don't mention)
curl -X PUT \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '[
       { "index": 0, "name": "Item Count" },
       { "index": 1, "name": "Price", "description": "Unit price, pre-tax" }
     ]' \
     https://micro.crums.io/alice/sales/columns
```

**Response** — a JSON array, ordered by `index` ascending:

```json
[
  { "index": 0, "name": "Item Count" },
  { "index": 1, "name": "Price", "description": "Unit price, pre-tax" }
]
```

| Field | Description |
|---|---|
| `index` | 0-based column position |
| `name` | Human-readable column name |
| `description` | Optional longer explanation (omitted if not set) |

`PUT` is a full-list replace: indices not included in the request body are
removed. Gaps in indices are allowed — not every column needs a name.

**Error responses:**

| HTTP | Condition |
|---|---|
| 401 | Missing or invalid Bearer token (`PUT` only) |
| 404 | Chain not found |
| 422 | Malformed body, blank `name`, negative `index`, or duplicate `index` |

---

### `GET /state` — commitment path (public)

Returns a skipledger **PathPack**: a compact hash proof linking one or more
rows to the chain's latest committed state.

```sh
# Default: path from row 1 to the latest committed row
curl "https://micro.crums.io/alice/sales/state"

# Singleton path for the latest row (used by mchain status / commit workflow)
curl "https://micro.crums.io/alice/sales/state?rows=last"

# Path linking rows 5, 11, and the latest; hex-encoded hashes
curl "https://micro.crums.io/alice/sales/state?rows=5,11,last&enc=hex"
```

#### Query parameters

| Parameter  | Default  | Description |
|------------|----------|-------------|
| `rows`     | `1,last` | Comma-separated stitch row numbers (1-based) and/or `last` (the latest committed row). At least one token is required. |
| `enc`      | `b64`    | Hash encoding: `b64` (43-char Base64_32, URL-safe) or `hex` (64-char hex) |
| `compress` | `1`      | `1` to return a compressed path (if compressible); `0` for a full path |

#### Stitch-row expansion

The `rows` values are *stitch row numbers*.  Connecting any two rows in a
skipledger requires passing through a specific set of intermediate rows
determined by the skipledger skip structure.  The server expands the
requested set to include all required intermediaries automatically.

Examples:

| `rows=` | Expanded stitch rows |
|---|---|
| `last` | `[N]` (singleton) |
| `1,last` | `[1, 2, 4, 8, …, N]` (full ladder) |
| `1,16` | `[1, 2, 4, 8, 16]` |
| `3,31` | `[3, 4, 8, 16, 24, 28, 30, 31]` |

The expansion rules are defined by `SkipLedger.stitch()` / `stitchCompress()`
in the `skipledger-base` module.

#### PathPack response

Skipledger commitment paths (succinct hash proof linkings the commitment hashes of
any number of rows in the ledger together) are encoded as `PathPack`s. A path-pack
contains very little redundant data. For example, the commitment hash of the
last row in a path-pack is *not written*; only its *input hash* is: the commitment
hash of the row is *computed* by the client.

```json
{
  "stitch_rows": [1, 16],
  "compression": 1,
  "hashes":      ["<hash>", "<hash>", "<hash>", "<hash>", "<hash>", "…"]
}
```

`stitch_rows` lists the row numbers that must be included in the path. As noted
above, stitch-row numbers `[1, 16]` expands to `[1, 2, 4, 8, 16]` (and are
equivalent); the server, however, always chooses the most compact (abbreviated)
representation (`[1, 16]` in the example above). For each row number (after
expansion), the response returns its **input hash** — the SHA-256 of the source
ledger row — along with whatever auxiliary hashes are needed for a verifier to
derive each row's skipledger commitment hash.  Commitment hashes are not
transmitted directly; they are computed from the input hashes and auxiliary
hashes by the verifying client.

When `compress=0`, all row hashes in the path are present; `compress=1`
elides hashes that a verifier can recompute from its neighbours, producing a
smaller but still fully verifiable proof.

#### Error responses

| HTTP | Condition |
|---|---|
| 404 | Chain not found, or chain has no committed rows |
| 422 | `rows` contains an invalid token or a row number < 1 |

---

### `GET /salts` — salt epochs (Bearer)

```sh
curl -H "Authorization: Bearer $TOKEN" \
     https://micro.crums.io/alice/sales/salts
```

Returns an array of salt epoch objects, ordered by `seed_start` ascending.
Each epoch covers rows from its `seed_start` up to (but not including) the
next epoch's `seed_start`, or indefinitely if it is the last epoch.

```json
[
  { "seed_start": 1,    "seed": "<43-char Base64_32>" },
  { "seed_start": 5001, "seed": "<43-char Base64_32>" }
]
```

The `seed` is the 256-bit secret used to derive per-cell salts for all rows
in that epoch.  It is required to reproduce the input hash of any revealed
row; keep it confidential.

---

### `GET /sql-config` — JDBC configuration (Bearer)

```sh
curl -H "Authorization: Bearer $TOKEN" \
     https://micro.crums.io/alice/sales/sql-config
```

Returns the SQL ledger configuration stored server-side.  All fields except
`by_no_query` and `row_count_query` are optional and omitted when not set.

```json
{
  "by_no_query":       "SELECT item_count, price, txn_date, cust_id FROM sales WHERE row_no = ?",
  "row_count_query":   "SELECT MAX(row_no) FROM sales",
  "jdbc_url":          "jdbc:postgresql://db.example.com:5432/mydb",
  "jdbc_driver_class": "org.postgresql.Driver",
  "timezone":          "America/New_York",
  "default_lag":       5
}
```

| Field | Description |
|---|---|
| `by_no_query` | Parameterised SQL returning at most one row by its 1-based row number (single `?`) |
| `row_count_query` | No-parameter SQL returning the current row count as a single integer |
| `jdbc_url` | JDBC connection URL; overridden by `--jdbc-url` on the CLI |
| `jdbc_driver_class` | Fully-qualified JDBC driver class (optional; usually auto-detected) |
| `timezone` | IANA timezone for hashing `DATE`/`TIMESTAMP` columns (e.g. `America/New_York`). Defaults to UTC when absent. Must match the timezone used when existing chain hashes were built. |
| `default_lag` | Default number of trailing source rows to withhold from each commit, guarding against in-flight transactions whose sequence IDs appear in `MAX()` before the inserting transaction commits. Overridden by `--lag` on the CLI. |

---

### `POST /commit` — append row hashes (Bearer)

Hashes rows locally (via `mchain commit` or `commit.java`) and posts them to
the chain.  The endpoint is idempotent: re-submitting already-committed rows
is accepted as long as the hashes match, which is useful for overlap-based
sanity checks.

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

`start_row` must be ≤ the number of already-committed rows + 1.  Each hash
in `input_hashes` is a 43-character Base64_32 (URL-safe) encoding of the
32-byte SHA-256 input hash for that row.

**Success (HTTP 200):**
```json
{ "row_count": 43 }
```

**Error responses:**

| HTTP | Condition |
|---|---|
| 400 | Malformed JSON |
| 401 | Missing or invalid Bearer token |
| 404 | Chain not found |
| 409 | A submitted hash conflicts with an already-recorded row hash |
| 422 | `start_row` < 1, `input_hashes` empty, or a hash value is malformed |

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

### Native installer (`jpackage`)

`mchain` loads JDBC drivers dynamically at run time (see
[`--jdbc-driver-jar`](#dynamic-jdbc-driver-loading---jdbc-driver-jar) above), so it can't be
compiled ahead-of-time (e.g. GraalVM native-image). Instead, a self-contained
installer bundling a Java runtime is built with `jpackage`, using the
`@file`-style option files under `jpckg/` (shared attribution/icon) and
`microchain-cli/jpckg/base` (module name, description).

`jpackage` can't cross-compile: each installer must be built on its target OS,
from the `microchain-cli` subdirectory after `mvn package`:

```
# Linux — produces a .deb or .rpm, depending on distro
$ jpackage @jpckg/base @../jpckg/attrib @../jpckg/linux

# macOS
$ jpackage @jpckg/base @../jpckg/attrib @../jpckg/mac

# Windows — @..\jpckg\file doesn't work; copy the shared files in first
> copy ..\jpckg\attrib jpckg
> copy ..\jpckg\win jpckg
> copy ..\jpckg\logo.ico jpckg
> jpackage @jpckg\base @jpckg\attrib @jpckg\win
```

The `.github/workflows/release.yml` workflow automates this across all three
OSes (via a build matrix) and attaches the resulting installers to a GitHub
Release whenever a `v*` tag is pushed. It also checks out and installs
`skipledger`, `io-util`, and `merkle-tree` from source first, since those are
still SNAPSHOT dependencies not published anywhere CI can otherwise resolve
them from.
