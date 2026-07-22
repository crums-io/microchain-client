/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonPrinter;
import io.crums.util.json.simple.JSONObject;


/**
 * Roundtrip and error tests for {@link SqlConfig.Parser} and wither methods.
 */
public class SqlConfigParserTest {

  private static final SqlConfig.Parser PARSER = SqlConfig.PARSER;

  static final String BY_NO = "SELECT a, b FROM t WHERE row_no = ?";
  static final String COUNT = "SELECT COUNT(*) FROM t";


  // ── helpers ───────────────────────────────────────────────────────────────

  private static void assertRoundtrip(SqlConfig cfg) {
    JSONObject jObj = PARSER.toJsonObject(cfg);
    SqlConfig cfg2  = PARSER.toEntity(jObj);
    assertEquals(cfg.byNoQuery(),       cfg2.byNoQuery());
    assertEquals(cfg.rowCountQuery(),   cfg2.rowCountQuery());
    assertEquals(cfg.jdbcUrl(),         cfg2.jdbcUrl());
    assertEquals(cfg.jdbcDriverClass(), cfg2.jdbcDriverClass());
    assertEquals(cfg.timezone(),        cfg2.timezone());
    assertEquals(cfg.defaultLag(),      cfg2.defaultLag());
  }


  // ── roundtrips ────────────────────────────────────────────────────────────

  @Test
  public void testRoundtrip_minimal() {
    SqlConfig cfg = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.empty(), Optional.empty());
    assertRoundtrip(cfg);

    JSONObject jObj = PARSER.toJsonObject(cfg);
    JsonPrinter.println(jObj);
    assertFalse(jObj.containsKey(JDBC_URL_KEY),          "no jdbc_url when absent");
    assertFalse(jObj.containsKey(JDBC_DRIVER_CLASS_KEY), "no jdbc_driver_class when absent");
    assertFalse(jObj.containsKey(TIMEZONE_KEY),          "no timezone when absent");
    assertFalse(jObj.containsKey(DEFAULT_LAG_KEY),       "no default_lag when absent");
  }

  @Test
  public void testRoundtrip_withJdbcUrl() {
    SqlConfig cfg = new SqlConfig(BY_NO, COUNT,
        Optional.of(DbConfig.EMPTY.withJdbcUrl("jdbc:postgresql://db:5432/mydb")),
        Optional.empty(), Optional.empty());
    assertRoundtrip(cfg);
    assertTrue(cfg.jdbcUrl().isPresent());
    assertTrue(cfg.jdbcDriverClass().isEmpty());
  }

  @Test
  public void testRoundtrip_withTimezone() {
    SqlConfig cfg = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.of("America/New_York"), Optional.empty());
    assertRoundtrip(cfg);
  }

  @Test
  public void testRoundtrip_withDefaultLag() {
    SqlConfig cfg = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.empty(), Optional.of(5L));
    assertRoundtrip(cfg);
  }

  @Test
  public void testRoundtrip_full() {
    SqlConfig cfg = new SqlConfig(BY_NO, COUNT,
        Optional.of(new DbConfig(
            Optional.of("jdbc:postgresql://db:5432/mydb"),
            Optional.of("org.postgresql.Driver"))),
        Optional.of("America/New_York"),
        Optional.of(3L));
    assertRoundtrip(cfg);
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }


  // ── error cases ───────────────────────────────────────────────────────────

  @Test
  public void testMissingByNoQueryThrows() {
    JSONObject jObj = new JSONObject();
    jObj.put(ROW_COUNT_QUERY_KEY, COUNT);
    assertThrows(JsonParsingException.class, () -> PARSER.toEntity(jObj));
  }

  @Test
  public void testMissingRowCountQueryThrows() {
    JSONObject jObj = new JSONObject();
    jObj.put(BY_NO_QUERY_KEY, BY_NO);
    assertThrows(JsonParsingException.class, () -> PARSER.toEntity(jObj));
  }


  // ── wither methods ────────────────────────────────────────────────────────

  @Test
  public void testWithJdbcUrl_onMinimal() {
    SqlConfig base    = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.empty(), Optional.empty());
    SqlConfig updated = base.withJdbcUrl("jdbc:h2:mem:test");
    assertEquals("jdbc:h2:mem:test", updated.jdbcUrl().orElseThrow());
    assertTrue(updated.jdbcDriverClass().isEmpty());
    assertEquals(base.byNoQuery(), updated.byNoQuery());
  }

  @Test
  public void testWithJdbcDriverClass_onMinimal() {
    SqlConfig base    = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.empty(), Optional.empty());
    SqlConfig updated = base.withJdbcDriverClass("org.h2.Driver");
    assertEquals("org.h2.Driver", updated.jdbcDriverClass().orElseThrow());
    assertTrue(updated.jdbcUrl().isEmpty());
  }

  @Test
  public void testWithJdbcUrl_preservesExistingDriverClass() {
    SqlConfig base = new SqlConfig(BY_NO, COUNT,
        Optional.of(DbConfig.EMPTY.withJdbcDriverClass("org.h2.Driver")),
        Optional.empty(), Optional.empty());
    SqlConfig updated = base.withJdbcUrl("jdbc:h2:mem:test");
    assertEquals("jdbc:h2:mem:test", updated.jdbcUrl().orElseThrow());
    assertEquals("org.h2.Driver",    updated.jdbcDriverClass().orElseThrow());
  }

  @Test
  public void testWithTimezone() {
    SqlConfig cfg = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.empty(), Optional.empty())
        .withTimezone("Europe/London");
    assertEquals("Europe/London", cfg.timezone().orElseThrow());
  }

  @Test
  public void testWithDefaultLag() {
    SqlConfig cfg = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.empty(), Optional.empty())
        .withDefaultLag(10L);
    assertEquals(10L, cfg.defaultLag().orElseThrow());
  }
}
