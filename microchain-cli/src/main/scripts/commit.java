///usr/bin/env jbang "$0" "$@" ; exit $?
//
// JBang script: commit newly-appended rows to a microchain.
//
// Prerequisites:
//   1. Build and install the project locally:
//        cd <microchain-client-root>  &&  mvn install
//   2. Supply your JDBC driver at run time via --deps, e.g. for PostgreSQL:
//        jbang run --deps org.postgresql:postgresql:42.7.3 commit.java ...
//      or for H2 (testing):
//        jbang run --repos mavenCentral,mavenLocal --deps com.h2database:h2:2.3.232 commit.java ...
//
// Usage:
//   jbang run --deps <group:artifact:version> microchain-cli/src/main/scripts/commit.java \
//       <chain-url> --token <t> \
//       [--jdbc-url <url>] [--jdbc-user <user>] [--jdbc-pass <pass>] [--batch <n>] \
//       [--timezone <tz>] [--from <n>] [--to <n>] [--lag <n>]
//
// Example:
//   jbang run --deps org.postgresql:postgresql:42.7.3 \
//       microchain-cli/src/main/scripts/commit.java \
//       http://localhost:8081/alice/sales \
//       --token my-access-token \
//       --jdbc-url jdbc:postgresql://localhost:5432/mydb \
//       --jdbc-user alice \
//       --jdbc-pass secret

//REPOS mavenCentral,mavenLocal
//DEPS io.crums:microchain-http:0.1.0-SNAPSHOT
//DEPS info.picocli:picocli:4.7.5

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
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

/**
 * Commits newly-appended rows from a local SQL ledger to a remote microchain.
 *
 * <p>Run {@code jbang microchain-cli/src/main/scripts/commit.java --help} for all options.</p>
 */
@Command(
    name = "commit",
    mixinStandardHelpOptions = true,
    versionProvider = commit.VersionProvider.class,
    description = {
        "Hash new rows from the local database and commit them to the chain.",
        "",
        "Workflow:",
        "  1. GET /{user}/{chain}/info        -> salt scheme",
        "  2. GET /{user}/{chain}/salts       -> table salt  (token required)",
        "  3. GET /{user}/{chain}/sql-config  -> JDBC queries",
        "  4. GET /{user}/{chain}/state       -> current committed row count N",
        "  5. Open local SqlLedger, hash rows N .. M  (see --from / --to / --lag)",
        "  6. POST /{user}/{chain}/commit     -> updated row count"
    })
class commit implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "<chain-url>",
      description = {
          "Full chain URL: <server>/{user}/{chain}",
          "  e.g. https://micro.crums.io/alice/sales"
      })
  URI chainUrl;

  @Option(names = {"--token", "-t"}, required = true,
      description = "Bearer access token")
  String token;

  @Option(names = {"--jdbc-url"},
      description = "JDBC URL (overrides value in server sql-config)")
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
          "JVM timezone for hashing DATE/TIMESTAMP columns (default: ${DEFAULT-VALUE}).",
          "Controls how ResultSet.getDate() interprets date values.",
          "Must match the timezone used when any existing chain hashes were built."
      },
      defaultValue = "UTC")
  String timezone;

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

  public static void main(String[] args) {
    System.exit(new CommandLine(new commit()).execute(args));
  }

  static class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() throws Exception {
      try (InputStream in = VersionProvider.class.getResourceAsStream(
          "/META-INF/maven/io.crums/microchain-http/pom.properties")) {
        if (in == null) return new String[]{"commit (version unknown)"};
        Properties p = new Properties();
        p.load(in);
        return new String[]{"commit " + p.getProperty("version", "unknown")};
      }
    }
  }

  @Override
  public Integer call() throws Exception {
    // ── option validation (before any I/O) ─────────────────────────────────
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(timezone)));
    } catch (java.time.zone.ZoneRulesException e) {
      System.err.println("ERROR: unknown timezone '" + timezone + "'");
      return 1;
    }
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

    // ── network + DB ───────────────────────────────────────────────────────
    URI base = URI.create(chainUrl.getScheme() + "://" + chainUrl.getAuthority());
    String[] parts = chainUrl.getPath().split("/");
    String username = parts[parts.length - 2];
    String chain    = parts[parts.length - 1];

    ChainClient client = new ChainClient(base, username, chain,
        HttpClient.newHttpClient());

    ChainInfo info = client.getInfo().orElseThrow(() ->
        new IllegalArgumentException("Chain not found: " + chainUrl));

    TableSalt salt = info.saltScheme().hasSalt()
        ? client.getSalts(token)
        : null;

    SqlConfig sqlCfg = client.getSqlConfig(token);

    long committed = client.getState()
        .map(p -> p.hiRowNumber())
        .orElse(0L);

    SqlConfig effectiveCfg = jdbcUrlOverride != null
        ? new SqlConfig(
            sqlCfg.byNoQuery(), sqlCfg.rowCountQuery(),
            Optional.of(jdbcUrlOverride), sqlCfg.jdbcDriverClass())
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
      System.err.println("ERROR: hash conflict at " + chainUrl);
      System.err.println("  " + e.getMessage());
      System.err.println(
          "One or more submitted input hashes do not match what is already recorded on the chain.");
      return 2;
    } catch (SecurityException e) {
      System.err.println("ERROR: " + e.getMessage());
      return 1;
    }
    return 0;
  }
}
