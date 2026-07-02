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
//       [--jdbc-url <url>] [--jdbc-user <user>] [--jdbc-pass <pass>] [--batch <n>]
//
// Example:
//   jbang run --deps org.postgresql:postgresql:42.7.3 \
//       microchain-cli/src/main/scripts/commit.java \
//       http://localhost:8081/alice/sales \
//       --token my-access-token \
//       --jdbc-url jdbc:postgresql://localhost:5432/mydb \
//       --jdbc-user alice \
//       --jdbc-pass secret

// SqlLedger reads SQL DATE/TIMESTAMP cells via ResultSet.getDate(), whose
// resulting epoch-millis value depends on the JVM default timezone. Pin it
// to UTC so row hashes are reproducible across machines (see loadCsv.java
// under src/test/scripts, which pins the same setting when building test
// fixtures).
//JAVA_OPTIONS -Duser.timezone=UTC

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
import java.util.ArrayList;
import java.util.Optional;
import java.util.Properties;
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
        "  5. Open local SqlLedger, hash rows N+1 .. M",
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

  @Option(names = {"--from"},
      description = {
          "First row to hash and submit.",
          "Defaults to the current chain length (re-submits the last committed",
          "row as a minimal overlap/sanity check before appending new rows).",
          "Set lower to widen the overlap, or to 1 for a full re-hash."
      })
  Long fromOverride;

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
    URI base = URI.create(chainUrl.getScheme() + "://" + chainUrl.getAuthority());
    String[] parts = chainUrl.getPath().split("/");
    String username = parts[parts.length - 2];
    String chain    = parts[parts.length - 1];

    ChainClient client = new ChainClient(base, username, chain,
        HttpClient.newHttpClient());

    // 1. chain metadata
    ChainInfo info = client.getInfo().orElseThrow(() ->
        new IllegalArgumentException("Chain not found: " + chainUrl));

    // 2. salt (only if scheme uses salt)
    TableSalt salt = info.saltScheme().hasSalt()
        ? client.getSalts(token)
        : null;

    // 3. SQL config
    SqlConfig sqlCfg = client.getSqlConfig(token);

    // 4. current committed row count
    long committed = client.getState()
        .map(p -> p.hiRowNumber())
        .orElse(0L);

    // override JDBC URL if supplied on command line
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

    // 5-6. open ledger, hash and commit
    try (Connection conn = effectiveCfg.openConnection(props.isEmpty() ? null : props);
         SqlLedger ledger = salt != null
             ? effectiveCfg.open(conn, info.saltScheme(), salt)
             : effectiveCfg.open(conn, info.saltScheme())) {

      long total = ledger.size();
      long from = fromOverride != null ? fromOverride
          : (committed > 0 ? committed : 1);

      if (from > total) {
        System.out.println("Nothing to commit.  " +
            "Chain: " + committed + " row(s)  Source: " + total + " row(s)");
        return 0;
      }

      System.out.println("Chain committed: " + committed +
          "  Source rows: " + total +
          "  Submitting from row: " + from);
      while (from <= total) {
        long to = Math.min(from + batch - 1, total);
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
