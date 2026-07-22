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

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * JDBC connection parameters: URL and optional driver class.
 *
 * <p>Extracted from {@link SqlConfig} so that the connection details can be
 * carried independently — in particular, a {@link LocalConfig} may hold a
 * {@code DbConfig} before its SQL queries are known (the partial / dry-run
 * setup state).</p>
 *
 * @param jdbcUrl         optional JDBC connection URL
 * @param jdbcDriverClass optional fully-qualified JDBC driver class name
 */
public record DbConfig(
    Optional<String> jdbcUrl,
    Optional<String> jdbcDriverClass) {

  /** Empty instance: neither URL nor driver class configured. */
  public static final DbConfig EMPTY = new DbConfig(Optional.empty(), Optional.empty());

  public static final Parser PARSER = new Parser();


  // ── Connection factory ────────────────────────────────────────────────────

  /** Opens a JDBC connection using {@link #jdbcUrl()}; equivalent to {@code openConnection(null)}. */
  public Connection openConnection() throws SQLException {
    return openConnection(null);
  }

  /**
   * Opens a JDBC connection, passing {@code info} to the driver.
   *
   * @param info connection properties (e.g. {@code user}, {@code password}), or {@code null}
   * @throws IllegalStateException if {@link #jdbcUrl()} is not configured
   * @throws SQLException          on connection failure
   */
  public Connection openConnection(Properties info) throws SQLException {
    String url = jdbcUrl.orElseThrow(
        () -> new IllegalStateException("jdbcUrl not configured"));
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


  // ── Wither methods ────────────────────────────────────────────────────────

  public DbConfig withJdbcUrl(String url) {
    return new DbConfig(Optional.of(url), jdbcDriverClass);
  }

  public DbConfig withJdbcDriverClass(String cls) {
    return new DbConfig(jdbcUrl, Optional.of(cls));
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  /**
   * Parses and writes {@code jdbc_url} and {@code jdbc_driver_class} fields
   * (both optional) in a flat JSON object shared with other entities.
   */
  public static class Parser implements JsonEntityParser<DbConfig> {

    @Override
    public JSONObject injectEntity(DbConfig cfg, JSONObject jObj) {
      cfg.jdbcUrl().ifPresent(u -> jObj.put(JDBC_URL_KEY, u));
      cfg.jdbcDriverClass().ifPresent(d -> jObj.put(JDBC_DRIVER_CLASS_KEY, d));
      return jObj;
    }

    @Override
    public DbConfig toEntity(JSONObject jObj) throws JsonParsingException {
      var url = Optional.ofNullable(JsonUtils.getString(jObj, JDBC_URL_KEY, false));
      var cls = Optional.ofNullable(JsonUtils.getString(jObj, JDBC_DRIVER_CLASS_KEY, false));
      return new DbConfig(url, cls);
    }
  }
}
