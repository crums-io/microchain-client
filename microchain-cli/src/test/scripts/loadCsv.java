///usr/bin/env jbang "$0" "$@" ; exit $?
//
// JBang script: load CSV fixtures into a fresh H2 file database, for use as
// a mock SqlLedger source when testing commit.java.
//
// Pins the JVM default timezone to UTC (see commit.java for why this matters):
// SQL DATE/TIMESTAMP columns are read back via ResultSet.getDate(), whose
// resulting epoch-millis value depends on the JVM default timezone. Loading
// and committing under the same pinned timezone keeps row hashes reproducible
// across machines.
//
// Usage:
//   jbang microchain-cli/src/test/scripts/loadCsv.java \
//       --ddl <schema.sql> --db <db-path> \
//       --load <table1>=<csv1> [--load <table2>=<csv2> ...]
//
// Example (using the sample fixture):
//   jbang microchain-cli/src/test/scripts/loadCsv.java \
//       --ddl microchain-cli/src/test/scripts/sample-data/schema.sql \
//       --db /tmp/microchain-test/sales \
//       --load customers=microchain-cli/src/test/scripts/sample-data/customers.csv \
//       --load products=microchain-cli/src/test/scripts/sample-data/products.csv \
//       --load sales=microchain-cli/src/test/scripts/sample-data/sales.csv \
//       --load sold_items=microchain-cli/src/test/scripts/sample-data/sold_items.csv
//
// The DDL file should contain ';'-separated CREATE TABLE statements. For each
// --load table=csv pair, the CSV's column order must match the table's.
// CSVREAD() reads all CSV columns as VARCHAR; H2 implicitly converts them to
// the target column types on INSERT.

//JAVA_OPTIONS -Duser.timezone=UTC

//DEPS com.h2database:h2:2.3.232
//DEPS info.picocli:picocli:4.7.5

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "loadCsv",
    mixinStandardHelpOptions = true,
    description = "Load CSV fixtures into a fresh H2 file database.")
class loadCsv implements Callable<Integer> {

  @Option(names = {"--ddl"}, required = true,
      description = "SQL file with ';'-separated CREATE TABLE statements")
  Path ddl;

  @Option(names = {"--db"}, required = true,
      description = "H2 database file path, without extension (e.g. /tmp/sales)")
  Path db;

  @Option(names = {"--load"}, required = true,
      description = "table=csv-file pair; repeatable. " +
          "CSV column order must match the table's.")
  Map<String, Path> loads = new LinkedHashMap<>();

  public static void main(String[] args) {
    System.exit(new CommandLine(new loadCsv()).execute(args));
  }

  @Override
  public Integer call() throws Exception {
    String url = "jdbc:h2:file:" + db.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(url, "sa", "");
         Statement stmt = conn.createStatement()) {

      for (String sql : Files.readString(ddl).split(";")) {
        sql = sql.strip();
        if (!sql.isEmpty())
          stmt.execute(sql);
      }

      for (var e : loads.entrySet()) {
        String table = e.getKey();
        String csvPath = e.getValue().toAbsolutePath().toString().replace("'", "''");
        int n = stmt.executeUpdate(
            "INSERT INTO " + table + " SELECT * FROM CSVREAD('" + csvPath + "')");
        System.out.println("Loaded " + n + " row(s) into '" + table + "'");
      }

      System.out.println("JDBC URL: " + url);
    }
    return 0;
  }
}
