/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
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
    assertEquals(cfg.jdbcDriverJar(),   cfg2.jdbcDriverJar());
    assertEquals(cfg.jdbcProps(),       cfg2.jdbcProps());
  }


  // ── roundtrips ────────────────────────────────────────────────────────────

  @Test
  public void testRoundtrip_both() {
    DbConfig cfg = new DbConfig(
        Optional.of("jdbc:postgresql://db:5432/mydb"),
        Optional.of("org.postgresql.Driver"),
        Optional.empty(),
        Map.of());
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


  @Test
  public void testRoundtrip_withDriverJar() {
    DbConfig cfg = DbConfig.EMPTY
        .withJdbcUrl("jdbc:postgresql://db:5432/mydb")
        .withJdbcDriverJar(Path.of("drivers/postgresql.jar"));
    assertRoundtrip(cfg);

    JSONObject jObj = PARSER.toJsonObject(cfg);
    assertEquals("drivers/postgresql.jar", jObj.get(JDBC_DRIVER_JAR_KEY),
        "relative path preserved as-is in JSON");
    assertFalse(jObj.containsKey(JDBC_DRIVER_CLASS_KEY), "no driver class key");
  }

  @Test
  public void testRoundtrip_withJdbcProps() {
    DbConfig cfg = DbConfig.EMPTY
        .withJdbcUrl("jdbc:postgresql://db:5432/mydb")
        .withJdbcProps(Map.of("user", "alice", "password", "s3cr3t", "ssl", "true"));
    assertRoundtrip(cfg);

    JSONObject jObj = PARSER.toJsonObject(cfg);
    JsonPrinter.println(jObj);
    assertTrue(jObj.containsKey(JDBC_PROPS_KEY), "jdbc_props key present");
    assertFalse(jObj.containsKey("user"),     "user not hoisted to top level");
    assertFalse(jObj.containsKey("password"), "password not hoisted to top level");
  }

  @Test
  public void testEmptyPropsNotSerialized() {
    DbConfig cfg = DbConfig.EMPTY.withJdbcUrl("jdbc:h2:mem:test");
    JSONObject jObj = PARSER.toJsonObject(cfg);
    assertFalse(jObj.containsKey(JDBC_PROPS_KEY), "no jdbc_props key when map is empty");
  }


  // ── resolve ───────────────────────────────────────────────────────────────

  @Test
  public void testResolve_relativePathMadeAbsolute() {
    DbConfig cfg = DbConfig.EMPTY.withJdbcDriverJar(Path.of("drivers/pg.jar"));
    Path configDir = Path.of("/home/user/config");
    DbConfig resolved = cfg.resolve(configDir);
    assertTrue(resolved.jdbcDriverJar().get().isAbsolute());
    assertEquals(Path.of("/home/user/config/drivers/pg.jar"), resolved.jdbcDriverJar().get());
  }

  @Test
  public void testResolve_absolutePathReturnsSameInstance() {
    DbConfig cfg = DbConfig.EMPTY.withJdbcDriverJar(Path.of("/opt/drivers/pg.jar"));
    assertSame(cfg, cfg.resolve(Path.of("/some/dir")));
  }

  @Test
  public void testResolve_nullConfigDirReturnsSameInstance() {
    DbConfig cfg = DbConfig.EMPTY.withJdbcDriverJar(Path.of("pg.jar"));
    assertSame(cfg, cfg.resolve(null));
  }

  @Test
  public void testResolve_noJarReturnsSameInstance() {
    DbConfig cfg = DbConfig.EMPTY.withJdbcUrl("jdbc:h2:mem:test");
    assertSame(cfg, cfg.resolve(Path.of("/some/dir")));
  }


  // ── merge ─────────────────────────────────────────────────────────────────

  @Test
  public void testMerge_overridesWin() {
    DbConfig base = new DbConfig(
        Optional.of("jdbc:h2:mem:base"),
        Optional.of("org.h2.Driver"),
        Optional.empty(),
        Map.of("ssl", "false"));
    DbConfig overrides = DbConfig.EMPTY
        .withJdbcUrl("jdbc:postgresql://db/prod")
        .withJdbcProps(Map.of("ssl", "true", "user", "alice"));

    DbConfig merged = base.merge(overrides);
    assertEquals("jdbc:postgresql://db/prod", merged.jdbcUrl().orElseThrow());
    assertEquals("org.h2.Driver",             merged.jdbcDriverClass().orElseThrow(), "base driver class kept");
    assertEquals(Map.of("ssl", "true", "user", "alice"), merged.jdbcProps());
  }

  @Test
  public void testMerge_emptyOverrideKeepsBase() {
    DbConfig base = new DbConfig(
        Optional.of("jdbc:h2:mem:test"),
        Optional.of("org.h2.Driver"),
        Optional.empty(),
        Map.of());
    DbConfig merged = base.merge(DbConfig.EMPTY);
    assertEquals(base.jdbcUrl(),         merged.jdbcUrl());
    assertEquals(base.jdbcDriverClass(), merged.jdbcDriverClass());
    assertTrue(merged.jdbcProps().isEmpty());
  }

  @Test
  public void testMerge_jarOverride() {
    DbConfig base     = DbConfig.EMPTY.withJdbcUrl("jdbc:postgresql://db/prod");
    DbConfig override = DbConfig.EMPTY.withJdbcDriverJar(Path.of("/opt/pg.jar"));
    DbConfig merged   = base.merge(override);
    assertEquals(base.jdbcUrl(),           merged.jdbcUrl());
    assertEquals(override.jdbcDriverJar(), merged.jdbcDriverJar());
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
