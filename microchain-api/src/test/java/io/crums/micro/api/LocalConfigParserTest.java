/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;
import static io.crums.micro.api.SqlConfigParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.crums.sldg.salt.EpochedTableSalt.EpochSeed;
import io.crums.sldg.src.SaltScheme;
import io.crums.testing.IoTestCase;
import io.crums.util.json.JsonPrinter;
import io.crums.util.json.simple.JSONObject;


/**
 * Roundtrip, structural, and wither tests for {@link LocalConfig.Parser}.
 */
public class LocalConfigParserTest extends IoTestCase {

  private static final LocalConfig.Parser PARSER = LocalConfig.PARSER;


  // ── helpers ───────────────────────────────────────────────────────────────

  /** Returns a deterministic 32-byte seed filled with {@code fill}. */
  static byte[] seed(int fill) {
    byte[] b = new byte[32];
    Arrays.fill(b, (byte) fill);
    return b;
  }

  private static void assertSaltEpochsEqual(List<EpochSeed> expected, List<EpochSeed> actual) {
    assertEquals(expected.size(), actual.size(), "epoch count");
    for (int i = 0; i < expected.size(); i++) {
      assertEquals(expected.get(i).startRow(), actual.get(i).startRow(), "startRow[" + i + "]");
      assertArrayEquals(expected.get(i).seed(), actual.get(i).seed(),    "seed[" + i + "]");
    }
  }

  private static void assertRoundtrip(LocalConfig cfg) {
    JSONObject jObj  = PARSER.toJsonObject(cfg);
    LocalConfig cfg2 = PARSER.toEntity(jObj);
    assertEquals(cfg.accessToken(),   cfg2.accessToken());
    assertEquals(cfg.sqlConfig(),     cfg2.sqlConfig());
    assertEquals(cfg.looseDbConfig(), cfg2.looseDbConfig());
    assertSaltEpochsEqual(cfg.saltEpochs(), cfg2.saltEpochs());
    assertSaltSchemeEqual(cfg.saltScheme(), cfg2.saltScheme());
  }

  private static void assertSaltSchemeEqual(
      Optional<SaltScheme> expected, Optional<SaltScheme> actual) {
    assertEquals(expected.isPresent(), actual.isPresent(), "saltScheme presence");
    if (expected.isEmpty()) return;
    SaltScheme e = expected.get(), a = actual.get();
    assertEquals(e.isPositive(), a.isPositive(), "saltScheme isPositive");
    assertArrayEquals(e.cellIndices(), a.cellIndices(), "saltScheme cellIndices");
  }


  // ── roundtrips ────────────────────────────────────────────────────────────

  @Test
  public void testRoundtrip_empty() {
    assertRoundtrip(LocalConfig.EMPTY);
    assertTrue(PARSER.toJsonObject(LocalConfig.EMPTY).isEmpty(),
        "EMPTY serializes to empty JSON object");
  }

  @Test
  public void testRoundtrip_accessToken() {
    LocalConfig cfg = LocalConfig.EMPTY.withAccessToken("mcw_abc123");
    assertRoundtrip(cfg);
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }

  @Test
  public void testRoundtrip_sqlConfigMinimal() {
    SqlConfig sql = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.empty(), Optional.empty());
    LocalConfig cfg = LocalConfig.EMPTY.withSqlConfig(sql);
    assertRoundtrip(cfg);
    assertTrue(cfg.sqlConfig().isPresent());
    assertTrue(cfg.looseDbConfig().isEmpty());
  }

  @Test
  public void testRoundtrip_sqlConfigFull() {
    SqlConfig sql = new SqlConfig(BY_NO, COUNT,
        Optional.of(new DbConfig(
            Optional.of("jdbc:postgresql://db:5432/mydb"),
            Optional.of("org.postgresql.Driver"),
            Optional.empty(),
            Map.of())),
        Optional.of("America/New_York"),
        Optional.of(5L));
    LocalConfig cfg = LocalConfig.EMPTY
        .withAccessToken("mcw_tok")
        .withSqlConfig(sql);
    assertRoundtrip(cfg);
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }

  @Test
  public void testRoundtrip_looseDbConfig() {
    DbConfig db = new DbConfig(
        Optional.of("jdbc:h2:mem:test"),
        Optional.of("org.h2.Driver"),
        Optional.empty(),
        Map.of());
    LocalConfig cfg = LocalConfig.EMPTY.withDbConfig(db);
    assertRoundtrip(cfg);
    assertTrue(cfg.looseDbConfig().isPresent());
    assertTrue(cfg.sqlConfig().isEmpty());
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }

  @Test
  public void testRoundtrip_singleSaltEpoch() {
    LocalConfig cfg = LocalConfig.EMPTY
        .withSaltEpochs(List.of(new EpochSeed(1L, seed(0xAB))));
    assertRoundtrip(cfg);
  }

  @Test
  public void testRoundtrip_multipleEpochs() {
    LocalConfig cfg = LocalConfig.EMPTY.withSaltEpochs(List.of(
        new EpochSeed(  1L, seed(0x11)),
        new EpochSeed(1001L, seed(0x22))));
    assertRoundtrip(cfg);
  }

  @Test
  public void testRoundtrip_full() {
    SqlConfig sql = new SqlConfig(BY_NO, COUNT,
        Optional.of(DbConfig.EMPTY.withJdbcUrl("jdbc:postgresql://db:5432/mydb")),
        Optional.of("UTC"),
        Optional.of(3L));
    LocalConfig cfg = LocalConfig.EMPTY
        .withAccessToken("mcw_full")
        .withSqlConfig(sql)
        .withSaltEpochs(List.of(new EpochSeed(1L, seed(0xFF))));
    assertRoundtrip(cfg);
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }


  // ── partial-state / structural tests ─────────────────────────────────────

  @Test
  public void testPartialState_jdbcUrlWithoutQueries() {
    // jdbc_url in JSON but no SQL queries → parsed as looseDbConfig, not sqlConfig
    JSONObject jObj = new JSONObject();
    jObj.put(JDBC_URL_KEY, "jdbc:h2:mem:partial");

    LocalConfig cfg = PARSER.toEntity(jObj);
    assertTrue(cfg.sqlConfig().isEmpty(),       "no sqlConfig when queries absent");
    assertTrue(cfg.looseDbConfig().isPresent(), "jdbc_url alone → looseDbConfig");
    assertEquals("jdbc:h2:mem:partial",
        cfg.looseDbConfig().get().jdbcUrl().orElseThrow());
  }

  @Test
  public void testPartialState_driverClassWithoutUrl() {
    // jdbc_driver_class alone (no url, no queries) → looseDbConfig
    JSONObject jObj = new JSONObject();
    jObj.put(JDBC_DRIVER_CLASS_KEY, "org.h2.Driver");

    LocalConfig cfg = PARSER.toEntity(jObj);
    assertTrue(cfg.sqlConfig().isEmpty());
    assertTrue(cfg.looseDbConfig().isPresent());
    assertTrue(cfg.looseDbConfig().get().jdbcUrl().isEmpty());
    assertEquals("org.h2.Driver",
        cfg.looseDbConfig().get().jdbcDriverClass().orElseThrow());
  }

  @Test
  public void testPartialState_byNoQueryPresent() {
    // When by_no_query is present, SqlConfig is parsed; looseDbConfig stays empty
    JSONObject jObj = new JSONObject();
    jObj.put(BY_NO_QUERY_KEY,     BY_NO);
    jObj.put(ROW_COUNT_QUERY_KEY, COUNT);
    jObj.put(JDBC_URL_KEY, "jdbc:h2:mem:test");

    LocalConfig cfg = PARSER.toEntity(jObj);
    assertTrue(cfg.sqlConfig().isPresent(),   "sqlConfig when queries present");
    assertTrue(cfg.looseDbConfig().isEmpty(), "no looseDbConfig when sqlConfig present");
    assertEquals("jdbc:h2:mem:test",
        cfg.sqlConfig().get().jdbcUrl().orElseThrow());
  }


  // ── wither routing ────────────────────────────────────────────────────────

  @Test
  public void testWithDbConfig_routesIntoSqlConfig() {
    // withDbConfig() updates within sqlConfig when it is already present
    SqlConfig sql = new SqlConfig(BY_NO, COUNT,
        Optional.empty(), Optional.empty(), Optional.empty());
    LocalConfig cfg = LocalConfig.EMPTY.withSqlConfig(sql)
        .withDbConfig(DbConfig.EMPTY.withJdbcUrl("jdbc:h2:mem:x"));

    assertTrue(cfg.sqlConfig().isPresent());
    assertTrue(cfg.looseDbConfig().isEmpty());
    assertEquals("jdbc:h2:mem:x", cfg.jdbcUrl().orElseThrow());
  }

  @Test
  public void testWithDbConfig_storesLooseWhenNoSqlConfig() {
    LocalConfig cfg = LocalConfig.EMPTY
        .withDbConfig(DbConfig.EMPTY.withJdbcUrl("jdbc:h2:mem:loose"));

    assertTrue(cfg.sqlConfig().isEmpty());
    assertTrue(cfg.looseDbConfig().isPresent());
    assertEquals("jdbc:h2:mem:loose", cfg.jdbcUrl().orElseThrow());
  }

  @Test
  public void testWithSqlConfig_clearsLooseDbConfig() {
    // looseDbConfig is discarded once a full SqlConfig is set
    LocalConfig before = LocalConfig.EMPTY
        .withDbConfig(DbConfig.EMPTY.withJdbcUrl("jdbc:h2:mem:y"));
    assertTrue(before.looseDbConfig().isPresent());

    LocalConfig after = before.withSqlConfig(
        new SqlConfig(BY_NO, COUNT, Optional.empty(), Optional.empty(), Optional.empty()));
    assertTrue(after.sqlConfig().isPresent());
    assertTrue(after.looseDbConfig().isEmpty());
  }

  @Test
  public void testDbConfigResolver() {
    // dbConfig() resolves from sqlConfig when present; falls back to looseDbConfig
    DbConfig loose = DbConfig.EMPTY.withJdbcUrl("jdbc:h2:mem:loose");
    LocalConfig withLoose = LocalConfig.EMPTY.withDbConfig(loose);
    assertEquals(Optional.of(loose), withLoose.dbConfig());

    SqlConfig sql = new SqlConfig(BY_NO, COUNT,
        Optional.of(DbConfig.EMPTY.withJdbcUrl("jdbc:h2:mem:sql")),
        Optional.empty(), Optional.empty());
    LocalConfig withSql = LocalConfig.EMPTY.withSqlConfig(sql);
    assertEquals(sql.dbConfig(), withSql.dbConfig());
  }


  // ── salt scheme ───────────────────────────────────────────────────────────────

  @Test
  public void testRoundtrip_saltSchemeAll() {
    LocalConfig cfg = LocalConfig.EMPTY.withSaltScheme(SaltScheme.SALT_ALL);
    assertRoundtrip(cfg);
    // Verify the JSON contains a salt_scheme object
    JSONObject jObj = PARSER.toJsonObject(cfg);
    assertTrue(jObj.containsKey(SALT_SCHEME_KEY), "salt_scheme key present");
    JsonPrinter.println(jObj);
  }

  @Test
  public void testRoundtrip_saltSchemeNone() {
    // Explicit NO_SALT (as opposed to absent saltScheme)
    LocalConfig cfg = LocalConfig.EMPTY.withSaltScheme(SaltScheme.NO_SALT);
    assertRoundtrip(cfg);
    JSONObject jObj = PARSER.toJsonObject(cfg);
    assertTrue(jObj.containsKey(SALT_SCHEME_KEY), "salt_scheme key present");
  }

  @Test
  public void testRoundtrip_saltSchemeOnlySome() {
    // "none except 1,3" — only columns 1 and 3 are salted (positive, non-empty)
    SaltScheme scheme = SaltScheme.of(1, 3);
    LocalConfig cfg = LocalConfig.EMPTY.withSaltScheme(scheme);
    assertRoundtrip(cfg);
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }

  @Test
  public void testRoundtrip_saltSchemeAllExcept() {
    // "all except 0,2" — all salted except columns 0 and 2 (negative, non-empty)
    SaltScheme scheme = SaltScheme.ofAllExcept(0, 2);
    LocalConfig cfg = LocalConfig.EMPTY.withSaltScheme(scheme);
    assertRoundtrip(cfg);
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }

  @Test
  public void testRoundtrip_saltSchemeWithEpochs() {
    // Salt scheme alongside salt epochs — the combo needed for fully offline rows
    SaltScheme scheme = SaltScheme.SALT_ALL;
    List<EpochSeed> epochs = List.of(new EpochSeed(1L, seed(0xAB)));
    LocalConfig cfg = LocalConfig.EMPTY
        .withSaltScheme(scheme)
        .withSaltEpochs(epochs);
    assertRoundtrip(cfg);
    assertTrue(cfg.saltScheme().isPresent());
    assertFalse(cfg.saltEpochs().isEmpty());
  }

  @Test
  public void testAbsentSaltSchemeNotSerialized() {
    // When saltScheme is absent, no salt_scheme key should appear in JSON
    JSONObject jObj = PARSER.toJsonObject(LocalConfig.EMPTY);
    assertFalse(jObj.containsKey(SALT_SCHEME_KEY), "no salt_scheme key when absent");
  }


  // ── file-loading (Parser.toEntity(File) resolves relative jdbc_driver_jar) ──

  @Test
  public void testToEntityFile_resolvesRelativeDriverJar() throws Exception {
    final Object label = new Object() {};
    File dir = newMethodRunDir(label);

    // Write a config file with a relative jdbc_driver_jar path
    JSONObject jObj = new JSONObject();
    jObj.put(JDBC_URL_KEY,        "jdbc:postgresql://db:5432/mydb");
    jObj.put(JDBC_DRIVER_JAR_KEY, "drivers/pg.jar");
    File cfgFile = new File(dir, "local-config.json");
    Files.writeString(cfgFile.toPath(), JsonPrinter.toJson(jObj));

    LocalConfig cfg = PARSER.toEntity(cfgFile);

    Path jar = cfg.dbConfig()
        .flatMap(DbConfig::jdbcDriverJar)
        .orElseThrow(() -> new AssertionError("jdbcDriverJar not found"));

    assertTrue(jar.isAbsolute(), "path should be absolute after loading from file");
    assertEquals(dir.toPath().resolve("drivers/pg.jar").toAbsolutePath(), jar);
  }

  @Test
  public void testToEntityFile_resolvesJarWithinSqlConfig() throws Exception {
    final Object label = new Object() {};
    File dir = newMethodRunDir(label);

    JSONObject jObj = new JSONObject();
    jObj.put(BY_NO_QUERY_KEY,     BY_NO);
    jObj.put(ROW_COUNT_QUERY_KEY, COUNT);
    jObj.put(JDBC_URL_KEY,        "jdbc:postgresql://db:5432/mydb");
    jObj.put(JDBC_DRIVER_JAR_KEY, "drivers/pg.jar");
    File cfgFile = new File(dir, "local-config.json");
    Files.writeString(cfgFile.toPath(), JsonPrinter.toJson(jObj));

    LocalConfig cfg = PARSER.toEntity(cfgFile);

    assertTrue(cfg.sqlConfig().isPresent(), "sqlConfig should be present");
    Path jar = cfg.sqlConfig().get().dbConfig()
        .flatMap(DbConfig::jdbcDriverJar)
        .orElseThrow(() -> new AssertionError("jdbcDriverJar not found in sqlConfig"));

    assertTrue(jar.isAbsolute(), "path inside sqlConfig should be absolute");
    assertEquals(dir.toPath().resolve("drivers/pg.jar").toAbsolutePath(), jar);
  }
}
