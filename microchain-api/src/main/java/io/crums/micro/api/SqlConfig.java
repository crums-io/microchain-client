/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.SaltScheme;
import io.crums.sldg.src.sql.SqlLedger;
import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * SQL ledger configuration returned by {@code GET /{user}/{chain}/sql-config}.
 *
 * <p>Maps to {@code ConfigResponse} in {@code SqlLedgerConfigResource}.
 * The JDBC URL and driver class are optional: if not recorded server-side,
 * they must be supplied by the caller.</p>
 *
 * <h2>Opening an SqlLedger</h2>
 * <p>Given responses from the three read-only chain endpoints, a client-side
 * {@link SqlLedger} can be opened in a few lines:</p>
 * <pre>{@code
 * SqlConfig  cfg    = SqlConfig.PARSER.toEntity(sqlConfigJson);
 * SaltScheme scheme = ChainInfo.PARSER.toEntity(infoJson).saltScheme();
 * TableSalt  salt   = TableSaltReader.READER.toTableSalt(saltsArr);
 *
 * try (Connection conn   = cfg.openConnection();
 *      SqlLedger  ledger = cfg.open(conn, scheme, salt)) {
 *     // read rows via ledger.getSourceRow(rowNumber)
 * }
 * }</pre>
 *
 * @param byNoQuery       parameterised SQL SELECT returning one row by its 1-based row number
 * @param rowCountQuery   parameter-free SQL SELECT returning the maximum available row number
 * @param jdbcUrl         optional JDBC connection URL
 * @param jdbcDriverClass optional fully-qualified JDBC driver class name
 */
public record SqlConfig(
    String byNoQuery,
    String rowCountQuery,
    Optional<String> jdbcUrl,
    Optional<String> jdbcDriverClass) {

  public static final Parser PARSER = new Parser();

  public SqlConfig {
    byNoQuery = byNoQuery.strip();
    rowCountQuery = rowCountQuery.strip();
    if (byNoQuery.isEmpty())
      throw new IllegalArgumentException("byNoQuery is empty");
    if (rowCountQuery.isEmpty())
      throw new IllegalArgumentException("rowCountQuery is empty");
  }


  // ── Connection factory ────────────────────────────────────────────────────

  /**
   * Opens a read-only JDBC connection using {@link #jdbcUrl()} and, if
   * present, {@link #jdbcDriverClass()}.
   *
   * @throws IllegalStateException if {@code jdbcUrl} is not configured
   * @throws SQLException          on connection failure
   */
  public Connection openConnection() throws SQLException {
    return openConnection(null);
  }

  /**
   * Opens a read-only JDBC connection, passing {@code info} to the driver.
   *
   * <p>Use this overload to supply credentials or other driver-specific
   * properties (typically {@code user} and {@code password} keys in the
   * {@code Properties} map). Pass {@code null} when no extra properties
   * are needed.</p>
   *
   * <p>The driver class is loaded only when {@link #jdbcDriverClass()} is
   * present; many modern drivers self-register via {@link java.util.ServiceLoader}
   * and do not require explicit loading.</p>
   *
   * @param info connection properties, or {@code null} for none
   * @throws IllegalStateException if {@code jdbcUrl} is not configured
   * @throws SQLException          if the driver class cannot be loaded, or
   *                               the connection cannot be established
   */
  public Connection openConnection(Properties info) throws SQLException {
    String url = jdbcUrl.orElseThrow(
        () -> new IllegalStateException("jdbcUrl not configured in SqlConfig"));
    if (jdbcDriverClass.isPresent()) {
      try {
        Class.forName(jdbcDriverClass.get());
      } catch (ClassNotFoundException e) {
        throw new SQLException(
            "JDBC driver class not found: " + jdbcDriverClass.get(), e);
      }
    }
    Connection conn = (info == null || info.isEmpty())
        ? DriverManager.getConnection(url)
        : DriverManager.getConnection(url, info);
    conn.setReadOnly(true);
    return conn;
  }


  // ── SqlLedger factory ─────────────────────────────────────────────────────

  /**
   * Opens a salted {@link SqlLedger} on the given connection.
   *
   * @param connection  open JDBC connection (read-only recommended)
   * @param saltScheme  the chain's salt scheme, from {@link ChainInfo#saltScheme()}
   * @param shaker      the chain's table salt, from
   *                    {@link io.crums.sldg.src.json.TableSaltReader#READER};
   *                    may be {@code null} when {@code !saltScheme.hasSalt()}
   * @throws SQLException on SQL preparation error
   */
  public SqlLedger open(
      Connection connection, SaltScheme saltScheme, TableSalt shaker)
      throws SQLException {
    return SqlLedger.open(connection, rowCountQuery(), byNoQuery(), saltScheme, shaker);
  }

  /**
   * Opens an unsalted {@link SqlLedger} on the given connection.
   *
   * <p>Convenience for chains where {@code saltScheme.hasSalt()} is {@code false}.</p>
   *
   * @param connection  open JDBC connection (read-only recommended)
   * @param saltScheme  the chain's salt scheme; must satisfy {@code !saltScheme.hasSalt()}
   * @throws SQLException on SQL preparation error
   */
  public SqlLedger open(Connection connection, SaltScheme saltScheme)
      throws SQLException {
    return open(connection, saltScheme, null);
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<SqlConfig> {

    @Override
    public JSONObject injectEntity(SqlConfig cfg, JSONObject jObj) {
      jObj.put(BY_NO_QUERY_KEY,     cfg.byNoQuery());
      jObj.put(ROW_COUNT_QUERY_KEY, cfg.rowCountQuery());
      cfg.jdbcUrl().ifPresent(u -> jObj.put(JDBC_URL_KEY, u));
      cfg.jdbcDriverClass().ifPresent(d -> jObj.put(JDBC_DRIVER_CLASS_KEY, d));
      return jObj;
    }

    @Override
    public SqlConfig toEntity(JSONObject jObj) throws JsonParsingException {
      var byNoQuery     = JsonUtils.getString(jObj, BY_NO_QUERY_KEY,     true);
      var rowCountQuery = JsonUtils.getString(jObj, ROW_COUNT_QUERY_KEY, true);
      var jdbcUrl       = Optional.ofNullable(
          JsonUtils.getString(jObj, JDBC_URL_KEY, false));
      var jdbcDriverClass = Optional.ofNullable(
          JsonUtils.getString(jObj, JDBC_DRIVER_CLASS_KEY, false));
      try {
        return new SqlConfig(byNoQuery, rowCountQuery, jdbcUrl, jdbcDriverClass);
      } catch (Exception x) {
        throw new JsonParsingException(x);
      }
    }
  }
}
