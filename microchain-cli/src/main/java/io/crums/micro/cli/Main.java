/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.cli;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.Callable;

import io.crums.micro.api.ChainInfo;
import io.crums.micro.api.CommitRequest;
import io.crums.micro.api.SqlConfig;
import io.crums.micro.http.ChainClient;
import io.crums.sldg.HashConflictException;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.sql.SqlLedger;
import picocli.CommandLine;
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

    @Option(names = {"--token", "-t"}, required = true,
        description = "Bearer access token")
    String token;

    @Option(names = {"--jdbc-url"},
        description = "JDBC URL (overrides server sql-config)")
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

      // ── network + DB ─────────────────────────────────────────────────────────
      ChainClient client = newClient(opts);

      ChainInfo info = client.getInfo().orElseThrow(() ->
          new IllegalArgumentException("Chain not found: " + opts.chainUrl));

      TableSalt salt = info.saltScheme().hasSalt()
          ? client.getSalts(token)
          : null;

      SqlConfig sqlCfg = client.getSqlConfig(token);

      long committed = client.getState()
          .map(p -> p.hiRowNumber())
          .orElse(0L);

      // resolve + apply timezone: CLI > server > "UTC"
      String effectiveTimezone = timezoneOverride != null ? timezoneOverride
          : sqlCfg.timezone().orElse("UTC");
      try {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(effectiveTimezone)));
      } catch (java.time.zone.ZoneRulesException e) {
        System.err.println("ERROR: unknown timezone '" + effectiveTimezone + "'");
        return 1;
      }

      SqlConfig effectiveCfg = jdbcUrlOverride != null
          ? new SqlConfig(
              sqlCfg.byNoQuery(), sqlCfg.rowCountQuery(),
              Optional.of(jdbcUrlOverride), sqlCfg.jdbcDriverClass(),
              sqlCfg.timezone(), sqlCfg.defaultLag())
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
          var resp = client.commit(token, new CommitRequest(from, hashes));
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
