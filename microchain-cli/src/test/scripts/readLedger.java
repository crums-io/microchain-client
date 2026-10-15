///usr/bin/env jbang "$0" "$@" ; exit $?
//
// JBang script: exercise a local SqlLedger against an H2 fixture, printing
// each row's content and hash. Useful for sanity-checking a chain's
// sql-config queries (row-count + row-by-number) before registering them.
//
// Reads the queries from a SQL file containing exactly two ';'-terminated
// statements: the row-count query, then the row-by-number query. Lines
// starting with '--' are treated as comments and ignored.
//
// Pins the JVM default timezone to UTC (see commit.java) so DATE/TIMESTAMP
// cell hashes match those produced when committing.
//
// Usage:
//   jbang microchain-cli/src/test/scripts/readLedger.java \
//       --db <h2-db-path> --queries <ledger-queries.sql>
//
// Example:
//   jbang microchain-cli/src/test/scripts/readLedger.java \
//       --db microchain-client/sandbox/sales \
//       --queries microchain-cli/src/test/scripts/sample-data/ledger-queries.sql

//JAVA_OPTIONS -Duser.timezone=UTC

//REPOS mavenCentral,mavenLocal
//DEPS io.crums:microchain-api:0.1.0-SNAPSHOT
//DEPS com.h2database:h2:2.3.232
//DEPS info.picocli:picocli:4.7.5

import io.crums.micro.api.SqlConfig;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.sql.SqlLedger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
    name = "readLedger",
    mixinStandardHelpOptions = true,
    description = "Exercise a local SqlLedger, printing each row's content and hash.")
class readLedger implements Callable<Integer> {

  @Option(names = {"--db"}, required = true,
      description = "H2 database file path, without extension (e.g. sandbox/sales)")
  Path db;

  @Option(names = {"--queries"}, required = true,
      description = "SQL file with row-count query and row-by-number query, ';'-separated")
  Path queries;

  public static void main(String[] args) {
    System.exit(new CommandLine(new readLedger()).execute(args));
  }

  @Override
  public Integer call() throws Exception {
    List<String> statements = new ArrayList<>();
    for (String chunk : Files.readString(queries).split(";")) {
      String sql = chunk.lines()
          .filter(line -> !line.strip().startsWith("--"))
          .reduce("", (a, b) -> a + "\n" + b)
          .strip();
      if (!sql.isEmpty())
        statements.add(sql);
    }
    if (statements.size() != 2)
      throw new IllegalArgumentException(
          "expected 2 statements (row-count query, row-by-number query) in " +
          queries + ", found " + statements.size());

    String rowCountQuery = statements.get(0);
    String byNoQuery = statements.get(1);

    var cfg = new SqlConfig(byNoQuery, rowCountQuery, Optional.empty(), Optional.empty());
    String url = "jdbc:h2:file:" + db.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(url, "sa", "");
         SqlLedger ledger = cfg.open(conn)) {

      long size = ledger.size();
      System.out.println("Ledger size: " + size);

      for (long rn = 1; rn <= size; rn++) {
        SourceRow row = ledger.getSourceRow(rn);
        String hash = HashEncoding.BASE64_32.encode(row.hash());
        System.out.printf("  row %d: %s  hash=%s%n", rn, row, hash);
      }
    }
    return 0;
  }
}
