/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.crums.util.json.JsonPrinter;
import io.crums.util.json.simple.JSONObject;


/**
 * Roundtrip and structural tests for {@link DbConfig.Parser}.
 */
public class DbConfigParserTest {

  private static final DbConfig.Parser PARSER = DbConfig.PARSER;


  // ── helpers ───────────────────────────────────────────────────────────────

  private static void assertRoundtrip(DbConfig cfg) {
    JSONObject jObj = PARSER.toJsonObject(cfg);
    DbConfig cfg2   = PARSER.toEntity(jObj);
    assertEquals(cfg.jdbcUrl(),         cfg2.jdbcUrl());
    assertEquals(cfg.jdbcDriverClass(), cfg2.jdbcDriverClass());
  }


  // ── roundtrips ────────────────────────────────────────────────────────────

  @Test
  public void testRoundtrip_both() {
    DbConfig cfg = new DbConfig(
        Optional.of("jdbc:postgresql://db:5432/mydb"),
        Optional.of("org.postgresql.Driver"));
    assertRoundtrip(cfg);

    JSONObject jObj = PARSER.toJsonObject(cfg);
    JsonPrinter.println(jObj);
    assertEquals("jdbc:postgresql://db:5432/mydb", jObj.get(JDBC_URL_KEY));
    assertEquals("org.postgresql.Driver",          jObj.get(JDBC_DRIVER_CLASS_KEY));
  }

  @Test
  public void testRoundtrip_urlOnly() {
    DbConfig cfg = DbConfig.EMPTY.withJdbcUrl("jdbc:h2:mem:test");
    assertRoundtrip(cfg);
    assertTrue(cfg.jdbcDriverClass().isEmpty());
  }

  @Test
  public void testRoundtrip_driverClassOnly() {
    DbConfig cfg = DbConfig.EMPTY.withJdbcDriverClass("org.h2.Driver");
    assertRoundtrip(cfg);
    assertTrue(cfg.jdbcUrl().isEmpty());
  }

  @Test
  public void testRoundtrip_empty() {
    assertRoundtrip(DbConfig.EMPTY);
    JSONObject jObj = PARSER.toJsonObject(DbConfig.EMPTY);
    assertTrue(jObj.isEmpty(), "empty DbConfig produces empty JSON object");
  }


  // ── structural ────────────────────────────────────────────────────────────

  @Test
  public void testInjectIntoSharedObject() {
    // Verifies that inject pattern leaves pre-existing fields untouched
    DbConfig cfg = DbConfig.EMPTY
        .withJdbcUrl("jdbc:h2:mem:test")
        .withJdbcDriverClass("org.h2.Driver");
    JSONObject shared = new JSONObject();
    shared.put("other_key", "other_value");
    PARSER.injectEntity(cfg, shared);

    assertEquals("other_value",   shared.get("other_key"),      "pre-existing field preserved");
    assertEquals("jdbc:h2:mem:test", shared.get(JDBC_URL_KEY));
    assertEquals("org.h2.Driver",    shared.get(JDBC_DRIVER_CLASS_KEY));
  }

  @Test
  public void testEmptyDoesNotInjectKeys() {
    JSONObject shared = new JSONObject();
    shared.put("sentinel", 1);
    PARSER.injectEntity(DbConfig.EMPTY, shared);
    assertFalse(shared.containsKey(JDBC_URL_KEY),          "empty: no jdbc_url key");
    assertFalse(shared.containsKey(JDBC_DRIVER_CLASS_KEY), "empty: no jdbc_driver_class key");
    assertEquals(1, shared.size(), "only the sentinel key");
  }
}
