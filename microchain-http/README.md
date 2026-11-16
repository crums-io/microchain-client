# microchain-http

This module's primary deliverable is the HTTP contract itself, documented
below as the [REST API reference](#rest-api-reference) — every endpoint a
microchain web service exposes, independent of any particular client
implementation. The one piece of actual code here, `ChainClient`, is a
minimal `java.net.http`-based reference implementation of part of that
contract: it lives here, not in `microchain-api`, specifically so that
module stays free of a `java.net.http` dependency. Centralising it here
also lets the CLI and future rich clients share the same networking code
instead of each reimplementing it.

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

## REST API reference

All chain endpoints are namespaced under `/{user}/{chain}/`.  In the examples
below, replace `https://micro.crums.io/bikeshop/orders` with your chain URL and
`$TOKEN` with the Bearer token shown on the chain's manage page.

### Endpoint summary

| Verb | Path | Auth | Description |
|---|---|---|---|
| GET | `/{user}/_chains` | — | List all chains for a user |
| GET | `/{user}/{chain}/info` | — | Chain metadata: ledger type, title, salted |
| GET | `/{user}/{chain}/state` | — | Skipledger commitment path (PathPack) |
| GET | `/{user}/{chain}/salts` | Bearer | Salt epoch seeds (owner only) |
| GET | `/{user}/{chain}/sql-config` | Bearer | JDBC queries, connection URL, timezone, default lag |
| POST | `/{user}/{chain}/commit` | Bearer | Append row hashes; receive updated row count |
| POST | `/{user}/{chain}/verify` | — | Verify submitted source rows against the chain; return an anchored receipt entry |
| POST | `/_verify` | — | Verify every chain entry in a (possibly multi-chain) unanchored receipt in one round trip |
| GET | `/{user}/_receipt_templates.src` | — | Direct source-row read for a server-held `SourceLedger` (today: receipt templates) |
| GET | `/{user}/_receipt_templates.src/count` | — | Row count of that `SourceLedger` |

---

### `GET /{user}/_chains` — list chains (public)

```sh
curl https://micro.crums.io/bikeshop/_chains
```

Returns a JSON array of chain summaries for the user.  Each entry has:
`name`, `title`, `description`, `ledger_type` (1 = SQL ledger),
`has_salt` (boolean), `created_at` (ISO-8601 timestamp).

---

### `GET /info` — chain metadata (public)

```sh
curl https://micro.crums.io/bikeshop/orders/info
```

Response fields:

| Field | Type | Description |
|---|---|---|
| `ledger_type` | int | `1` = SQL-backed ledger |
| `title` | string | Display title (omitted if not set) |
| `description` | string | (omitted if not set) |
| `salted` | boolean | Whether every cell in a row is salted; `false` means no cells are |

There is no per-column salting: a chain's rows are either entirely salted or
entirely unsalted.

---

### `GET /state` — commitment path (public)

Returns a skipledger **PathPack**: a compact hash proof linking one or more
rows to the chain's latest committed state.

```sh
# Default: path from row 1 to the latest committed row
curl "https://micro.crums.io/bikeshop/orders/state"

# Singleton path for the latest row (used by mchain status / commit workflow)
curl "https://micro.crums.io/bikeshop/orders/state?rows=last"

# Path linking rows 5, 11, and the latest; hex-encoded hashes
curl "https://micro.crums.io/bikeshop/orders/state?rows=5,11,last&enc=hex"
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
determined by the skipledger skip structure. The server expands the
requested set to include all required intermediaries automatically.

Examples:

| `rows=` | Expanded stitch rows |
|---|---|
| `last` | `[N]` (singleton) |
| `1,16` | `[1, 2, 4, 8, 16]` |
| `1,last` | `[1, 2, 4, 8, …, N]` (full ladder) |
| `3,31` | `[3, 4, 8, 16, 24, 28, 30, 31]` |

The expansion rules are defined by `SkipLedger.stitch()` / `stitchCompress()`
in the `skipledger-base` module.

#### Why `compress=0` exists

A compressed path scales as *O*(log *N*) in storage (*N* = rows committed);
an uncompressed one as *O*((log *N*)²) — compression works by replacing certain
intermediate row-hashes (*between* the expanded stitch rows) with Merkle
proofs instead. The tradeoff: an uncompressed path has more information about the
intermediate rows. Since paths from the same [skipledger] chain share common data,
it makes sense to archive them together and avoid data duplication. For archives,
compression is often counter-productive.

Pass `compress=0` when you expect to combine this path with others offline
later (the planned `bindle` archiving tool's use case); use the default
otherwise.

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

Paths, and hence path-packs, are sticky: any 2 path-packs whose commitment hashes conflict at
an overlapping row-number do not belong to the same chain; conversely, whenever their commitment hashes
match at some row-number, the 2 paths belong to the same chain *up to* that row number.
Under the right conditions, 2 paths can be independently concatenated. When `compress=0`,
roughly (log *N*)<sup>2</sup> row hashes are present in the path (where *N* is difference
of highest and lowest row-number in the path); when `compress=1`, only roughly log *N*
row hashes are present. So `compress=0` is more sticky than `compress=1`.

#### Error responses

| HTTP | Condition |
|---|---|
| 404 | Chain not found, or chain has no committed rows |
| 422 | `rows` contains an invalid token or a row number < 1 |

---

### `GET /salts` — salt epochs (Bearer)

```sh
curl -H "Authorization: Bearer $TOKEN" \
     https://micro.crums.io/bikeshop/orders/salts
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
     https://micro.crums.io/bikeshop/orders/sql-config
```

Returns the SQL ledger configuration stored server-side.  All fields except
`by_no_query` and `row_count_query` are optional and omitted when not set.

```json
{
  "by_no_query":       "SELECT customer_id, order_date, store_id, staff_id FROM orders WHERE order_id = ?",
  "row_count_query":   "SELECT MAX(order_id) FROM orders",
  "jdbc_url":          "jdbc:sqlite:/data/bikeshop/bikes.db",
  "jdbc_driver_class": "org.sqlite.JDBC",
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
     https://micro.crums.io/bikeshop/orders/commit
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

---

### `POST /verify` — verify submitted rows against the chain (public)

Takes the source rows you already have (possibly with cells redacted — see
[Redacting a receipt](../README.md#redacting-a-receipt)) and asks the chain to
independently recompute their hashes and compare them against its own
committed input hashes at those row numbers. On success, returns a
verified `ChainReceipt`: the same shape `mchain receipt generate`/`anchor`
produce, with a fresh skipledger commitment path attached — the exact
mechanism `mchain receipt anchor` uses client-side, just driven from the
server with the row content as proof instead of trusting the caller's own
source database.

No Bearer token is required — this is a trustless verification: the only
thing the caller learns is whether the row content they already had
matches what's publicly committed, which anyone holding a receipt is
entitled to check.

```sh
curl -X POST \
     -H "Content-Type: application/json" \
     -d '{"rows": [{"row": 42, "cells": [7, "2026-01-01", 3, 9]}]}' \
     "https://micro.crums.io/bikeshop/orders/verify?compress=1"
```

A mismatch at any submitted row, or a row number the chain doesn't have
yet, fails the whole request rather than returning a partial result.

#### Query parameters

| Parameter  | Default | Description |
|------------|---------|-------------|
| `compress` | `1`     | Same tradeoff as `/state`'s `compress` — see [Why `compress=0` exists](#why-compress0-exists) |

**Error responses:**

| HTTP | Condition |
|---|---|
| 400 | `compress` is not `0` or `1` |
| 404 | Chain not found, or doesn't yet have a submitted row number |
| 409 | A submitted row's hash conflicts with the chain's committed input hash |
| 422 | Malformed JSON, missing/empty `rows`, or rows not strictly ascending with no duplicates |

---

### `POST /_verify` — verify a multi-chain receipt (public)

The chain-independent counterpart to `/verify`: since a receipt typically
spans several chains (e.g. `orders`, `order_items`, `customers`), this
endpoint verifies every chain entry in a submitted unanchored `Receipt` —
same JSON shape `mchain receipt generate`/`redact` already produce —
against this server's own chains, in one round trip. Any `state` already
attached to a submitted entry is ignored; a fresh one is always computed.

This exists primarily for a receipt's *recipient* who hasn't installed
`microchain-client` — the website itself verifies (and re-anchors) an
unanchored receipt on their behalf, so they don't need local tooling to
trust it. A `microchain-client` installation doesn't need this endpoint
for its own receipts (the same check runs locally), though `mchain` does
call it anyway, purely to batch a multi-chain verification into one round
trip instead of several per-chain calls — a convenience, not trust
delegation.

Only chains hosted on *this* server can be verified here, resolved from
each entry's own `chain_uri`. A chain hosted elsewhere still has its own
`<chain-url>/verify` to fall back on.

```sh
curl -X POST \
     -H "Content-Type: application/json" \
     -d @order-1037-redacted.json \
     "https://micro.crums.io/_verify?compress=1"
```

As with `/verify`, any single chain entry's hash conflict or unresolvable
`chain_uri` fails the whole request. The same `compress` query parameter
applies here too, uniformly to every chain entry's fresh commitment path.

**Error responses:**

| HTTP | Condition |
|---|---|
| 400 | `compress` is not `0` or `1` |
| 404 | A chain entry's `chain_uri` doesn't resolve to a chain this server hosts |
| 409 | A chain entry's row hash conflicts with that chain's committed input hash |
| 422 | Malformed JSON, no chain entries, or a malformed `chain_uri` |

---

### The `.src` convention — direct `SourceLedger` access (public)

Any `SourceLedger` this server holds server-side can expose its rows
directly through a `.src` sibling resource, alongside whatever other REST
surface that resource already has. The suffix is a dot rather than a path
segment specifically so it can never collide with a literally-named
sub-resource (e.g. a receipt template named `src`) — every name/chain
pattern in this codebase already excludes `.` from its allowed characters,
so this is safe with no extra reservation needed.

Two defaults deliberately differ from `/verify`'s `ChainReceipt`:

- **Unchained.** The response carries no `chain_uri` — this endpoint
  doesn't know or care about chain identity (some `SourceLedger`s may have
  no associated chain at all). The caller already knows the URL it just
  called.
- **Unanchored.** By default you get rows only, no commitment proof;
  anchoring is an explicit opt-in (`anchor=1`), not the default shape.

The response is a plain `SourceReceipt` (rows, and optionally a `state`),
or — for `/count` — a `RowCount`. Both are shared types defined in
`microchain-api` (`io.crums.micro.api.receipt.SourceReceipt`,
`io.crums.micro.api.RowCount`); the latter is also what `/commit` returns.

`_receipt_templates` is the one `SourceLedger` exposed this way today: its
rows are every template version this user has ever published, across all
names, ordered by publish order — row number *is* `chain_row_number` (see
[Receipt-generator templates](../README.md#receipt-generator-templates) for the client-side
generation workflow; the templates resource's own `GET`/`POST
/{user}/_receipt_templates[/{name}]` endpoints aren't documented here yet).

#### `GET /_receipt_templates.src` — direct source-row read

```sh
# Rows 1-3, unanchored
curl "https://micro.crums.io/bikeshop/_receipt_templates.src?rows=1,2,3"

# Same rows, anchored to the chain's current head
curl "https://micro.crums.io/bikeshop/_receipt_templates.src?rows=1,2,3&anchor=1"
```

##### Query parameters

| Parameter  | Default      | Description |
|------------|--------------|-------------|
| `rows`     | *(required)* | Comma-separated row numbers (1-based). No default — every row number must be ≤ the ledger's current size, or the request 404s. |
| `anchor`   | `0`          | `1` attaches a commitment path (`state`) linking the requested rows to the chain's current head — same target-selection convention as `/verify` (requested rows plus the latest committed row). |
| `compress` | `1`          | Same tradeoff as `/state`'s `compress` — see [Why `compress=0` exists](#why-compress0-exists). Only meaningful when `anchor=1`. |

##### Response

```json
{
  "rows": [
    {
      "row": 1,
      "cells": ["order-receipt", {"type": "DATE", "value": 1792316551207}, "{{order.total}} paid by {{customer.name}}"],
      "hash": "<hash>"
    }
  ]
}
```

With `anchor=1`, a `state` field (a PathPack — same shape as `/state`'s
response) is added.

##### Error responses

| HTTP | Condition |
|---|---|
| 404 | User not found, or a requested row number exceeds the ledger's current size |
| 422 | `rows` missing, empty, or containing a non-positive-integer token; `anchor`/`compress` not `0`/`1` |

---

#### `GET /_receipt_templates.src/count` — row count

```sh
curl "https://micro.crums.io/bikeshop/_receipt_templates.src/count"
```

```json
{ "row_count": 17 }
```

| HTTP | Condition |
|---|---|
| 404 | User not found |

---

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
| `commit(token, CommitRequest)` → `RowCount` | `POST /commit` | Bearer | implemented |

The same Bearer access token is used for `/salts`, `/sql-config`, and
`/commit`. A missing or invalid token raises `SecurityException`
(unchecked). A hash conflict on `/commit` raises `HashConflictException`
(HTTP 409).

`getState()` with no arguments sends no query parameters (server defaults to
row 1 + latest, compressed).  The parameterised overload lets callers request
a path through specific row numbers with explicit `plusLatest` and `compress`
flags.

#### JSON parsing notes

- Object-returning endpoints (`/info`, `/sql-config`, `/state`, `/commit`)
  use each API type's `PARSER.toEntity(String)` directly — no intermediate
  `JSONObject` construction needed.
- `/salts` returns a JSON array, read via
  `TableSaltReader.READER.toTableSalt(JSONArray)`, which assembles all epoch
  seeds into a single `TableSalt`.
- The `/state` PathPack uses field names `stitch_rows`, `compression`, `hashes`
  with `enc=b64` (Base64_32), matched by the static `STATE_PARSER`.

### RemoteSkipLedger

Stub — extends `io.crums.sldg.SkipLedger` and will wrap `ChainClient`.

## Dependencies

- `microchain-api` (transitively re-exports `skipledger-base` and `jsonimple`)
- `java.net.http` (JDK built-in)
