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
//       <chain-url> [--token <t>] [--local-config <file>] \
//       [--jdbc-url <url>] [--jdbc-driver-jar <jar>] \
//       [--jdbc-user <user>] [--jdbc-pass <pass>] [--batch <n>] \
//       [--timezone <tz>] [--from <n>] [--to <n>] [--lag <n>]
//
// Example (token on command line):
//   jbang run --deps org.postgresql:postgresql:42.7.3 \
//       microchain-cli/src/main/scripts/commit.java \
//       http://localhost:8081/alice/sales \
//       --token my-access-token \
//       --jdbc-url jdbc:postgresql://localhost:5432/mydb \
//       --jdbc-user alice \
//       --jdbc-pass secret
//
// Example (all sensitive values in a local config file):
//   jbang run --deps org.postgresql:postgresql:42.7.3 \
//       microchain-cli/src/main/scripts/commit.java \
//       http://localhost:8081/alice/sales \
//       --local-config ~/.config/crums/sales.json

//REPOS mavenCentral,mavenLocal
//DEPS io.crums:microchain-http:0.1.0-SNAPSHOT
//DEPS info.picocli:picocli:4.7.5

import io.crums.micro.api.ChainInfo;
import io.crums.micro.api.CommitRequest;
import io.crums.micro.api.DbConfig;
import io.crums.micro.api.LocalConfig;
import io.crums.micro.api.SqlConfig;
import io.crums.micro.http.ChainClient;
import io.crums.sldg.HashConflictException;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.sql.SqlLedger;
import io.crums.util.json.JsonParsingException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.time.ZoneId;
import java.util.ArrayList;
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
          "Path to a JDBC driver JAR to load dynamically.",
          "Relative paths are resolved against the --local-config file's directory.",
          "Prefer JBang's --deps for classpath-based driver loading."
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

    // ── load local config (if provided) ────────────────────────────────────
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

    // ── network + DB ───────────────────────────────────────────────────────
    URI base = URI.create(chainUrl.getScheme() + "://" + chainUrl.getAuthority());
    String[] parts = chainUrl.getPath().split("/");
    String username = parts[parts.length - 2];
    String chain    = parts[parts.length - 1];

    ChainClient client = new ChainClient(base, username, chain,
        HttpClient.newHttpClient());

    ChainInfo info = client.getInfo().orElseThrow(() ->
        new IllegalArgumentException("Chain not found: " + chainUrl));

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

    DbConfig cliDb = DbConfig.EMPTY;
    if (jdbcUrlOverride != null)       cliDb = cliDb.withJdbcUrl(jdbcUrlOverride);
    if (jdbcDriverJarOverride != null) cliDb = cliDb.withJdbcDriverJar(jdbcDriverJarOverride.toPath().toAbsolutePath());
    SqlConfig effectiveCfg = sqlCfg.withDbConfig(
        sqlCfg.dbConfig().orElse(DbConfig.EMPTY).merge(cliDb));

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
