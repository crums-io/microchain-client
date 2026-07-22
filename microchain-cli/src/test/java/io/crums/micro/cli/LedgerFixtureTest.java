/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.crums.micro.api.SqlConfig;
import io.crums.sldg.SldgConstants;
import io.crums.sldg.src.DataType;
import io.crums.sldg.src.SaltScheme;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.sql.SqlLedger;

/**
 * Exercises {@link SqlLedger} against the H2 fixture under
 * {@code src/test/scripts/sample-data/} -- the same fixture and
 * sql-config queries used by the {@code readLedger.java} JBang script.
 *
 * <p>Covers both a normal row and the intentional {@code si_id = 4} gap
 * in {@code sold_items.csv}, which sql-ledger surfaces as a null row
 * (32 zero-byte hash).</p>
 */
class LedgerFixtureTest {

  private static final Path SAMPLE_DATA = Path.of("src/test/scripts/sample-data");

  /** Cell types of a non-null row, per the row-by-number query's column order:
   * {@code item_count, price, txn_date, cust_guid, prod_guid}. */
  private static final List<DataType> ROW_CELL_TYPES = List.of(
      DataType.LONG, DataType.BIG_DEC, DataType.DATE, DataType.STRING, DataType.STRING);

  private static TimeZone savedTz;

  /**
   * SqlLedger reads DATE cells via {@code ResultSet.getDate()}, whose
   * epoch-millis value (and so the resulting row hash) depends on the
   * JVM default timezone. Pin it to UTC, matching the {@code //JAVA_OPTIONS
   * -Duser.timezone=UTC} pragma in commit.java / readLedger.java.
   */
  @BeforeAll
  static void pinUtc() {
    savedTz = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
  }

  @AfterAll
  static void restoreTz() {
    TimeZone.setDefault(savedTz);
  }

  @Test
  void readsFixtureRows() throws Exception {
    try (Connection conn = DriverManager.getConnection(
        "jdbc:h2:mem:ledger_fixture", "sa", "")) {

      loadFixture(conn);

      SqlConfig cfg = parseQueries(SAMPLE_DATA.resolve("ledger-queries.sql"));

      try (SqlLedger ledger = cfg.open(conn, SaltScheme.NO_SALT)) {
        assertEquals(6L, ledger.size());

        var hashes = new HashSet<ByteBuffer>();
        for (long rn = 1; rn <= ledger.size(); ++rn) {
          SourceRow row = ledger.getSourceRow(rn);
          hashes.add(row.hash());

          if (rn == 4) {
            // si_id = 4 is missing from sold_items.csv -> null row
            assertTrue(row.cells().isEmpty());
            assertEquals(SldgConstants.DIGEST.sentinelHash(), row.hash());
          } else {
            assertEquals(ROW_CELL_TYPES, row.cellTypes());
          }
        }

        // 5 distinct content hashes, plus the sentinel for row [4]
        assertEquals(6, hashes.size());
        assertTrue(hashes.contains(SldgConstants.DIGEST.sentinelHash()));
      }
    }
  }

  private static void loadFixture(Connection conn) throws Exception {
    try (Statement stmt = conn.createStatement()) {
      for (String sql : Files.readString(SAMPLE_DATA.resolve("schema.sql")).split(";")) {
        sql = sql.strip();
        if (!sql.isEmpty())
          stmt.execute(sql);
      }
      for (String table : new String[] {"customers", "products", "sales", "sold_items"}) {
        String csv = SAMPLE_DATA.resolve(table + ".csv")
            .toAbsolutePath().toString().replace("'", "''");
        stmt.executeUpdate("INSERT INTO " + table + " SELECT * FROM CSVREAD('" + csv + "')");
      }
    }
  }

  /**
   * Parses a SQL file containing exactly two ';'-terminated statements --
   * the row-count query, then the row-by-number query -- ignoring lines
   * starting with '--'. Mirrors the parsing in readLedger.java.
   */
  private static SqlConfig parseQueries(Path file) throws Exception {
    List<String> statements = new ArrayList<>();
    for (String chunk : Files.readString(file).split(";")) {
      String sql = chunk.lines()
          .filter(line -> !line.strip().startsWith("--"))
          .reduce("", (a, b) -> a + "\n" + b)
          .strip();
      if (!sql.isEmpty())
        statements.add(sql);
    }
    return new SqlConfig(statements.get(1), statements.get(0),
        Optional.empty(), Optional.empty(), Optional.empty());
  }
}
