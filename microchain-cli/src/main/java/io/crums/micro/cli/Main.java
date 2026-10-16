/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import io.crums.micro.api.ChainInfo;
import io.crums.micro.api.ColumnInfo;
import io.crums.micro.api.CommitRequest;
import io.crums.micro.api.DbConfig;
import io.crums.micro.api.LocalConfig;
import io.crums.micro.api.LocalConfig.ChainConfig;
import io.crums.micro.api.SqlConfig;
import io.crums.micro.api.receipt.ChainReceipt;
import io.crums.micro.api.receipt.Receipt;
import io.crums.micro.api.receipt.ReceiptTemplate;
import io.crums.micro.http.ChainClient;
import io.crums.sldg.HashConflictException;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.salt.EpochedTableSalt.EpochSeed;
import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.Cell;
import io.crums.sldg.src.DataType;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.json.EpochSeedParser;
import io.crums.sldg.src.json.SourceRowParser;
import io.crums.sldg.src.sql.SqlLedger;
import io.crums.util.api.Base64_32;
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
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

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
        Main.CommitAllCommand.class,
        Main.StatusCommand.class,
        Main.InfoCommand.class,
        Main.RowsCommand.class,
        Main.ColumnsCommand.class,
        Main.ConfigCommand.class,
        Main.ReceiptCommand.class,
        Main.HelpCommand.class
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


  // ── help ─────────────────────────────────────────────────────────────────────

  /**
   * Replaces {@code picocli.CommandLine.HelpCommand} (registered under every
   * level of subcommands below) so that a path of subcommand names drills all
   * the way down to a deeply-nested command, e.g. {@code mchain help receipt
   * generate}. The picocli built-in only accepts a single name — extra names
   * are silently dropped, printing the wrong (shallower) command's help with
   * no indication anything was ignored.
   */
  @Command(name = "help", helpCommand = true,
      description = "Display help information about given subcommand.")
  static class HelpCommand implements Runnable {

    @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
    boolean helpRequested;

    @Parameters(arity = "0..*", paramLabel = "COMMAND",
        description = "The command, and any nested subcommand names, to show help for, " +
            "e.g. 'receipt generate'.")
    List<String> commands = List.of();

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
      CommandLine current = spec.parent().commandLine();
      for (String name : commands) {
        CommandLine next = current.getSubcommands().get(name);
        if (next == null) {
          throw new ParameterException(current,
              "Unknown subcommand '" + name + "' for '" + current.getCommandName() + "'");
        }
        current = next;
      }
      current.usage(System.out);
    }
  }


  // ── shared positional argument ───────────────────────────────────────────────

  static class ChainOptions {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<chain-url>",
        description = {
            "Full chain URL: <server>/{user}/{chain}",
            "  e.g. https://micro.crums.io/alice/sales",
            "May be omitted when --local-config resolves a chain_url (see --chain)."
        })
    URI chainUrl;

    @Option(names = {"--chain"}, paramLabel = "<alias>",
        description = {
            "Select this chain from the --local-config file's 'chains'",
            "object.  May be omitted when the file has exactly one chain",
            "configured; required when it has more than one."
        })
    String chainAlias;

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
          "  1. GET /info        → salted (true/false)",
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
            """
            Local JSON config file supplying local config: access_token, sql-config \
            fields, and/or salts array, per chain (see --chain). Fields present \
            here are used instead of fetching from the server. CLI flags \
            (--token, --jdbc-url, etc.) take precedence.""",
            """
            Recommended for sensitive values (token, salt seeds) to keep them out \
            of shell history."""
        })
    File localConfigFile;

    @Option(names = {"--token", "-t"},
        description = {
            "Bearer access token.",
            "May be omitted when --local-config resolves an 'access_token' field."
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
            """
            Defaults to the current chain length (re-submits the last committed \
            row as a minimal overlap/sanity check before appending new rows).""",
            "Set lower to widen the overlap, or to 1 for a full re-hash."
        })
    Long fromOverride;

    @Option(names = {"--to"},
        description = {
            "Last row to include in this commit (inclusive).",
            """
            Defaults to the total row count reported by the source ledger, \
            less --lag (whether set from the command line, local config file, \
            or the server).""",
            """
            Use to exclude rows whose source transactions may not yet have \
            committed, since sequence-allocated IDs can appear in MAX() before \
            the inserting transaction completes.""",
            "Mutually exclusive with --lag."
        })
    Long toOverride;

    @Option(names = {"--lag"},
        description = {
            "Number of trailing rows to withhold from this commit.",
            """
            Equivalent to --to (source_total - N). Convenient for scheduled \
            scripts where a fixed buffer guards against in-flight transactions \
            whose IDs have been allocated but not yet committed.""",
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
      ChainConfig localCfg = resolveLocalConfig(localConfigFile, opts.chainAlias);
      if (localCfg == null) return 1;

      // CLI chain-url wins; fall back to local config
      URI effectiveUrl = effectiveChainUrl(opts, localCfg);
      if (effectiveUrl == null) {
        System.err.println("ERROR: chain URL required; pass as argument, or configure " +
            "a resolvable chain_url (or --base-chain-url) in --local-config");
        return 1;
      }

      return commitOne(effectiveUrl, localCfg, token, jdbcUrlOverride, jdbcDriverJarOverride,
          jdbcUser, jdbcPass, batch, timezoneOverride, fromOverride, toOverride, lagOverride);
    }
  }

  /**
   * Commits new rows for a single chain — the shared body of {@code mchain commit}
   * and each iteration of {@code mchain commit-all}. {@code localCfg} supplies
   * whatever fields are locally known (access token, sql-config, salts); anything
   * absent is fetched from the server. CLI-flag parameters, when non-null, take
   * precedence over both.
   */
  private static int commitOne(
      URI effectiveUrl, ChainConfig localCfg, String token,
      String jdbcUrlOverride, File jdbcDriverJarOverride,
      String jdbcUser, char[] jdbcPass, int batch, String timezoneOverride,
      Long fromOverride, Long toOverride, Long lagOverride) throws Exception {

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
      if (info.salted()) {
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
           SqlLedger ledger = effectiveCfg.open(conn, salt)) {

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


  // ── commit-all ──────────────────────────────────────────────────────────────

  @Command(
      name = "commit-all",
      description = {
          "Commit new rows for every chain in a local config file.",
          "",
          """
          The local config file groups several related chains — e.g. chains backed \
          by tables in the same database — under a top-level 'chains' object, keyed \
          by a short local alias, plus shared defaults (jdbc_url, access_token, \
          timezone, default_lag, ...) that apply to any chain that doesn't set its \
          own.""",
          "",
          """
          A chain's own 'chain_url' wins if set; otherwise it's inferred as \
          'base_chain_url' + '/' + the chain's alias.  Example:""",
          "",
          "  {",
          "    \"base_chain_url\": \"https://micro.crums.io/bikeshop\",",
          "    \"jdbc_url\":       \"jdbc:postgresql://db.example.com:5432/mydb\",",
          "    \"access_token\":   \"mcw_...\",",
          "    \"chains\": {",
          "      \"orders\":      { \"by_no_query\": \"...\", \"row_count_query\": \"...\" },",
          "      \"order_items\": { \"by_no_query\": \"...\", \"row_count_query\": \"...\" }",
          "    }",
          "  }",
          "",
          "Build one with repeated 'mchain config <file> set --chain <alias> [OPTIONS]'."
      })
  static class CommitAllCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
    boolean helpRequested;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>", required = true,
        description = "Local JSON config file (must have a top-level 'chains' object).")
    File localConfigFile;

    @Option(names = {"--only"}, paramLabel = "<alias>", split = ",",
        description = "Restrict to these chain aliases (comma-separated). Default: every chain in the file.")
    List<String> only;

    @Option(names = {"--token", "-t"},
        description = "Bearer access token, applied to every chain that doesn't set its own.")
    String token;

    @Option(names = {"--jdbc-url"},
        description = "JDBC URL, applied to every chain (overrides shared defaults and any per-chain value).")
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
        description = "JVM timezone for hashing DATE/TIMESTAMP columns; overrides each chain's own.")
    String timezoneOverride;

    @Option(names = {"--from"},
        description = "First row to hash and submit, applied to every chain.")
    Long fromOverride;

    @Option(names = {"--to"},
        description = "Last row to include (inclusive), applied to every chain. Mutually exclusive with --lag.")
    Long toOverride;

    @Option(names = {"--lag"},
        description = "Trailing rows to withhold, applied to every chain. Mutually exclusive with --to.")
    Long lagOverride;

    @Override
    public Integer call() throws Exception {
      if (toOverride != null && lagOverride != null) {
        System.err.println("ERROR: --to and --lag are mutually exclusive");
        return 1;
      }

      LocalConfig cfg;
      try {
        cfg = LocalConfig.PARSER.toEntity(localConfigFile);
      } catch (Exception e) {
        System.err.println("ERROR: cannot read --local-config '" + localConfigFile + "': " + e.getMessage());
        return 1;
      }

      List<String> aliases = (only != null && !only.isEmpty())
          ? only
          : new ArrayList<>(cfg.chainAliases());
      if (aliases.isEmpty()) {
        System.err.println("ERROR: no chains configured in '" + localConfigFile + "'");
        return 1;
      }

      int failures = 0;
      for (String alias : aliases) {
        System.out.println("== " + alias + " ==");
        ChainConfig chainCfg;
        try {
          chainCfg = cfg.resolve(alias);
        } catch (java.util.NoSuchElementException e) {
          System.err.println("ERROR: unknown chain '" + alias + "' (not in '" + localConfigFile + "')");
          failures++;
          continue;
        }
        URI chainUrl = chainCfg.chainUrl().map(URI::create).orElse(null);
        if (chainUrl == null) {
          System.err.println("ERROR: no chain_url resolvable for '" + alias +
              "' — set it explicitly or add 'base_chain_url' to --local-config");
          failures++;
          continue;
        }
        try {
          int rc = commitOne(chainUrl, chainCfg, token, jdbcUrlOverride, jdbcDriverJarOverride,
              jdbcUser, jdbcPass, batch, timezoneOverride, fromOverride, toOverride, lagOverride);
          if (rc != 0) failures++;
        } catch (Exception e) {
          System.err.println("ERROR [" + alias + "]: " + e.getMessage());
          failures++;
        }
        System.out.println();
      }

      if (failures > 0) {
        System.out.println(failures + " of " + aliases.size() + " chain(s) failed.");
        return 1;
      }
      return 0;
    }
  }


  // ── status ───────────────────────────────────────────────────────────────────

  @Command(
      name = "status",
      description = {
          "Print the current committed row count (GET /state, public).",
          "",
          """
          When a row-count query is available — via --row-count-query, a \
          'row_count_query' field in --local-config, or the server's sql-config \
          (fetched with a bearer token when not available locally) — also connects \
          to the local database to report the source ledger's row count and how \
          many rows are available to commit, factoring in 'default_lag' when configured.""",
          "",
          """
          If --local-config defines multiple chains and --chain is omitted, prints \
          the status of every chain in the file."""
      })
  static class StatusCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>",
        description = {
            """
            Local JSON config file; the selected chain's (see --chain) 'chain_url' \
            field may replace the <chain-url> argument.  When it includes sql-config \
            fields (row_count_query, jdbc_url, default_lag, etc.), those are used \
            to report rows available to commit."""
        })
    File localConfigFile;

    @Option(names = {"--token", "-t"},
        description = {
            """
            Bearer access token.  Used to fetch the row-count query (and JDBC details, \
            if not supplied otherwise) from the server's sql-config when not available \
            via --row-count-query or --local-config.""",
            "May be omitted when --local-config resolves an 'access_token' field."
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
      if (localConfigFile == null || opts.chainAlias != null) {
        ChainConfig localCfg = resolveLocalConfig(localConfigFile, opts.chainAlias);
        if (localCfg == null) return 1;

        URI effectiveUrl = effectiveChainUrl(opts, localCfg);
        if (effectiveUrl == null) {
          System.err.println("ERROR: chain URL is required; pass as argument, or configure " +
              "a resolvable chain_url (or --base-chain-url) in --local-config");
          return 1;
        }
        return statusOne(effectiveUrl, localCfg, token, rowCountQueryOverride,
            jdbcUrlOverride, jdbcDriverJarOverride, jdbcUser, jdbcPass);
      }

      // --local-config given, --chain omitted: single chain auto-selects (as before);
      // multiple chains report status for every one of them.
      LocalConfig cfg;
      try {
        cfg = LocalConfig.PARSER.toEntity(localConfigFile);
      } catch (Exception e) {
        System.err.println("ERROR: cannot read --local-config '" + localConfigFile + "': " + e.getMessage());
        return 1;
      }

      List<String> aliases = new ArrayList<>(cfg.chainAliases());
      if (aliases.isEmpty()) {
        System.err.println("ERROR: '" + localConfigFile + "' has no chains configured; add one with 'mchain config " +
            localConfigFile + " set --chain <alias> ...'");
        return 1;
      }

      boolean multi = aliases.size() > 1;
      int failures = 0;
      for (String alias : aliases) {
        ChainConfig localCfg;
        try {
          localCfg = cfg.resolve(alias);
        } catch (java.util.NoSuchElementException e) {
          System.err.println("ERROR: unknown chain '" + alias + "' (not in '" + localConfigFile + "')");
          failures++;
          continue;
        }
        URI effectiveUrl = effectiveChainUrl(opts, localCfg);
        if (effectiveUrl == null) {
          System.err.println("ERROR: no chain_url resolvable for '" + alias +
              "' — set it explicitly or add 'base_chain_url' to --local-config");
          failures++;
          continue;
        }
        if (multi) System.out.println("== " + alias + " ==");
        int rc = statusOne(effectiveUrl, localCfg, token, rowCountQueryOverride,
            jdbcUrlOverride, jdbcDriverJarOverride, jdbcUser, jdbcPass);
        if (rc != 0) failures++;
        if (multi) System.out.println();
      }

      return failures > 0 ? 1 : 0;
    }
  }

  /** Prints the status (committed / source / available row counts) of one chain. */
  private static int statusOne(URI effectiveUrl, ChainConfig localCfg, String token,
      String rowCountQueryOverride, String jdbcUrlOverride, File jdbcDriverJarOverride,
      String jdbcUser, char[] jdbcPass) throws Exception {
    ChainClient client = newClient(effectiveUrl);
    var state = client.getState();
    long committed = state.map(p -> p.hiRowNumber()).orElse(0L);
    if (state.isEmpty()) {
      System.out.println(effectiveUrl + "  No committed rows.");
    } else {
      System.out.println(effectiveUrl + "  Committed rows: " + committed);
    }

    // ── source row count / available-to-commit (best effort) ────────────────
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


  // ── info ─────────────────────────────────────────────────────────────────────

  @Command(
      name = "info",
      description = "Print chain metadata (GET /info, public).")
  static class InfoCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>",
        description = "Local JSON config file; the selected chain's (see --chain) 'chain_url' field may replace the <chain-url> argument.")
    File localConfigFile;

    @Override
    public Integer call() throws Exception {
      ChainConfig localCfg = resolveLocalConfig(localConfigFile, opts.chainAlias);
      if (localCfg == null) return 1;

      URI effectiveUrl = effectiveChainUrl(opts, localCfg);
      if (effectiveUrl == null) {
        System.err.println("ERROR: chain URL is required; pass as argument, or configure " +
            "a resolvable chain_url (or --base-chain-url) in --local-config");
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
      System.out.println("Salt       : " + (info.salted() ? "yes" : "no"));
      return 0;
    }
  }


  // ── columns ──────────────────────────────────────────────────────────────────

  @Command(
      name = "columns",
      description = {
          "View or set column metadata (GET/PUT /{user}/{chain}/columns).",
          "",
          """
          With no --name / --description / --clear options, prints the chain's \
          current column metadata (public, no token required).""",
          "",
          """
          With any of those options, merges them into the chain's current column \
          list — existing entries not mentioned are left unchanged — then PUTs the \
          full merged list back (requires a token)."""
      })
  static class ColumnsCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>",
        description = "Local JSON config file; the selected chain's (see --chain) 'chain_url' and/or 'access_token' fields may replace the <chain-url> argument / --token.")
    File localConfigFile;

    @Option(names = {"--token", "-t"},
        description = "Bearer access token; required when setting column metadata.")
    String token;

    @Option(names = {"--name"}, paramLabel = "<index>=<name>",
        description = "Set the name for the given 0-based column index. Repeatable.")
    Map<Integer, String> names;

    @Option(names = {"--description"}, paramLabel = "<index>=<description>",
        description = "Set the description for the given 0-based column index. Repeatable.")
    Map<Integer, String> descriptions;

    @Option(names = {"--clear"}, paramLabel = "<index>",
        description = "Remove the given 0-based column index from the chain's column metadata. Repeatable.")
    List<Integer> clear;

    @Override
    public Integer call() throws Exception {
      ChainConfig localCfg = resolveLocalConfig(localConfigFile, opts.chainAlias);
      if (localCfg == null) return 1;

      URI effectiveUrl = effectiveChainUrl(opts, localCfg);
      if (effectiveUrl == null) {
        System.err.println("ERROR: chain URL is required; pass as argument, or configure " +
            "a resolvable chain_url (or --base-chain-url) in --local-config");
        return 1;
      }
      ChainClient client = newClient(effectiveUrl);

      boolean writing = (names != null && !names.isEmpty())
          || (descriptions != null && !descriptions.isEmpty())
          || (clear != null && !clear.isEmpty());

      if (!writing) {
        printColumns(client.getColumns());
        return 0;
      }

      String effectiveToken = token != null ? token : localCfg.accessToken().orElse(null);
      if (effectiveToken == null) {
        System.err.println("ERROR: bearer token required to set column metadata; " +
            "use --token or add 'access_token' to --local-config");
        return 1;
      }

      Map<Integer, ColumnInfo> merged = new TreeMap<>();
      for (ColumnInfo c : client.getColumns())
        merged.put(c.index(), c);

      if (names != null) {
        for (var e : names.entrySet()) {
          int index = e.getKey();
          if (index < 0) {
            System.err.println("ERROR: --name index < 0: " + index);
            return 1;
          }
          var existing = merged.get(index);
          var description = existing == null ? Optional.<String>empty() : existing.description();
          merged.put(index, new ColumnInfo(index, e.getValue(), description));
        }
      }
      if (descriptions != null) {
        for (var e : descriptions.entrySet()) {
          int index = e.getKey();
          var existing = merged.get(index);
          if (existing == null) {
            System.err.println("ERROR: --description for index " + index +
                " requires a name (existing on the server, or via --name)");
            return 1;
          }
          merged.put(index, new ColumnInfo(index, existing.name(), Optional.of(e.getValue())));
        }
      }
      if (clear != null)
        clear.forEach(merged::remove);

      List<ColumnInfo> result = List.copyOf(merged.values());
      client.putColumns(effectiveToken, result);
      printColumns(result);
      return 0;
    }

    private static void printColumns(List<ColumnInfo> columns) {
      if (columns.isEmpty()) {
        System.out.println("(no column metadata configured)");
        return;
      }
      for (ColumnInfo c : columns) {
        System.out.printf("  [%d] %-20s%s%n", c.index(), c.name(),
            c.description().map(d -> "  " + d).orElse(""));
      }
    }
  }


  // ── rows ─────────────────────────────────────────────────────────────────────

  @Command(
      name = "rows",
      description = {
          "Output source rows from the local SQL ledger.",
          "",
          """
          Fetches chain info (public), then sql-config and salt from the server \
          (requires token) or from --local-config if supplied there.  Queries the \
          local database by row number (--row) or by an arbitrary WHERE clause \
          (--where, rewriting the by-no-query).""",
          "",
          """
          When --local-config already supplies sql-config and a resolvable salt \
          story (unsalted, or salted with 'salted' + 'salts' both present \
          locally), falls back to that local-only config if the chain isn't \
          (yet) found at the server — handy for testing queries against a chain \
          that hasn't been registered yet.""",
          "",
          "Output is JSON (default) or pretty-printed one-cell-per-line (--pretty)."
      })
  static class RowsCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Option(names = {"--local-config", "-c"}, paramLabel = "<file>",
        description = {
            """
            Local JSON config file supplying local config: access_token, sql-config \
            fields, and/or salts array, per chain (see --chain).  Fields present \
            here are used instead of fetching from the server.  CLI flags \
            (--token, --jdbc-url, etc.) take precedence."""
        })
    File localConfigFile;

    @Option(names = {"--token", "-t"},
        description = {
            "Bearer access token.",
            "May be omitted when --local-config resolves both sql-config and salt."
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
      ChainConfig localCfg = resolveLocalConfig(localConfigFile, opts.chainAlias);
      if (localCfg == null) return 1;

      String effectiveToken = token != null ? token : localCfg.accessToken().orElse(null);

      // CLI chain-url wins; fall back to local config
      URI effectiveUrl = effectiveChainUrl(opts, localCfg);

      // ── chain info, salt, sql-config (public/local-fallback, then salt+sql) ──
      ChainClient client = effectiveUrl != null ? newClient(effectiveUrl) : null;
      ResolvedQueryConfig rqc;
      try {
        rqc = resolveQueryConfig(localCfg, effectiveUrl, client, effectiveToken);
      } catch (IllegalStateException e) {
        System.err.println("ERROR: " + e.getMessage());
        return 1;
      }
      TableSalt  salt       = rqc.salt();
      SqlConfig  sqlCfg     = rqc.sqlConfig();

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
      List<String> jdbcLabels = List.of();
      try (Connection conn = effectiveCfg.openConnection(props.isEmpty() ? null : props)) {
        if (rowNo != null) {
          try (LabeledSqlLedger ledger = LabeledSqlLedger.open(
              conn, effectiveCfg.rowCountQuery(), effectiveCfg.byNoQuery(),
              salt)) {
            rows = List.of(ledger.getSourceRow(rowNo));
            jdbcLabels = ledger.columnLabels();
          }
        } else {
          try (DisplaySqlLedger ledger = DisplaySqlLedger.open(
              conn, effectiveCfg.rowCountQuery(), effectiveCfg.byNoQuery(),
              salt)) {
            rows = ledger.query(whereClause);
            jdbcLabels = ledger.columnLabels();
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
        List<ColumnInfo> serverColumns = client != null ? client.getColumns() : List.of();
        List<String> columnLabels = resolveColumnLabels(serverColumns, jdbcLabels);
        for (SourceRow row : rows)
          printRow(row, pretty, columnLabels, effectiveTz);
      }
      return 0;
    }

    /**
     * Resolves per-index display labels, sized to {@code jdbcLabels} — captured
     * from the same {@code ResultSetMetaData} that produced the row's cells, so
     * it always matches the row's actual cell count (unlike {@code serverColumns},
     * a disconnected, possibly-stale, possibly-sparse config). A server-configured
     * {@link ColumnInfo#name()} wins over the JDBC label when both are present;
     * {@code null} means the caller falls back to a bare positional index.
     */
    private static List<String> resolveColumnLabels(List<ColumnInfo> serverColumns, List<String> jdbcLabels) {
      int size = jdbcLabels.size();
      String[] labels = new String[size];
      for (int i = 0; i < size; i++) {
        String jdbcLabel = jdbcLabels.get(i);
        labels[i] = jdbcLabel != null && !jdbcLabel.isBlank() ? jdbcLabel : null;
      }
      for (ColumnInfo c : serverColumns)
        if (c.index() < size) labels[c.index()] = c.name();
      return Arrays.asList(labels);
    }

    private static void printRow(SourceRow row, boolean pretty, List<String> columnLabels, String timezone) {
      if (pretty) {
        System.out.println("Row " + row.no() +
            "  (hash: " + HashEncoding.BASE64_32.encode(row.hash()) + ")");
        var cells = row.cells();
        var types = row.cellTypes();
        if (cells.isEmpty()) {
          System.out.println("  (null row)");
        } else {
          // Pre-format every cell first so the (TYPE) column can be aligned
          // to the row's longest value instead of a fixed width — a value
          // longer than that fixed width (e.g. a DATE with its timezone
          // suffix) would otherwise push just that row's (TYPE) marker out
          // of line with the rest.
          String[] vals = new String[cells.size()];
          int valWidth = 30;
          for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            vals[i] = cell.isRedacted()
                ? "REDACTED"
                : formatCellValue(cell.value(), types.get(i), timezone);
            valWidth = Math.max(valWidth, vals[i].length());
          }
          String noLabelFmt   = "  [%d] %-" + valWidth + "s  (%s)%n";
          String withLabelFmt = "  [%d] %-16s %-" + valWidth + "s  (%s)%n";
          for (int i = 0; i < cells.size(); i++) {
            DataType t = types.get(i);
            String label = i < columnLabels.size() ? columnLabels.get(i) : null;
            // 0-based, matching mchain columns'
            // --name/--description/--clear index convention.
            if (label == null)
              System.out.printf(noLabelFmt, i, vals[i], t);
            else
              System.out.printf(withLabelFmt, i, label, vals[i], t);
          }
        }
      } else {
        JSONObject jObj = SourceRowParser.INSTANCE.toJsonObject(row);
        jObj.put("hash", HashEncoding.BASE64_32.encode(row.hash()));
        JsonPrinter.println(jObj);
      }
    }

    private static final DateTimeFormatter PRETTY_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String formatCellValue(Object val, DataType type, String timezone) {
      if (val == null) return "NULL";
      return switch (type) {
        case STRING -> "\"" + val + "\"";
        case DATE   -> PRETTY_DATE_FORMAT
            .withZone(ZoneId.of(timezone))
            .format(Instant.ofEpochMilli((Long) val)) + " " + timezone;
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
          "The 'set' subcommand writes one or more settings into <file>."
      },
      subcommands = { Main.ConfigCommand.SetCommand.class, Main.HelpCommand.class })
  static class ConfigCommand implements Runnable {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<file>",
        description = "Path to the local JSON config file to create or update.")
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
            """
            Options not supplied are left unchanged.  Invoke multiple times to build \
            the file incrementally.  --chain-url, --by-no-query, --row-count-query, \
            --salt-seed, and --salted configure one specific chain and require \
            --chain <alias>; the rest (--jdbc-url, --token, --timezone, ...) may be \
            set with or without --chain — without it, they become shared defaults \
            any chain not overriding them falls back to.  A typical \
            dry-run setup for one chain:""",
            "",
            "  mchain config cfg.json set --jdbc-url jdbc:postgresql://db:5432/mydb",
            "  mchain config cfg.json set --chain sales --by-no-query  \"SELECT ... WHERE row_no = ?\"",
            "  mchain config cfg.json set --chain sales --row-count-query \"SELECT MAX(row_no) FROM ...\"",
            "  mchain config cfg.json set --chain sales --salt-seed   # auto-generates a secure seed",
            "  mchain commit --local-config cfg.json --chain sales",
            ""
        })
    static class SetCommand implements Callable<Integer> {

      @CommandLine.ParentCommand
      ConfigCommand parent;

      @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
      boolean helpRequested;

      @Option(names = {"--chain"}, paramLabel = "<alias>",
          description = {
              "Apply this update to the named chain within the file's",
              "top-level 'chains' object instead of the top level.",
              "Creates the 'chains' object and/or the named entry if absent.",
              "See 'mchain commit-all --help' for the local config file format."
          })
      String chainAlias;

      @Option(names = {"--base-chain-url"}, paramLabel = "<url>",
          description = {
              "Shared-default URL prefix (<server>/{user}). Combined with each",
              "chain's alias (its key under 'chains') to infer that chain's",
              "chain_url when the chain doesn't set its own. Always applies at",
              "the top level of the file, regardless of --chain."
          })
      String baseChainUrl;

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

      @Option(names = {"--salted"}, paramLabel = "<true|false>",
          description = {
              "Whether every cell in this chain's rows is salted.",
              """
              Stored as 'salted' in the config file; required for offline \
              'mchain rows' when 'salts' are also present."""
          })
      Boolean salted;

      @Override
      public Integer call() throws Exception {
        if (parent.configFile == null) {
          System.err.println("ERROR: <file> is required");
          return 1;
        }
        if (chainAlias == null && (chainUrl != null || byNoQuery != null || rowCountQuery != null
            || saltSeed != null || salted != null)) {
          System.err.println("ERROR: --chain-url, --by-no-query, --row-count-query, --salt-seed, " +
              "and --salted configure one specific chain and require --chain <alias>");
          return 1;
        }
        Path path = parent.configFile.toPath();
        boolean existed = Files.exists(path);

        JSONObject jObj;
        if (existed) {
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

        // --base-chain-url always applies at the top level, regardless of --chain
        if (baseChainUrl != null) jObj.put(BASE_CHAIN_URL_KEY, baseChainUrl);

        // Everything else applies either to the file root (shared defaults),
        // or — when --chain is given — to the named entry under the
        // top-level 'chains' object.
        JSONObject chainsObj = null;
        JSONObject target = jObj;
        if (chainAlias != null) {
          Object chainsVal = jObj.get(CHAINS_KEY);
          chainsObj = (chainsVal instanceof JSONObject co) ? co : new JSONObject();
          Object entryVal = chainsObj.get(chainAlias);
          target = (entryVal instanceof JSONObject eo) ? eo : new JSONObject();
        }

        if (chainUrl        != null) target.put(CHAIN_URL_KEY,           chainUrl);
        if (token           != null) target.put(ACCESS_TOKEN_KEY,       token);
        if (jdbcUrl         != null) target.put(JDBC_URL_KEY,           jdbcUrl);
        if (jdbcDriverClass != null) target.put(JDBC_DRIVER_CLASS_KEY,  jdbcDriverClass);
        if (jdbcDriverJar   != null) target.put(JDBC_DRIVER_JAR_KEY,    jdbcDriverJar.toString());
        if (jdbcProps       != null) {
          JSONObject propsObj = new JSONObject();
          jdbcProps.forEach(propsObj::put);
          target.put(JDBC_PROPS_KEY, propsObj);
        }
        if (byNoQuery       != null) target.put(BY_NO_QUERY_KEY,        byNoQuery);
        if (rowCountQuery   != null) target.put(ROW_COUNT_QUERY_KEY,    rowCountQuery);
        if (timezone        != null) target.put(TIMEZONE_KEY,           timezone);
        if (defaultLag      != null) target.put(DEFAULT_LAG_KEY,        defaultLag);

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
          target.put(SALTS_KEY, saltsArr);
        }

        if (salted != null) target.put(SALTED_KEY, salted);

        // Write the (possibly new/updated) per-chain entry back into 'chains'
        if (chainAlias != null) {
          chainsObj.put(chainAlias, target);
          jObj.put(CHAINS_KEY, chainsObj);
        }

        Files.writeString(path, JsonPrinter.toJson(jObj));
        System.out.println((existed ? "Updated: " : "Created: ") + path);
        return 0;
      }
    }
  }


  // ── receipt ──────────────────────────────────────────────────────────────────

  @Command(
      name = "receipt",
      description = "Generate and anchor tamper-evident receipts.",
      subcommands = {
          Main.ReceiptCommand.GenerateCommand.class,
          Main.ReceiptCommand.AnchorCommand.class,
          Main.HelpCommand.class
      })
  static class ReceiptCommand implements Runnable {

    @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
    boolean helpRequested;

    @Override
    public void run() {
      new CommandLine(this).usage(System.out);
    }


    @Command(
        name = "generate",
        description = {
            "Generate a draft receipt from a 'receipt_templates' entry in --local-config.",
            "",
            """
            Walks the named template's chain selectors in order: the first binds \
            <input>... directly (its 'where' clause's '?'s, in order, or — if it has \
            no 'where' — the single <input> as a row number); every later selector \
            derives its own bound values from a row already resolved for an earlier \
            chain, via ROW_NO(:alias) or :alias.column in its 'args'. The first chain \
            must resolve to exactly one row; every other chain may resolve to any \
            number (including zero) without that being an error — see 'chain' field \
            in a RowSelector's javadoc for the full binding rules.""",
            "",
            "Every chain touched needs a complete local sql-config and salt story " +
            "(server-fetched sql-config/salts also work, same as 'mchain rows').",
            "",
            "Output is a draft receipt (no commitment proof yet) — see 'mchain receipt anchor'."
        })
    static class GenerateCommand implements Callable<Integer> {

      @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
      boolean helpRequested;

      @Option(names = {"--local-config", "-c"}, required = true, paramLabel = "<file>",
          description = "Local JSON config file with a 'receipt_templates' object.")
      File localConfigFile;

      @Option(names = {"--template"}, required = true, paramLabel = "<name>",
          description = "Template name under 'receipt_templates' in --local-config.")
      String templateName;

      @Option(names = {"--token", "-t"},
          description = "Bearer access token, for any chain needing server-fetched sql-config/salts.")
      String token;

      @Option(names = {"--jdbc-url"},
          description = "JDBC URL, applied to every chain touched (overrides shared defaults and any per-chain value).")
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

      @Parameters(paramLabel = "<input>",
          description = "Input value(s) bound to the template's first chain, in order.")
      List<String> inputs = List.of();

      @Override
      public Integer call() throws Exception {
        LocalConfig cfg;
        try {
          cfg = LocalConfig.PARSER.toEntity(localConfigFile);
        } catch (Exception e) {
          System.err.println("ERROR: cannot read --local-config '" + localConfigFile + "': " + e.getMessage());
          return 1;
        }

        ReceiptTemplate template;
        try {
          template = cfg.resolveTemplate(templateName);
        } catch (java.util.NoSuchElementException e) {
          System.err.println("ERROR: unknown receipt template '" + templateName +
              "' (not in '" + localConfigFile + "')");
          return 1;
        }

        var generator = new ReceiptGenerator(
            cfg, token, jdbcUrlOverride, jdbcDriverJarOverride, jdbcUser, jdbcPass);

        Receipt receipt;
        try {
          receipt = generator.generate(template, inputs);
        } catch (IllegalStateException | IllegalArgumentException e) {
          System.err.println("ERROR: " + e.getMessage());
          return 1;
        }

        JsonPrinter.println(Receipt.PARSER.toJsonObject(receipt));
        return 0;
      }
    }


    @Command(
        name = "anchor",
        description = {
            "Attach a skipledger commitment path to each chain entry in a receipt.",
            "",
            """
            For every entry not already anchored, fetches GET /state for exactly \
            its row numbers plus the chain's latest row, and attaches the result as \
            that entry's 'state'. An entry whose chain has no committed rows yet (or \
            can't be reached) is left as a draft, with a warning — anchoring is \
            best-effort per chain, not all-or-nothing."""
        })
    static class AnchorCommand implements Callable<Integer> {

      @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
      boolean helpRequested;

      @Parameters(index = "0", paramLabel = "<receipt-file>",
          description = "Draft (or partially anchored) receipt JSON file.")
      File receiptFile;

      @Option(names = {"-o", "--output"}, paramLabel = "<file>",
          description = "Write the anchored receipt here instead of stdout.")
      File outputFile;

      @Override
      public Integer call() throws Exception {
        Receipt receipt;
        try {
          receipt = Receipt.PARSER.toEntity(receiptFile);
        } catch (Exception e) {
          System.err.println("ERROR: cannot read '" + receiptFile + "': " + e.getMessage());
          return 1;
        }

        int anchoredCount = 0;
        List<ChainReceipt> result = new ArrayList<>(receipt.chains().size());
        for (ChainReceipt cr : receipt.chains()) {
          if (cr.isAnchored()) {
            result.add(cr);
            anchoredCount++;
            continue;
          }
          try {
            URI chainUrl = URI.create(cr.chainUrl());
            ChainClient client = newClient(chainUrl);
            Optional<io.crums.sldg.Path> path = client.getState(cr.rowNumbers(), true, true);
            if (path.isEmpty()) {
              System.err.println("WARN: " + cr.chainUrl() +
                  " has no committed rows yet; leaving as draft");
              result.add(cr);
              continue;
            }
            result.add(cr.withState(path.get()));
            anchoredCount++;
          } catch (Exception e) {
            System.err.println("WARN: could not anchor " + cr.chainUrl() +
                "; leaving as draft: " + e.getMessage());
            result.add(cr);
          }
        }

        Receipt anchored = new Receipt(result);
        String json = JsonPrinter.toJson(Receipt.PARSER.toJsonObject(anchored));
        if (outputFile != null) {
          Files.writeString(outputFile.toPath(), json);
          System.out.println("Wrote: " + outputFile);
        } else {
          System.out.println(json);
        }

        if (anchoredCount < result.size())
          System.err.println("Anchored " + anchoredCount + " of " + result.size() + " chain(s).");
        return 0;
      }
    }
  }


  // ── helpers ──────────────────────────────────────────────────────────────────

  /**
   * Loads {@code localConfigFile} (if given) as a {@link LocalConfig} and
   * resolves the single chain to operate on: {@code chainAlias} when given,
   * else the file's sole chain when it has exactly one. Used by every
   * command that operates on one chain at a time — {@code commit-all} manages
   * its own iteration over every chain in the file instead.
   *
   * <p>Returns {@link ChainConfig#EMPTY} when {@code localConfigFile} is
   * {@code null} ({@code chainAlias} must then also be null). On any
   * failure — unreadable file, unknown alias, or an empty/ambiguous set of
   * chains when no alias was given — prints an {@code ERROR:} line and
   * returns {@code null}.</p>
   */
  private static ChainConfig resolveLocalConfig(File localConfigFile, String chainAlias) {
    if (localConfigFile == null) {
      if (chainAlias != null) {
        System.err.println("ERROR: --chain requires --local-config");
        return null;
      }
      return ChainConfig.EMPTY;
    }
    LocalConfig cfg;
    try {
      cfg = LocalConfig.PARSER.toEntity(localConfigFile);
    } catch (JsonParsingException | UncheckedIOException e) {
      System.err.println("ERROR: cannot read --local-config '" + localConfigFile + "': " + e.getMessage());
      return null;
    }
    String alias = chainAlias;
    if (alias == null) {
      var aliases = cfg.chainAliases();
      if (aliases.size() == 1) {
        alias = aliases.iterator().next();
      } else if (aliases.isEmpty()) {
        System.err.println("ERROR: '" + localConfigFile + "' has no chains configured; " +
            "add one with 'mchain config " + localConfigFile + " set --chain <alias> ...'");
        return null;
      } else {
        System.err.println("ERROR: '" + localConfigFile + "' has multiple chains " + aliases +
            "; select one with --chain <alias>");
        return null;
      }
    }
    try {
      return cfg.resolve(alias);
    } catch (java.util.NoSuchElementException e) {
      System.err.println("ERROR: unknown chain '" + alias + "' (not in '" + localConfigFile + "')");
      return null;
    }
  }

  /** CLI positional arg wins; falls back to the resolved local config's chain_url. */
  private static URI effectiveChainUrl(ChainOptions opts, ChainConfig localCfg) {
    if (opts.chainUrl != null)
      return opts.chainUrl;
    return localCfg.chainUrl().map(URI::create).orElse(null);
  }

  static ChainClient newClient(URI chainUrl) {
    URI base = URI.create(chainUrl.getScheme() + "://" + chainUrl.getAuthority());
    String[] parts = chainUrl.getPath().split("/");
    int n = parts.length;
    if (n < 2 || parts[n-1].isEmpty() || parts[n-2].isEmpty())
      throw new IllegalArgumentException(
          "chain-url must end with /{user}/{chain}: " + chainUrl);
    return new ChainClient(base, parts[n-2], parts[n-1], HttpClient.newHttpClient());
  }

  /** Table salt (or {@code null} if unsalted) and SQL ledger config needed to query one chain locally. */
  record ResolvedQueryConfig(TableSalt salt, SqlConfig sqlConfig) { }

  /**
   * Resolves the {@link TableSalt} and {@link SqlConfig} needed
   * to open a local ledger for one chain — the same local-config-first /
   * server-fallback logic {@code mchain rows} uses (see that command's
   * description), factored out for reuse by the receipt-template resolver,
   * which repeats this once per chain in a template.
   *
   * @param client the already-resolved {@link ChainClient} for {@code effectiveUrl}
   *               ({@code null} when no chain URL is resolvable), kept as a
   *               parameter (rather than constructed here) so callers that
   *               need it again afterward (e.g. for {@code GET /columns})
   *               don't have to re-derive it
   * @throws IllegalStateException with a user-facing message, on any
   *         unresolvable configuration (missing token, chain not found, etc.)
   */
  static ResolvedQueryConfig resolveQueryConfig(
      ChainConfig localCfg, URI effectiveUrl, ChainClient client, String effectiveToken)
      throws IOException, InterruptedException {

    // ── chain info (public) ──────────────────────────────────────────────────
    // We need ChainInfo to know whether the chain is salted.  We can skip — or
    // gracefully survive a failed — server call when the local config has
    // sql-config and either: (a) no salt epochs (→ unsalted), or (b) salt
    // epochs plus an explicit 'salted' setting.  The latter lets queries be
    // tested against a chain that hasn't been registered on the server yet.
    Optional<ChainInfo> maybeInfo = client != null ? client.getInfo() : Optional.empty();
    boolean localDryRunCapable = localCfg.sqlConfig().isPresent() &&
        (localCfg.saltEpochs().isEmpty() || localCfg.salted().isPresent());

    ChainInfo info;
    if (maybeInfo.isPresent()) {
      info = maybeInfo.get();
    } else if (localDryRunCapable) {
      if (client != null)
        System.err.println("WARN: chain not found at " + effectiveUrl +
            "; proceeding with local-only config");
      boolean salted = localCfg.salted().orElse(false);
      info = new ChainInfo(1, Optional.empty(), Optional.empty(), salted);
    } else if (localCfg.sqlConfig().isPresent()) {
      throw new IllegalStateException("'salted' is required in --local-config " +
          "when 'salts' are present and the chain isn't resolvable on the server");
    } else if (client != null) {
      throw new IllegalStateException("chain not found: " + effectiveUrl);
    } else {
      throw new IllegalStateException("chain URL required; pass as argument, or configure " +
          "a resolvable chain_url (or --base-chain-url) in --local-config");
    }

    // ── salt ──────────────────────────────────────────────────────────────────
    TableSalt salt = null;
    if (info.salted()) {
      var localSalt = localCfg.tableSalt();
      if (localSalt.isPresent()) {
        salt = localSalt.get();
      } else {
        if (effectiveToken == null)
          throw new IllegalStateException("bearer token required to fetch salts; " +
              "use --token or add 'access_token' to --local-config");
        if (client == null)
          throw new IllegalStateException("a 'salts' array is required in --local-config " +
              "since no chain URL is configured to fetch it from");
        salt = client.getSalts(effectiveToken);
      }
    }

    // ── sql-config ────────────────────────────────────────────────────────────
    SqlConfig sqlCfg;
    if (localCfg.sqlConfig().isPresent()) {
      sqlCfg = localCfg.sqlConfig().get();
    } else {
      if (effectiveToken == null)
        throw new IllegalStateException("bearer token required to fetch sql-config; " +
            "use --token or add sql-config fields to --local-config");
      if (client == null)
        throw new IllegalStateException("sql-config fields are required in --local-config " +
            "since no chain URL is configured to fetch them from");
      sqlCfg = client.getSqlConfig(effectiveToken);
    }

    return new ResolvedQueryConfig(salt, sqlCfg);
  }

  /** Builds a {@link DbConfig} from the {@code --jdbc-url} / {@code --jdbc-driver-jar} CLI flags. */
  private static DbConfig cliDbConfig(String jdbcUrlOverride, File jdbcDriverJarOverride) {
    DbConfig cliDb = DbConfig.EMPTY;
    if (jdbcUrlOverride != null)       cliDb = cliDb.withJdbcUrl(jdbcUrlOverride);
    if (jdbcDriverJarOverride != null) cliDb = cliDb.withJdbcDriverJar(jdbcDriverJarOverride.toPath().toAbsolutePath());
    return cliDb;
  }

  /** Builds JDBC connection {@link Properties} from the {@code --jdbc-user} / {@code --jdbc-pass} CLI flags. */
  static Properties cliJdbcProps(String jdbcUser, char[] jdbcPass) {
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
  static DbConfig effectiveDbConfig(
      Optional<DbConfig> primary, ChainConfig localCfg,
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
