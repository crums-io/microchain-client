/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.sql.Connection;
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
 * The JDBC connection details are grouped in an optional {@link DbConfig};
 * if not recorded server-side they must be supplied locally (via
 * {@link LocalConfig.ChainConfig} or the {@code --jdbc-url} flag).</p>
 *
 * <h2>Opening an SqlLedger</h2>
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
 * @param byNoQuery     parameterised SQL SELECT returning one row by its 1-based row number
 * @param rowCountQuery parameter-free SQL SELECT returning the current row count
 * @param dbConfig      optional JDBC connection parameters ({@link DbConfig})
 * @param timezone      optional IANA timezone for DATE/TIMESTAMP hashing
 * @param defaultLag    optional default number of trailing rows to withhold per commit
 */
public record SqlConfig(
    String             byNoQuery,
    String             rowCountQuery,
    Optional<DbConfig> dbConfig,
    Optional<String>   timezone,
    Optional<Long>     defaultLag) {

  public static final Parser PARSER = new Parser();

  public SqlConfig {
    byNoQuery = byNoQuery.strip();
    rowCountQuery = rowCountQuery.strip();
    if (byNoQuery.isEmpty())
      throw new IllegalArgumentException("byNoQuery is empty");
    if (rowCountQuery.isEmpty())
      throw new IllegalArgumentException("rowCountQuery is empty");
  }


  // ── Convenience delegation to DbConfig ───────────────────────────────────

  public Optional<String> jdbcUrl() {
    return dbConfig.flatMap(DbConfig::jdbcUrl);
  }

  public Optional<String> jdbcDriverClass() {
    return dbConfig.flatMap(DbConfig::jdbcDriverClass);
  }


  // ── Connection factory ────────────────────────────────────────────────────

  /** Opens a JDBC connection; equivalent to {@code openConnection(null)}. */
  public Connection openConnection() throws SQLException {
    return openConnection(null);
  }

  /**
   * Opens a JDBC connection via {@link DbConfig#openConnection(Properties)}.
   *
   * @param info connection properties, or {@code null}
   * @throws IllegalStateException if no {@link DbConfig} is configured
   * @throws SQLException          on connection failure
   */
  public Connection openConnection(Properties info) throws SQLException {
    return dbConfig.orElseThrow(
        () -> new IllegalStateException("No database connection configured in SqlConfig"))
        .openConnection(info);
  }


  // ── SqlLedger factory ─────────────────────────────────────────────────────

  /**
   * Opens a salted {@link SqlLedger} on the given connection.
   *
   * @param connection open JDBC connection (read-only recommended)
   * @param saltScheme the chain's salt scheme, from {@link ChainInfo#saltScheme()}
   * @param shaker     the chain's table salt; may be {@code null} when
   *                   {@code !saltScheme.hasSalt()}
   */
  public SqlLedger open(
      Connection connection, SaltScheme saltScheme, TableSalt shaker)
      throws SQLException {
    return SqlLedger.open(connection, rowCountQuery(), byNoQuery(), saltScheme, shaker);
  }

  /** Opens an unsalted {@link SqlLedger}; convenience for {@code !saltScheme.hasSalt()}. */
  public SqlLedger open(Connection connection, SaltScheme saltScheme) throws SQLException {
    return open(connection, saltScheme, null);
  }


  // ── Wither methods ────────────────────────────────────────────────────────

  public SqlConfig withByNoQuery(String q) {
    return new SqlConfig(q, rowCountQuery, dbConfig, timezone, defaultLag);
  }

  public SqlConfig withRowCountQuery(String q) {
    return new SqlConfig(byNoQuery, q, dbConfig, timezone, defaultLag);
  }

  public SqlConfig withDbConfig(DbConfig db) {
    return new SqlConfig(byNoQuery, rowCountQuery, Optional.of(db), timezone, defaultLag);
  }

  public SqlConfig withJdbcUrl(String url) {
    DbConfig db = dbConfig.orElse(DbConfig.EMPTY).withJdbcUrl(url);
    return new SqlConfig(byNoQuery, rowCountQuery, Optional.of(db), timezone, defaultLag);
  }

  public SqlConfig withJdbcDriverClass(String cls) {
    DbConfig db = dbConfig.orElse(DbConfig.EMPTY).withJdbcDriverClass(cls);
    return new SqlConfig(byNoQuery, rowCountQuery, Optional.of(db), timezone, defaultLag);
  }

  public SqlConfig withTimezone(String tz) {
    return new SqlConfig(byNoQuery, rowCountQuery, dbConfig, Optional.of(tz), defaultLag);
  }

  public SqlConfig withDefaultLag(long lag) {
    return new SqlConfig(byNoQuery, rowCountQuery, dbConfig, timezone, Optional.of(lag));
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<SqlConfig> {

    @Override
    public JSONObject injectEntity(SqlConfig cfg, JSONObject jObj) {
      jObj.put(BY_NO_QUERY_KEY,     cfg.byNoQuery());
      jObj.put(ROW_COUNT_QUERY_KEY, cfg.rowCountQuery());
      cfg.dbConfig().ifPresent(db -> DbConfig.PARSER.injectEntity(db, jObj));
      cfg.timezone().ifPresent(tz  -> jObj.put(TIMEZONE_KEY,    tz));
      cfg.defaultLag().ifPresent(l -> jObj.put(DEFAULT_LAG_KEY, l));
      return jObj;
    }

    @Override
    public SqlConfig toEntity(JSONObject jObj) throws JsonParsingException {
      var byNoQuery     = JsonUtils.getString(jObj, BY_NO_QUERY_KEY,     true);
      var rowCountQuery = JsonUtils.getString(jObj, ROW_COUNT_QUERY_KEY, true);
      DbConfig db = DbConfig.PARSER.toEntity(jObj);
      Optional<DbConfig> dbConfig = (db.jdbcUrl().isPresent() || db.jdbcDriverClass().isPresent())
          ? Optional.of(db) : Optional.empty();
      var timezone   = Optional.ofNullable(JsonUtils.getString(jObj, TIMEZONE_KEY, false));
      Optional<Long> defaultLag = Optional.empty();
      Object lagVal = jObj.get(DEFAULT_LAG_KEY);
      if (lagVal instanceof Number n) defaultLag = Optional.of(n.longValue());
      try {
        return new SqlConfig(byNoQuery, rowCountQuery, dbConfig, timezone, defaultLag);
      } catch (Exception x) {
        throw new JsonParsingException(x);
      }
    }
  }
}
