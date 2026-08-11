/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.cli;

import java.io.File;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import io.crums.micro.api.ChainInfo;
import io.crums.micro.api.CommitRequest;
import io.crums.micro.api.DbConfig;
import io.crums.micro.api.LocalConfig;
import io.crums.micro.api.SqlConfig;
import io.crums.micro.http.ChainClient;
import io.crums.sldg.HashConflictException;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.salt.EpochedTableSalt.EpochSeed;
import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.Cell;
import io.crums.sldg.src.DataType;
import io.crums.sldg.src.SaltScheme;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.json.EpochSeedParser;
import io.crums.sldg.src.json.SaltSchemeParser;
import io.crums.sldg.src.json.SourceRowParser;
import io.crums.sldg.src.sql.SqlLedger;
import io.crums.util.Base64_32;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonPrinter;
import io.crums.util.json.simple.JSONArray;
import io.crums.util.json.simple.JSONObject;
import io.crums.util.json.simple.parser.JSONParser;
import io.crums.util.json.simple.parser.ParseException;
import picocli.CommandLine;
import static io.crums.micro.api.ApiConstants.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Entry point for the mchain CLI.
 *
 * <pre>
 *   mchain commit  &lt;chain-url&gt; --token &lt;t&gt; [JDBC opts]
 *   mchain status  &lt;chain-url&gt;
 *   mchain info    &lt;chain-url&gt;
 * </pre>
 */
@Command(
    name = "mchain",
    subcommands = {
        Main.CommitCommand.class,
        Main.StatusCommand.class,
        Main.InfoCommand.class,
        Main.RowsCommand.class,
        Main.ConfigCommand.class,
        CommandLine.HelpCommand.class
    },
    mixinStandardHelpOptions = true,
    versionProvider = Main.VersionProvider.class,
    description = "Commit on-prem ledger rows to a microchain.")
public class Main implements Runnable {

  public static void main(String[] args) {
    System.exit(new CommandLine(new Main()).execute(args));
  }

  @Override
  public void run() {
    new CommandLine(new Main()).usage(System.out);
  }


  // ── shared positional argument ───────────────────────────────────────────────

  static class ChainOptions {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<chain-url>",
        description = {
            "Full chain URL: <server>/{user}/{chain}",
            "  e.g. https://micro.crums.io/alice/sales",
            "May be omitted when --local-config supplies a 'chain_url' field."
        })
    URI chainUrl;

    // Subcommands handle --help themselves; -V belongs only on the root command.
    @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
    boolean helpRequested;
  }


  // ── commit ───────────────────────────────────────────────────────────────────

  @Command(
      name = "commit",
      description = {
          "Hash new rows from the local database and commit them to the chain.",
          "",
          "Workflow:",
          "  1. GET /info        → salt scheme",
          "  2. GET /salts       → table salt (token required)",
          "  3. GET /sql-config  → JDBC queries",
          "  4. GET /state       → current committed row count N",
          "  5. Open local SqlLedger, hash rows N..M  (see --from / --to / --lag)",
          "  6. POST /commit     → updated row count"
      })
  static class CommitCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>",
        description = {
            "JSON file supplying local config: access_token, sql-config fields,",
            "and/or salts array. Fields present here are used instead of fetching",
            "from the server. CLI flags (--token, --jdbc-url, etc.) take precedence.",
            "Recommended for sensitive values (token, salt seeds) to keep them out",
            "of shell history."
        })
    File localConfigFile;

    @Option(names = {"--token", "-t"},
        description = {
            "Bearer access token.",
            "May be omitted when --local-config provides an 'access_token' field."
        })
    String token;

    @Option(names = {"--jdbc-url"},
        description = "JDBC URL (overrides server sql-config and --local-config value)")
    String jdbcUrlOverride;

    @Option(names = {"--jdbc-user"},
        description = "JDBC username")
    String jdbcUser;

    @Option(names = {"--jdbc-pass"},
        description = "JDBC password (omit value to be prompted interactively)",
        interactive = true, echo = false, arity = "0..1")
    char[] jdbcPass;

    @Option(names = {"--jdbc-driver-jar"}, paramLabel = "<jar>",
        description = {
            "Path to the JDBC driver JAR to load dynamically.",
            "Relative paths are resolved against the --local-config file's directory."
        })
    File jdbcDriverJarOverride;

    @Option(names = {"--batch", "-b"},
        description = "Max rows per commit request (default: ${DEFAULT-VALUE})",
        defaultValue = "1000")
    int batch;

    @Option(names = {"--timezone", "--tz"},
        description = {
            "JVM timezone for hashing DATE/TIMESTAMP columns.",
            "Overrides the server-configured timezone from sql-config.",
            "Defaults to the server-configured timezone, or UTC if not set.",
            "Must match the timezone used when existing chain hashes were built."
        })
    String timezoneOverride;

    @Option(names = {"--from"},
        description = {
            "First row to hash and submit.",
            "Defaults to the current chain length (re-submits the last committed",
            "row as a minimal overlap/sanity check before appending new rows).",
            "Set lower to widen the overlap, or to 1 for a full re-hash."
        })
    Long fromOverride;

    @Option(names = {"--to"},
        description = {
            "Last row to include in this commit (inclusive).",
            "Defaults to the total row count reported by the source ledger.",
            "Use to exclude rows whose source transactions may not yet have",
            "committed, since sequence-allocated IDs can appear in MAX() before",
            "the inserting transaction completes.",
            "Mutually exclusive with --lag."
        })
    Long toOverride;

    @Option(names = {"--lag"},
        description = {
            "Number of trailing rows to withhold from this commit.",
            "Equivalent to --to (source_total - N). Convenient for scheduled",
            "scripts where a fixed buffer guards against in-flight transactions",
            "whose IDs have been allocated but not yet committed.",
            "Mutually exclusive with --to."
        })
    Long lagOverride;

    @Override
    public Integer call() throws Exception {
      // ── option validation (before any I/O) ───────────────────────────────────
      if (toOverride != null && lagOverride != null) {
        System.err.println("ERROR: --to and --lag are mutually exclusive");
        return 1;
      }
      if (fromOverride != null && fromOverride < 1) {
        System.err.println("ERROR: --from must be >= 1");
        return 1;
      }
      if (toOverride != null && toOverride < 1) {
        System.err.println("ERROR: --to must be >= 1");
        return 1;
      }
      if (lagOverride != null && lagOverride < 0) {
        System.err.println("ERROR: --lag must be >= 0");
        return 1;
      }
      if (fromOverride != null && toOverride != null && fromOverride > toOverride) {
        System.err.println("ERROR: --from (" + fromOverride + ") must be <= --to (" + toOverride + ")");
        return 1;
      }

      // ── load local config (if provided) ──────────────────────────────────────
      LocalConfig localCfg = LocalConfig.EMPTY;
      if (localConfigFile != null) {
        try {
          localCfg = LocalConfig.PARSER.toEntity(localConfigFile);
        } catch (JsonParsingException | UncheckedIOException e) {
          System.err.println("ERROR: cannot read --local-config '" + localConfigFile + "': " + e.getMessage());
          return 1;
        }
      }

      // CLI chain-url wins; fall back to local config
      URI effectiveUrl = resolveChainUrl(opts, localConfigFile);
      if (effectiveUrl == null) {
        System.err.println("ERROR: chain URL required; pass as argument or add 'chain_url' to --local-config");
        return 1;
      }

      // CLI --token wins; fall back to file's access_token
      String effectiveToken = token != null ? token : localCfg.accessToken().orElse(null);
      if (effectiveToken == null) {
        System.err.println("ERROR: bearer token required; use --token <t> or add 'access_token' to --local-config file");
        return 1;
      }

      // ── network + DB ─────────────────────────────────────────────────────────
      ChainClient client = newClient(effectiveUrl);

      ChainInfo info = client.getInfo().orElseThrow(() ->
          new IllegalArgumentException("Chain not found: " + effectiveUrl));

      // salt: local config wins over server call
      TableSalt salt = null;
      if (info.saltScheme().hasSalt()) {
        var localSalt = localCfg.tableSalt();
        salt = localSalt.isPresent() ? localSalt.get() : client.getSalts(effectiveToken);
      }

      // sql-config: local config wins over server call; CLI flags applied on top
      SqlConfig sqlCfg = localCfg.sqlConfig().isPresent()
          ? localCfg.sqlConfig().get()
          : client.getSqlConfig(effectiveToken);

      long committed = client.getState()
          .map(p -> p.hiRowNumber())
          .orElse(0L);

      // resolve + apply timezone: CLI > local-config/server > "UTC"
      String effectiveTimezone = timezoneOverride != null ? timezoneOverride
          : sqlCfg.timezone().orElse("UTC");
      try {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(effectiveTimezone)));
      } catch (java.time.zone.ZoneRulesException e) {
        System.err.println("ERROR: unknown timezone '" + effectiveTimezone + "'");
        return 1;
      }

      SqlConfig effectiveCfg = sqlCfg.withDbConfig(
          effectiveDbConfig(sqlCfg.dbConfig(), localCfg, jdbcUrlOverride, jdbcDriverJarOverride));

      Properties props = cliJdbcProps(jdbcUser, jdbcPass);

      try (Connection conn = effectiveCfg.openConnection(props.isEmpty() ? null : props);
           SqlLedger ledger = salt != null
               ? effectiveCfg.open(conn, info.saltScheme(), salt)
               : effectiveCfg.open(conn, info.saltScheme())) {

        long total = ledger.size();
        long ceiling;
        String ceilNote;
        if (toOverride != null) {
          ceiling  = Math.min(toOverride, total);
          ceilNote = "  --to: " + ceiling;
        } else if (lagOverride != null) {
          ceiling  = Math.max(0, total - lagOverride);
          ceilNote = "  lag: " + lagOverride + "  (effective --to: " + ceiling + ")";
        } else if (sqlCfg.defaultLag().isPresent()) {
          long serverLag = sqlCfg.defaultLag().get();
          ceiling  = Math.max(0, total - serverLag);
          ceilNote = "  lag: " + serverLag + "  (server default, effective --to: " + ceiling + ")";
        } else {
          ceiling  = total;
          ceilNote = "";
        }

        long from = fromOverride != null ? fromOverride
            : (committed > 0 ? committed : 1);

        if (from > ceiling) {
          System.out.println("Nothing to commit.  " +
              "Chain: " + committed + " row(s)  Source: " + total + " row(s)" + ceilNote);
          return 0;
        }

        System.out.println("Chain committed: " + committed +
            "  Source rows: " + total +
            "  Submitting from row: " + from + " to row: " + ceiling + ceilNote);
        while (from <= ceiling) {
          long to = Math.min(from + batch - 1, ceiling);
          var hashes = new ArrayList<String>((int) (to - from + 1));
          for (long rn = from; rn <= to; rn++) {
            hashes.add(HashEncoding.BASE64_32.encode(
                ledger.getSourceRow(rn).hash()));
          }
          var resp = client.commit(effectiveToken, new CommitRequest(from, hashes));
          long replayed = Math.max(0, Math.min(to, committed) - from + 1);
          long added    = (to - from + 1) - replayed;
          System.out.println("  Committed rows " + from + "–" + to +
              "  (chain total: " + resp.rowCount() + ")" +
              describeReplayedAdded(replayed, added));
          from = to + 1;
        }
      } catch (HashConflictException e) {
        System.err.println("ERROR: hash conflict at " + effectiveUrl);
        System.err.println("  " + e.getMessage());
        System.err.println("One or more submitted input hashes do not match what is already on the chain.");
        return 2;
      } catch (SecurityException e) {
        System.err.println("ERROR: " + e.getMessage());
        return 1;
      }
      return 0;
    }
  }

  /**
   * Describes a commit batch's split between rows that were already on the
   * chain (resubmitted idempotently, e.g. the default one-row overlap check)
   * and rows newly appended.
   */
  private static String describeReplayedAdded(long replayed, long added) {
    if (replayed == 0)
      return "  (" + added + " new)";
    if (added == 0)
      return "  (" + replayed + " replayed)";
    return "  (" + replayed + " replayed, " + added + " new)";
  }


  // ── status ───────────────────────────────────────────────────────────────────

  @Command(
      name = "status",
      description = {
          "Print the current committed row count (GET /state, public).",
          "",
          "When a row-count query is available — via --row-count-query, a",
          "'row_count_query' field in --local-config, or the server's sql-config",
          "(fetched with a bearer token when not available locally) — also connects",
          "to the local database to report the source ledger's row count and how",
          "many rows are available to commit, factoring in 'default_lag' when configured."
      })
  static class StatusCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>",
        description = {
            "JSON file supplying local config; 'chain_url' field may replace the",
            "<chain-url> argument.  When it includes sql-config fields (row_count_query,",
            "jdbc_url, default_lag, etc.), those are used to report rows available to commit."
        })
    File localConfigFile;

    @Option(names = {"--token", "-t"},
        description = {
            "Bearer access token.  Used to fetch the row-count query (and JDBC details,",
            "if not supplied otherwise) from the server's sql-config when not available",
            "via --row-count-query or --local-config.",
            "May be omitted when --local-config supplies an 'access_token' field."
        })
    String token;

    @Option(names = {"--row-count-query"},
        description = "SQL query returning the current source row count (overrides --local-config and the server).")
    String rowCountQueryOverride;

    @Option(names = {"--jdbc-url"},
        description = "JDBC URL (overrides --local-config value)")
    String jdbcUrlOverride;

    @Option(names = {"--jdbc-user"},
        description = "JDBC username")
    String jdbcUser;

    @Option(names = {"--jdbc-pass"},
        description = "JDBC password (omit value to be prompted interactively)",
        interactive = true, echo = false, arity = "0..1")
    char[] jdbcPass;

    @Option(names = {"--jdbc-driver-jar"}, paramLabel = "<jar>",
        description = {
            "Path to the JDBC driver JAR to load dynamically.",
            "Relative paths are resolved against the --local-config file's directory."
        })
    File jdbcDriverJarOverride;

    @Override
    public Integer call() throws Exception {
      URI effectiveUrl = resolveChainUrl(opts, localConfigFile);
      if (effectiveUrl == null) {
        System.err.println("ERROR: chain URL is required; pass as argument or add 'chain_url' to --local-config");
        return 1;
      }
      ChainClient client = newClient(effectiveUrl);
      var state = client.getState();
      long committed = state.map(p -> p.hiRowNumber()).orElse(0L);
      if (state.isEmpty()) {
        System.out.println(effectiveUrl + "  No committed rows.");
      } else {
        System.out.println(effectiveUrl + "  Committed rows: " + committed);
      }

      // ── source row count / available-to-commit (best effort) ────────────────
      LocalConfig localCfg = LocalConfig.EMPTY;
      if (localConfigFile != null) {
        try {
          localCfg = LocalConfig.PARSER.toEntity(localConfigFile);
        } catch (JsonParsingException | UncheckedIOException e) {
          System.err.println("ERROR: cannot read --local-config '" + localConfigFile + "': " + e.getMessage());
          return 1;
        }
      }

      String effectiveToken = token != null ? token : localCfg.accessToken().orElse(null);

      String rowCountQuery = rowCountQueryOverride != null
          ? rowCountQueryOverride
          : localCfg.sqlConfig().map(SqlConfig::rowCountQuery).orElse(null);

      Optional<Long>     lagFromSqlCfg = localCfg.sqlConfig().flatMap(SqlConfig::defaultLag);
      Optional<DbConfig> serverDb      = Optional.empty();

      if (rowCountQuery == null && effectiveToken != null) {
        // Queries are defined server-side; fetch sql-config (best effort).
        try {
          SqlConfig serverCfg = client.getSqlConfig(effectiveToken);
          rowCountQuery = serverCfg.rowCountQuery();
          serverDb = serverCfg.dbConfig();
          if (lagFromSqlCfg.isEmpty())
            lagFromSqlCfg = serverCfg.defaultLag();
        } catch (Exception e) {
          System.err.println("WARN: could not fetch sql-config from server: " + e.getMessage());
        }
      }

      if (rowCountQuery == null)
        return 0;

      DbConfig effectiveDb = effectiveDbConfig(serverDb, localCfg, jdbcUrlOverride, jdbcDriverJarOverride);

      if (effectiveDb.jdbcUrl().isEmpty())
        return 0;

      Properties props = cliJdbcProps(jdbcUser, jdbcPass);

      long sourceRows;
      try (Connection conn = effectiveDb.openConnection(props.isEmpty() ? null : props);
           var stmt = conn.createStatement();
           var rs   = stmt.executeQuery(rowCountQuery)) {
        sourceRows = rs.next() ? rs.getLong(1) : 0L;
      } catch (SQLException e) {
        System.err.println("WARN: could not determine source row count: " + e.getMessage());
        return 0;
      }

      long lag = lagFromSqlCfg.orElse(0L);
      long available = Math.max(0L, sourceRows - committed - lag);

      System.out.println("Source rows    : " + sourceRows);
      System.out.println("Available rows : " + available +
          (lag > 0 ? " (after default lag of " + lag + ")" : ""));
      return 0;
    }
  }


  // ── info ─────────────────────────────────────────────────────────────────────

  @Command(
      name = "info",
      description = "Print chain metadata (GET /info, public).")
  static class InfoCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>",
        description = "JSON file supplying local config; 'chain_url' field may replace the <chain-url> argument.")
    File localConfigFile;

    @Override
    public Integer call() throws Exception {
      URI effectiveUrl = resolveChainUrl(opts, localConfigFile);
      if (effectiveUrl == null) {
        System.err.println("ERROR: chain URL is required; pass as argument or add 'chain_url' to --local-config");
        return 1;
      }
      ChainClient client = newClient(effectiveUrl);
      Optional<ChainInfo> maybeInfo = client.getInfo();
      if (maybeInfo.isEmpty()) {
        System.err.println("Chain not found: " + effectiveUrl);
        return 1;
      }
      ChainInfo info = maybeInfo.get();
      System.out.println("Chain      : " + effectiveUrl);
      System.out.println("Type       : " +
          (info.ledgerType() == 1 ? "SQL ledger" : "log-file ledger"));
      info.title().ifPresent(t       -> System.out.println("Title      : " + t));
      info.description().ifPresent(d -> System.out.println("Description: " + d));
      System.out.println("Salt       : " + describeSaltScheme(info.saltScheme()));
      return 0;
    }
  }

  /** Human-readable summary of a {@link SaltScheme} for the {@code info} command. */
  private static String describeSaltScheme(SaltScheme scheme) {
    if (!scheme.hasSalt())
      return "no";
    if (scheme.saltAll())
      return "yes (all columns)";
    String cols = Arrays.stream(scheme.cellIndices())
        .mapToObj(String::valueOf)
        .collect(Collectors.joining(", "));
    return scheme.isPositive()
        ? "yes (only columns " + cols + " (zero-based))"
        : "yes (all columns except " + cols + " (zero-based))";
  }


  // ── rows ─────────────────────────────────────────────────────────────────────

  @Command(
      name = "rows",
      description = {
          "Output source rows from the local SQL ledger.",
          "",
          "Fetches chain info (public), then sql-config and salt from the server",
          "(requires token) or from --local-config if supplied there.  Queries the",
          "local database by row number (--row) or by an arbitrary WHERE clause",
          "(--where, rewriting the by-no-query).",
          "",
          "Output is JSON (default) or pretty-printed one-cell-per-line (--pretty)."
      })
  static class RowsCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>",
        description = {
            "JSON file supplying local config: access_token, sql-config fields,",
            "and/or salts array.  Fields present here are used instead of fetching",
            "from the server.  CLI flags (--token, --jdbc-url, etc.) take precedence."
        })
    File localConfigFile;

    @Option(names = {"--token", "-t"},
        description = {
            "Bearer access token.",
            "May be omitted when --local-config supplies both sql-config and salt."
        })
    String token;

    @Option(names = {"--jdbc-url"},
        description = "JDBC URL (overrides server sql-config and --local-config value)")
    String jdbcUrlOverride;

    @Option(names = {"--jdbc-user"},
        description = "JDBC username")
    String jdbcUser;

    @Option(names = {"--jdbc-pass"},
        description = "JDBC password (omit value to be prompted interactively)",
        interactive = true, echo = false, arity = "0..1")
    char[] jdbcPass;

    @Option(names = {"--jdbc-driver-jar"}, paramLabel = "<jar>",
        description = {
            "Path to the JDBC driver JAR to load dynamically.",
            "Relative paths are resolved against the --local-config file's directory."
        })
    File jdbcDriverJarOverride;

    @Option(names = {"--row", "-r"}, paramLabel = "<N>",
        description = {
            "1-based row number to fetch.",
            "Mutually exclusive with --where."
        })
    Long rowNo;

    @Option(names = {"--where", "-w"}, paramLabel = "<clause>",
        description = {
            "SQL WHERE predicate (the WHERE keyword is optional).",
            "Example: --where \"customer_id = 42\"",
            "The by-no-query is rewritten to SELECT pk, ... WHERE <clause>.",
            "Mutually exclusive with --row and --count."
        })
    String whereClause;

    @Option(names = {"--count", "-n"},
        description = {
            "Print the total ledger row count (runs the row-count query).",
            "Mutually exclusive with --row and --where."
        })
    boolean count;

    @Option(names = {"--pretty", "-p"},
        description = "Pretty-print output (one cell per line with type info) instead of JSON.")
    boolean pretty;

    @Override
    public Integer call() throws Exception {
      // ── option validation ──────────────────────────────────────────────────
      int modes = (rowNo != null ? 1 : 0) + (whereClause != null ? 1 : 0) + (count ? 1 : 0);
      if (modes == 0) {
        System.err.println("ERROR: one of --row, --where, or --count is required");
        return 1;
      }
      if (modes > 1) {
        System.err.println("ERROR: --row, --where, and --count are mutually exclusive");
        return 1;
      }
      if (rowNo != null && rowNo < 1) {
        System.err.println("ERROR: --row must be >= 1");
        return 1;
      }

      // ── load local config ──────────────────────────────────────────────────
      LocalConfig localCfg = LocalConfig.EMPTY;
      if (localConfigFile != null) {
        try {
          localCfg = LocalConfig.PARSER.toEntity(localConfigFile);
        } catch (JsonParsingException | UncheckedIOException e) {
          System.err.println("ERROR: cannot read --local-config '" + localConfigFile + "': " + e.getMessage());
          return 1;
        }
      }

      String effectiveToken = token != null ? token : localCfg.accessToken().orElse(null);

      // CLI chain-url wins; fall back to local config
      URI effectiveUrl = resolveChainUrl(opts, localConfigFile);

      // ── chain info (public) ────────────────────────────────────────────────
      // We need ChainInfo for the SaltScheme.  We can skip the server call when
      // the local config has sql-config and either: (a) no salt epochs (→ NO_SALT),
      // or (b) salt epochs plus an explicit salt_scheme.
      ChainClient client = effectiveUrl != null ? newClient(effectiveUrl) : null;
      ChainInfo info;
      if (client != null) {
        info = client.getInfo().orElseThrow(() ->
            new IllegalArgumentException("Chain not found: " + effectiveUrl));
      } else if (localCfg.sqlConfig().isPresent() &&
                 (localCfg.saltEpochs().isEmpty() || localCfg.saltScheme().isPresent())) {
        // local-only dry-run: unsalted, or salted with scheme supplied locally
        SaltScheme scheme = localCfg.saltScheme().orElse(SaltScheme.NO_SALT);
        info = new ChainInfo(1, Optional.empty(), Optional.empty(), scheme);
      } else if (localCfg.sqlConfig().isPresent()) {
        System.err.println("ERROR: 'salt_scheme' is required in --local-config " +
            "when 'salts' are present and no chain URL is provided");
        return 1;
      } else {
        System.err.println("ERROR: chain URL required; pass as argument or add 'chain_url' to --local-config");
        return 1;
      }

      // ── salt ───────────────────────────────────────────────────────────────
      TableSalt salt = null;
      if (info.saltScheme().hasSalt()) {
        var localSalt = localCfg.tableSalt();
        if (localSalt.isPresent()) {
          salt = localSalt.get();
        } else {
          if (effectiveToken == null) {
            System.err.println("ERROR: bearer token required to fetch salts; " +
                "use --token or add 'access_token' to --local-config");
            return 1;
          }
          salt = client.getSalts(effectiveToken);
        }
      }

      // ── sql-config ─────────────────────────────────────────────────────────
      SqlConfig sqlCfg;
      if (localCfg.sqlConfig().isPresent()) {
        sqlCfg = localCfg.sqlConfig().get();
      } else {
        if (effectiveToken == null) {
          System.err.println("ERROR: bearer token required to fetch sql-config; " +
              "use --token or add sql-config fields to --local-config");
          return 1;
        }
        sqlCfg = client.getSqlConfig(effectiveToken);
      }

      // ── timezone ───────────────────────────────────────────────────────────
      String effectiveTz = sqlCfg.timezone().orElse("UTC");
      try {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(effectiveTz)));
      } catch (java.time.zone.ZoneRulesException e) {
        System.err.println("ERROR: unknown timezone '" + effectiveTz + "'");
        return 1;
      }

      SqlConfig effectiveCfg = sqlCfg.withDbConfig(
          effectiveDbConfig(sqlCfg.dbConfig(), localCfg, jdbcUrlOverride, jdbcDriverJarOverride));

      Properties props = cliJdbcProps(jdbcUser, jdbcPass);

      // ── count shortcut (no chain-info or salt needed) ──────────────────────
      if (count) {
        try (var conn = effectiveCfg.openConnection(props.isEmpty() ? null : props);
             var stmt = conn.createStatement();
             var rs   = stmt.executeQuery(effectiveCfg.rowCountQuery())) {
          System.out.println(rs.next() ? rs.getLong(1) : 0L);
        }
        return 0;
      }

      // ── query ──────────────────────────────────────────────────────────────
      List<SourceRow> rows = List.of();
      try (Connection conn = effectiveCfg.openConnection(props.isEmpty() ? null : props)) {
        if (rowNo != null) {
          try (SqlLedger ledger = salt != null
              ? effectiveCfg.open(conn, info.saltScheme(), salt)
              : effectiveCfg.open(conn, info.saltScheme())) {
            rows = List.of(ledger.getSourceRow(rowNo));
          }
        } else {
          try (DisplaySqlLedger ledger = DisplaySqlLedger.open(
              conn, effectiveCfg.rowCountQuery(), effectiveCfg.byNoQuery(),
              info.saltScheme(), salt)) {
            rows = ledger.query(whereClause);
          }
        }
      } catch (IndexOutOfBoundsException e) {
        System.err.println("ERROR: " + e.getMessage());
        return 1;
      } catch (IllegalArgumentException e) {
        System.err.println("ERROR: " + e.getMessage());
        return 1;
      }

      if (rows.isEmpty()) {
        System.out.println("(no matching rows)");
      } else {
        for (SourceRow row : rows)
          printRow(row, pretty);
      }
      return 0;
    }

    private static void printRow(SourceRow row, boolean pretty) {
      if (pretty) {
        System.out.println("Row " + row.no() +
            "  (hash: " + HashEncoding.BASE64_32.encode(row.hash()) + ")");
        var cells = row.cells();
        var types = row.cellTypes();
        if (cells.isEmpty()) {
          System.out.println("  (null row)");
        } else {
          for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            DataType t = types.get(i);
            String val = cell.isRedacted()
                ? "REDACTED"
                : formatCellValue(cell.value(), t);
            System.out.printf("  [%d] %-30s  (%s)%n", i + 1, val, t);
          }
        }
      } else {
        JSONObject jObj = SourceRowParser.INSTANCE.toJsonObject(row);
        jObj.put("hash", HashEncoding.BASE64_32.encode(row.hash()));
        JsonPrinter.println(jObj);
      }
    }

    private static String formatCellValue(Object val, DataType type) {
      if (val == null) return "NULL";
      return switch (type) {
        case STRING -> "\"" + val + "\"";
        default     -> String.valueOf(val);
      };
    }
  }


  // ── config ───────────────────────────────────────────────────────────────────

  @Command(
      name = "config",
      description = {
          "Create or update a local config file.",
          "",
          "The 'set' subcommand writes one or more settings into <file>.",
          "Run 'mchain config set --help' to see all available settings."
      },
      subcommands = { Main.ConfigCommand.SetCommand.class, CommandLine.HelpCommand.class })
  static class ConfigCommand implements Runnable {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<file>",
        description = "Path to the local config JSON file to create or update.")
    File configFile;

    @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
    boolean helpRequested;

    @Override
    public void run() {
      new CommandLine(this).usage(System.out);
    }


    @Command(
        name = "set",
        customSynopsis = { "mchain config @|yellow <file>|@ set [OPTIONS]" },
        description = {
            "Set one or more configuration values in @|yellow <file>|@, creating it if necessary.",
            "",
            "Options not supplied are left unchanged.  Invoke multiple times to build",
            "the file incrementally.  A typical dry-run setup:",
            "",
            "  mchain config cfg.json set --jdbc-url jdbc:postgresql://db:5432/mydb",
            "  mchain config cfg.json set --by-no-query  \"SELECT ... WHERE row_no = ?\"",
            "  mchain config cfg.json set --row-count-query \"SELECT MAX(row_no) FROM ...\"",
            "  mchain config cfg.json set --salt-seed   # auto-generates a secure seed",
            "  mchain commit https://micro.crums.io/alice_7/sales --local-config cfg.json",
            ""
        })
    static class SetCommand implements Callable<Integer> {

      @CommandLine.ParentCommand
      ConfigCommand parent;

      @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
      boolean helpRequested;

      @Option(names = {"--chain-url"}, paramLabel = "<chain-url>",
          description = "Full chain URL (<server>/{user}/{chain}); lets the <chain-url> " +
              "CLI argument be omitted on later invocations.")
      String chainUrl;

      @Option(names = {"--token", "-t"},
          description = "Bearer access token.")
      String token;

      @Option(names = {"--jdbc-url"},
          description = "JDBC connection URL.")
      String jdbcUrl;

      @Option(names = {"--jdbc-driver-class"},
          description = "Fully-qualified JDBC driver class (usually auto-detected).")
      String jdbcDriverClass;

      @Option(names = {"--jdbc-driver-jar"}, paramLabel = "<jar>",
          description = {
              "Path to the JDBC driver JAR to load dynamically.",
              "Stored as-is (relative paths are preserved relative to this config file)."
          })
      File jdbcDriverJar;

      @Option(names = {"--jdbc-props"}, paramLabel = "key=value",
          description = {
              "Extra JDBC connection properties (e.g. user=alice password=s3cr3t ssl=true).",
              "Repeat the option to set multiple properties.",
              "CLI flags --jdbc-user / --jdbc-pass override these when both are supplied."
          }, split = ",")
      Map<String, String> jdbcProps;

      @Option(names = {"--by-no-query"},
          description = "SQL query returning one row by its 1-based row number (single '?' parameter).")
      String byNoQuery;

      @Option(names = {"--row-count-query"},
          description = "SQL query returning the current row count.")
      String rowCountQuery;

      @Option(names = {"--timezone", "--tz"},
          description = "IANA timezone for hashing DATE/TIMESTAMP columns (e.g. America/New_York).")
      String timezone;

      @Option(names = {"--default-lag"},
          description = "Default trailing rows to withhold per commit.")
      Long defaultLag;

      @Option(names = {"--salt-seed"},
          arity = "0..1", fallbackValue = "",
          description = {
              "32-byte salt seed, Base64_32-encoded (43 chars).",
              "Omit the value to auto-generate a seed with SecureRandom.",
              "Sets (or replaces) the single salt epoch starting at row 1."
          })
      String saltSeed;

      @Option(names = {"--salt-scheme"}, paramLabel = "<scheme>",
          description = {
              "Cell salting scheme.  Format: <base>[:<except-indices>]",
              "  all        every cell is salted",
              "  none       no cells are salted",
              "  all:0,2    all salted except columns 0 and 2",
              "  none:1,3   only columns 1 and 3 are salted",
              "Column indices are 0-based.  Stored as 'salt_scheme' in the",
              "config file; required for offline 'mchain rows' when 'salts'",
              "are also present."
          })
      String saltSchemeStr;

      @Override
      public Integer call() throws Exception {
        if (parent.configFile == null) {
          System.err.println("ERROR: <file> is required");
          return 1;
        }
        Path path = parent.configFile.toPath();

        JSONObject jObj;
        if (Files.exists(path)) {
          try {
            jObj = (JSONObject) new JSONParser(true).parse(Files.readString(path));
          } catch (ParseException e) {
            System.err.println("ERROR: cannot parse config file: " + path);
            System.err.println("  " + e.getMessage());
            return 1;
          }
        } else {
          jObj = new JSONObject();
        }

        if (chainUrl != null) {
          if (jObj.containsKey(CHAIN_URL_KEY)) {
            jObj.put(CHAIN_URL_KEY, chainUrl);
          } else {
            // New chain_url: lead with it so a human can see at a glance
            // which chain the file belongs to.
            JSONObject reordered = new JSONObject();
            reordered.put(CHAIN_URL_KEY, chainUrl);
            reordered.putAll(jObj);
            jObj = reordered;
          }
        }
        if (token           != null) jObj.put(ACCESS_TOKEN_KEY,       token);
        if (jdbcUrl         != null) jObj.put(JDBC_URL_KEY,           jdbcUrl);
        if (jdbcDriverClass != null) jObj.put(JDBC_DRIVER_CLASS_KEY,  jdbcDriverClass);
        if (jdbcDriverJar   != null) jObj.put(JDBC_DRIVER_JAR_KEY,    jdbcDriverJar.toString());
        if (jdbcProps       != null) {
          JSONObject propsObj = new JSONObject();
          jdbcProps.forEach(propsObj::put);
          jObj.put(JDBC_PROPS_KEY, propsObj);
        }
        if (byNoQuery       != null) jObj.put(BY_NO_QUERY_KEY,        byNoQuery);
        if (rowCountQuery   != null) jObj.put(ROW_COUNT_QUERY_KEY,    rowCountQuery);
        if (timezone        != null) jObj.put(TIMEZONE_KEY,           timezone);
        if (defaultLag      != null) jObj.put(DEFAULT_LAG_KEY,        defaultLag);

        if (saltSeed != null) {
          byte[] seedBytes;
          if (saltSeed.isEmpty()) {
            seedBytes = new byte[32];
            new SecureRandom().nextBytes(seedBytes);
            System.out.println("Generated salt seed: " + Base64_32.encode(seedBytes));
          } else {
            if (saltSeed.length() != 43) {
              System.err.println("ERROR: --salt-seed: expected 43 Base64_32 chars, got " + saltSeed.length());
              return 1;
            }
            try {
              seedBytes = Base64_32.decode(saltSeed);
            } catch (Exception e) {
              System.err.println("ERROR: --salt-seed: invalid Base64_32 value");
              return 1;
            }
          }
          JSONArray saltsArr = new JSONArray();
          saltsArr.add(EpochSeedParser.PARSER.injectEntity(new EpochSeed(1L, seedBytes), new JSONObject()));
          jObj.put(SALTS_KEY, saltsArr);
        }

        if (saltSchemeStr != null) {
          SaltScheme scheme;
          try {
            scheme = parseSaltScheme(saltSchemeStr);
          } catch (IllegalArgumentException e) {
            System.err.println("ERROR: --salt-scheme: " + e.getMessage());
            return 1;
          }
          jObj.put(SALT_SCHEME_KEY, SaltSchemeParser.PARSER.toJsonObject(scheme));
        }

        Files.writeString(path, JsonPrinter.toJson(jObj));
        System.out.println("Updated: " + path);
        return 0;
      }
    }
  }


  // ── helpers ──────────────────────────────────────────────────────────────────

  /**
   * Parses a {@code --salt-scheme} value of the form {@code <base>[:<except-indices>]}
   * where base is {@code all} or {@code none} and the optional colon-separated suffix
   * is a comma-separated list of 0-based column indices that are excepted from the base.
   */
  private static SaltScheme parseSaltScheme(String s) {
    s = s.strip();
    int colonIdx = s.indexOf(':');
    String base;
    int[] exceptions;
    if (colonIdx < 0) {
      base = s;
      exceptions = new int[0];
    } else {
      base = s.substring(0, colonIdx).strip();
      String excPart = s.substring(colonIdx + 1).strip();
      String[] parts = excPart.split(",");
      exceptions = new int[parts.length];
      for (int i = 0; i < parts.length; i++) {
        try {
          exceptions[i] = Integer.parseInt(parts[i].strip());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              "invalid cell index '" + parts[i].strip() + "'");
        }
      }
    }
    return switch (base.toLowerCase()) {
      case "all"  -> SaltScheme.of(exceptions, false); // all except listed
      case "none" -> SaltScheme.of(exceptions, true);  // only listed are salted
      default     -> throw new IllegalArgumentException(
          "expected 'all' or 'none', got '" + base + "'");
    };
  }

  /** CLI arg wins; falls back to {@code chain_url} in the local config file (if any). */
  private static URI resolveChainUrl(ChainOptions opts, File localConfigFile) {
    if (opts.chainUrl != null)
      return opts.chainUrl;
    if (localConfigFile != null) {
      try {
        return LocalConfig.PARSER.toEntity(localConfigFile)
            .chainUrl().map(URI::create).orElse(null);
      } catch (Exception e) {
        // caller will handle null and report an appropriate error
      }
    }
    return null;
  }

  private static ChainClient newClient(URI chainUrl) {
    URI base = URI.create(chainUrl.getScheme() + "://" + chainUrl.getAuthority());
    String[] parts = chainUrl.getPath().split("/");
    int n = parts.length;
    if (n < 2 || parts[n-1].isEmpty() || parts[n-2].isEmpty())
      throw new IllegalArgumentException(
          "chain-url must end with /{user}/{chain}: " + chainUrl);
    return new ChainClient(base, parts[n-2], parts[n-1], HttpClient.newHttpClient());
  }

  /** Builds a {@link DbConfig} from the {@code --jdbc-url} / {@code --jdbc-driver-jar} CLI flags. */
  private static DbConfig cliDbConfig(String jdbcUrlOverride, File jdbcDriverJarOverride) {
    DbConfig cliDb = DbConfig.EMPTY;
    if (jdbcUrlOverride != null)       cliDb = cliDb.withJdbcUrl(jdbcUrlOverride);
    if (jdbcDriverJarOverride != null) cliDb = cliDb.withJdbcDriverJar(jdbcDriverJarOverride.toPath().toAbsolutePath());
    return cliDb;
  }

  /** Builds JDBC connection {@link Properties} from the {@code --jdbc-user} / {@code --jdbc-pass} CLI flags. */
  private static Properties cliJdbcProps(String jdbcUser, char[] jdbcPass) {
    Properties props = new Properties();
    if (jdbcUser != null)                        props.setProperty("user",     jdbcUser);
    if (jdbcPass != null && jdbcPass.length > 0) props.setProperty("password", new String(jdbcPass));
    return props;
  }

  /**
   * Resolves the effective {@link DbConfig}, layering — lowest to highest precedence —
   * {@code primary} (typically the server's sql-config, when fetched), then
   * {@code localCfg}'s own JDBC details (from its {@code sqlConfig} or loose
   * {@code jdbc_url}/{@code jdbc_driver_jar} fields), then the CLI override flags.
   * When {@code primary} and {@code localCfg}'s config are the same object (the common
   * case where the local file supplies a complete sql-config and no server call is made),
   * the middle merge is a harmless no-op.
   */
  private static DbConfig effectiveDbConfig(
      Optional<DbConfig> primary, LocalConfig localCfg,
      String jdbcUrlOverride, File jdbcDriverJarOverride) {
    return primary.orElse(DbConfig.EMPTY)
        .merge(localCfg.dbConfig().orElse(DbConfig.EMPTY))
        .merge(cliDbConfig(jdbcUrlOverride, jdbcDriverJarOverride));
  }

  static class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() throws Exception {
      try (InputStream in = VersionProvider.class.getResourceAsStream(
          "/META-INF/maven/io.crums/microchain-cli/pom.properties")) {
        if (in == null) return new String[]{"mchain (version unknown)"};
        Properties p = new Properties();
        p.load(in);
        return new String[]{"mchain " + p.getProperty("version", "unknown")};
      }
    }
  }
}
