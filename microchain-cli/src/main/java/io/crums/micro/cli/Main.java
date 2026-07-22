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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.Callable;

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
import io.crums.sldg.src.json.EpochSeedParser;
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

    @Parameters(index = "0", paramLabel = "<chain-url>",
        description = {
            "Full chain URL: <server>/{user}/{chain}",
            "  e.g. https://micro.crums.io/alice/sales"
        })
    URI chainUrl;

    // Subcommands handle --help themselves; -V belongs only on the root command.
    @Option(names = {"-h", "--help"}, usageHelp = true, hidden = true)
    boolean helpRequested;

    URI base() {
      return URI.create(chainUrl.getScheme() + "://" + chainUrl.getAuthority());
    }

    String username() { return pathSegment(-2); }
    String chain()    { return pathSegment(-1); }

    private String pathSegment(int fromEnd) {
      String[] parts = chainUrl.getPath().split("/");
      int idx = parts.length + fromEnd;
      if (idx < 1 || parts[idx].isEmpty())
        throw new IllegalArgumentException(
            "chain-url must end with /{user}/{chain}: " + chainUrl);
      return parts[idx];
    }
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

      // CLI --token wins; fall back to file's access_token
      String effectiveToken = token != null ? token : localCfg.accessToken().orElse(null);
      if (effectiveToken == null) {
        System.err.println("ERROR: bearer token required; use --token <t> or add 'access_token' to --local-config file");
        return 1;
      }

      // ── network + DB ─────────────────────────────────────────────────────────
      ChainClient client = newClient(opts);

      ChainInfo info = client.getInfo().orElseThrow(() ->
          new IllegalArgumentException("Chain not found: " + opts.chainUrl));

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

      SqlConfig effectiveCfg = jdbcUrlOverride != null
          ? sqlCfg.withJdbcUrl(jdbcUrlOverride)
          : sqlCfg;

      Properties props = new Properties();
      if (jdbcUser != null)
        props.setProperty("user", jdbcUser);
      if (jdbcPass != null && jdbcPass.length > 0)
        props.setProperty("password", new String(jdbcPass));

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
          System.out.println("  Committed rows " + from + "–" + to +
              "  (chain total: " + resp.rowCount() + ")");
          from = to + 1;
        }
      } catch (HashConflictException e) {
        System.err.println("ERROR: hash conflict at " + opts.chainUrl);
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


  // ── status ───────────────────────────────────────────────────────────────────

  @Command(
      name = "status",
      description = "Print the current committed row count (GET /state, public).")
  static class StatusCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Override
    public Integer call() throws Exception {
      ChainClient client = newClient(opts);
      var state = client.getState();
      if (state.isEmpty()) {
        System.out.println(opts.chainUrl + "  No committed rows.");
      } else {
        System.out.println(opts.chainUrl +
            "  Committed rows: " + state.get().hiRowNumber());
      }
      return 0;
    }
  }


  // ── info ─────────────────────────────────────────────────────────────────────

  @Command(
      name = "info",
      description = "Print chain metadata (GET /info, public).")
  static class InfoCommand implements Callable<Integer> {

    @Mixin ChainOptions opts;

    @Override
    public Integer call() throws Exception {
      ChainClient client = newClient(opts);
      Optional<ChainInfo> maybeInfo = client.getInfo();
      if (maybeInfo.isEmpty()) {
        System.err.println("Chain not found: " + opts.chainUrl);
        return 1;
      }
      ChainInfo info = maybeInfo.get();
      System.out.println("Chain      : " + opts.chainUrl);
      System.out.println("Type       : " +
          (info.ledgerType() == 1 ? "SQL ledger" : "log-file ledger"));
      info.title().ifPresent(t       -> System.out.println("Title      : " + t));
      info.description().ifPresent(d -> System.out.println("Description: " + d));
      System.out.println("Salt       : " +
          (info.saltScheme().hasSalt()
              ? "yes (" + info.saltScheme() + ")"
              : "none"));
      return 0;
    }
  }


  // ── config ───────────────────────────────────────────────────────────────────

  @Command(
      name = "config",
      description = "Create or update a local config file.",
      subcommands = { Main.ConfigCommand.SetCommand.class, CommandLine.HelpCommand.class })
  static class ConfigCommand implements Runnable {

    @Parameters(index = "0", paramLabel = "<file>",
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
        description = {
            "Set one or more configuration values in <file>, creating it if necessary.",
            "",
            "Options not supplied are left unchanged.  Invoke multiple times to build",
            "the file incrementally.  A typical dry-run setup:",
            "",
            "  mchain config cfg.json set --jdbc-url jdbc:postgresql://db:5432/mydb",
            "  mchain config cfg.json set --by-no-query  \"SELECT ... WHERE row_no = ?\"",
            "  mchain config cfg.json set --row-count-query \"SELECT COUNT(*) FROM ...\"",
            "  mchain config cfg.json set --salt-seed   # auto-generates a secure seed",
            "  mchain commit https://micro.crums.io/alice/sales --local-config cfg.json"
        })
    static class SetCommand implements Callable<Integer> {

      @CommandLine.ParentCommand
      ConfigCommand parent;

      @Option(names = {"--token", "-t"},
          description = "Bearer access token.")
      String token;

      @Option(names = {"--jdbc-url"},
          description = "JDBC connection URL.")
      String jdbcUrl;

      @Option(names = {"--jdbc-driver-class"},
          description = "Fully-qualified JDBC driver class (usually auto-detected).")
      String jdbcDriverClass;

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

      @Override
      public Integer call() throws Exception {
        Path path = parent.configFile.toPath();

        JSONObject jObj;
        if (Files.exists(path)) {
          try {
            jObj = (JSONObject) new JSONParser().parse(Files.readString(path));
          } catch (ParseException e) {
            System.err.println("ERROR: cannot parse config file: " + path);
            System.err.println("  " + e.getMessage());
            return 1;
          }
        } else {
          jObj = new JSONObject();
        }

        if (token           != null) jObj.put(ACCESS_TOKEN_KEY,       token);
        if (jdbcUrl         != null) jObj.put(JDBC_URL_KEY,           jdbcUrl);
        if (jdbcDriverClass != null) jObj.put(JDBC_DRIVER_CLASS_KEY,  jdbcDriverClass);
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

        Files.writeString(path, JsonPrinter.toJson(jObj));
        System.out.println("Updated: " + path);
        return 0;
      }
    }
  }


  // ── helpers ──────────────────────────────────────────────────────────────────

  private static ChainClient newClient(ChainOptions opts) {
    return new ChainClient(opts.base(), opts.username(), opts.chain(),
        HttpClient.newHttpClient());
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
