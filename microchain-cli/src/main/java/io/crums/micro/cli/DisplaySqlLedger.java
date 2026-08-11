/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.cli;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.SaltScheme;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.sql.SqlLedger;
import io.crums.util.TaskStack;

/**
 * {@link SqlLedger} subclass that can execute ad-hoc WHERE-clause queries
 * against the source database and return the matching rows as {@link SourceRow}s.
 *
 * <p>Transforms the {@code byNoQuery} — which has the form
 * {@code SELECT <cols> FROM <table> WHERE <pk> = ?} — into a display base query
 * {@code SELECT <pk>, <cols> FROM <table>} so that any user-supplied WHERE
 * constraint can be appended.  The pk column becomes column 1 in the result set,
 * providing the skipledger row number needed to build each {@link SourceRow}.</p>
 */
public class DisplaySqlLedger extends SqlLedger {

  /** Matches: SELECT <select-list+from> WHERE <pk-col> = ?  (trailing whitespace ok) */
  private static final Pattern BY_NO_PATTERN = Pattern.compile(
      "^(\\s*SELECT\\s+)(.+?)\\s+WHERE\\s+([\\w.]+)\\s*=\\s*\\?\\s*$",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private final Connection connection;
  /** Full SQL up to but not including WHERE: {@code SELECT <pk>, <cols+from>} */
  private final String displayQueryBase;

  /**
   * Opens a {@code DisplaySqlLedger} by parsing {@code byNoQuery} to derive
   * the display query base.
   *
   * @param conn        open JDBC connection
   * @param sizeQuery   parameter-free SQL returning the row count
   * @param byNoQuery   single-{@code ?} SQL of the form
   *                    {@code SELECT cols FROM t WHERE pk = ?}
   * @param scheme      salt scheme
   * @param salt        table salt; may be {@code null} when {@code !scheme.hasSalt()}
   * @throws IllegalArgumentException if {@code byNoQuery} does not match the
   *                                  expected single-parameter WHERE pattern
   */
  public static DisplaySqlLedger open(
      Connection conn,
      String sizeQuery,
      String byNoQuery,
      SaltScheme scheme,
      TableSalt salt) throws SQLException {

    Matcher m = BY_NO_PATTERN.matcher(byNoQuery);
    if (!m.matches())
      throw new IllegalArgumentException(
          "byNoQuery does not match expected pattern "
          + "'SELECT ... FROM ... WHERE <col> = ?': " + byNoQuery);

    String selectKw = m.group(1).stripLeading(); // "SELECT "
    String middle   = m.group(2);                // "col1, col2 FROM tbl"
    String pkCol    = m.group(3);                // e.g. "row_no" or "t.row_no"
    // Prepend the pk column so it occupies column 1 in the result set
    String base = selectKw + pkCol + ", " + middle;

    try (var closeOnFail = new TaskStack()) {
      var sizeStmt  = conn.prepareStatement(sizeQuery);
      closeOnFail.pushClose(sizeStmt);
      var byNoStmt  = conn.prepareStatement(byNoQuery);
      closeOnFail.pushClose(byNoStmt);
      var ledger = new DisplaySqlLedger(
          new Config(sizeStmt, byNoStmt, scheme, salt), conn, base);
      closeOnFail.clear();
      return ledger;
    }
  }

  private DisplaySqlLedger(Config config, Connection conn, String displayQueryBase) {
    super(config);
    this.connection       = conn;
    this.displayQueryBase = displayQueryBase;
  }

  /**
   * Returns the display query base (for diagnostics / testing).
   * Example: {@code "SELECT row_no, col1, col2 FROM tbl"}.
   */
  public String displayQueryBase() {
    return displayQueryBase;
  }

  /**
   * Executes a WHERE-clause query and returns matching source rows.
   *
   * <p>The result set is ordered by whatever the database returns; the caller
   * receives rows in that order.  An empty list is returned when nothing
   * matches.</p>
   *
   * @param sqlConstraint  the WHERE predicate; the {@code WHERE} keyword is
   *                       optional and will be stripped if present
   */
  public List<SourceRow> query(String sqlConstraint) throws SQLException {
    String where = sqlConstraint.stripLeading();
    if (where.regionMatches(true, 0, "where ", 0, 6))
      where = where.substring(6).stripLeading();

    String sql = displayQueryBase + " WHERE " + where;
    var results = new ArrayList<SourceRow>();
    try (Statement stmt = connection.createStatement();
         ResultSet rs   = stmt.executeQuery(sql)) {
      while (rs.next()) {
        long rn = rs.getLong(1);
        results.add(buildSourceRowFromResultSet(rs, rn, 1));
      }
    }
    return results;
  }
}
