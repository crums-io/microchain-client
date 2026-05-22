/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.util.Optional;

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * SQL ledger configuration returned by {@code GET /{user}/{chain}/sql-config}.
 *
 * <p>Maps to {@code ConfigResponse} in {@code SqlLedgerConfigResource}.
 * The JDBC URL and driver class are optional: if not recorded server-side,
 * they must be supplied on the CLI.</p>
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
