/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.cli;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.SaltScheme;
import io.crums.sldg.src.sql.SqlLedger;
import io.crums.util.TaskStack;

/**
 * {@link SqlLedger} subclass that captures JDBC-reported column labels
 * ({@code ResultSetMetaData.getColumnLabel}) as they're read, for use as a
 * naming fallback in {@code mchain rows} pretty-printing when no explicit
 * column metadata (server-configured or local) is available.
 *
 * <p>Labels are captured from whichever row is fetched first and assumed
 * stable thereafter (same query, same columns, every call).</p>
 */
public class LabeledSqlLedger extends SqlLedger {

  private volatile List<String> columnLabels;

  /**
   * Opens a {@code LabeledSqlLedger}; same contract as {@link SqlLedger#open}.
   */
  public static LabeledSqlLedger open(
      Connection connection,
      String sizeQuery,
      String rowByNoQuery,
      SaltScheme saltScheme,
      TableSalt shaker) throws SQLException {
    try (var closeOnFail = new TaskStack()) {
      PreparedStatement sizeStmt = connection.prepareStatement(sizeQuery);
      closeOnFail.pushClose(sizeStmt);
      PreparedStatement rowStmt = connection.prepareStatement(rowByNoQuery);
      closeOnFail.pushClose(rowStmt);
      var ledger = new LabeledSqlLedger(new Config(sizeStmt, rowStmt, saltScheme, shaker));
      closeOnFail.clear();
      return ledger;
    }
  }

  protected LabeledSqlLedger(Config config) {
    super(config);
  }

  @Override
  protected void observeMeta(ResultSetMetaData meta, int colOffset) throws SQLException {
    if (columnLabels != null)
      return;
    int cc = meta.getColumnCount() - colOffset;
    String[] labels = new String[cc];
    for (int i = 0; i < cc; i++)
      labels[i] = meta.getColumnLabel(colOffset + 1 + i);
    columnLabels = List.of(labels);
  }

  /**
   * Returns the JDBC-reported column labels for the row shape most recently
   * read, or empty if no row has been fetched yet.
   */
  public List<String> columnLabels() {
    List<String> labels = columnLabels;
    return labels == null ? List.of() : labels;
  }
}
