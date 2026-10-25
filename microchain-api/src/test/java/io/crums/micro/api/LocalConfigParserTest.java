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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.crums.micro.api.LocalConfig.ChainConfig;
import io.crums.micro.api.receipt.ReceiptGeneratorTemplate;
import io.crums.micro.api.receipt.RowSelector;
import io.crums.testing.IoTestCase;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonPrinter;
import io.crums.util.json.simple.JSONArray;
import io.crums.util.json.simple.JSONObject;


/**
 * Roundtrip, structural, and {@code resolve(..)} tests for {@link LocalConfig.Parser}.
 */
public class LocalConfigParserTest extends IoTestCase {

  private static final LocalConfig.Parser PARSER = LocalConfig.PARSER;


  // ── helpers ───────────────────────────────────────────────────────────────

  private static void assertRoundtrip(LocalConfig cfg) {
    JSONObject jObj = PARSER.toJsonObject(cfg);
    LocalConfig cfg2 = PARSER.toEntity(jObj);
    assertEquals(cfg.baseChainUrl(), cfg2.baseChainUrl());
    assertEquals(cfg.accessToken(), cfg2.accessToken());
    assertEquals(cfg.dbConfig(),    cfg2.dbConfig());
    assertEquals(cfg.timezone(),    cfg2.timezone());
    assertEquals(cfg.defaultLag(),  cfg2.defaultLag());
    assertEquals(cfg.chains(),      cfg2.chains());
    assertEquals(cfg.receiptTemplates(), cfg2.receiptTemplates());
  }


  // ── roundtrips ────────────────────────────────────────────────────────────

  @Test
  public void testRoundtrip_minimal() {
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(), DbConfig.EMPTY,
        Optional.empty(), Optional.empty(), Map.of(), Map.of());
    assertRoundtrip(cfg);

    JSONObject jObj = PARSER.toJsonObject(cfg);
    JsonPrinter.println(jObj);
    assertFalse(jObj.containsKey(BASE_CHAIN_URL_KEY));
    assertFalse(jObj.containsKey(ACCESS_TOKEN_KEY));
    assertFalse(jObj.containsKey(TIMEZONE_KEY));
    assertFalse(jObj.containsKey(DEFAULT_LAG_KEY));
    assertTrue(jObj.containsKey(CHAINS_KEY), "'chains' is always written, even when empty");
    assertTrue(((JSONObject) jObj.get(CHAINS_KEY)).isEmpty());
    assertFalse(jObj.containsKey(RECEIPT_TEMPLATES_KEY),
        "'receipt_templates' is omitted entirely when empty");
  }

  @Test
  public void testRoundtrip_sharedDefaultsOnly() {
    LocalConfig cfg = new LocalConfig(
        Optional.of("https://micro.crums.io/bikeshop"),
        Optional.of("mcw_shared"),
        DbConfig.EMPTY.withJdbcUrl("jdbc:sqlite:/data/bikes.db"),
        Optional.of("America/New_York"),
        Optional.of(5L),
        Map.of("orders", ChainConfig.EMPTY),
        Map.of());
    assertRoundtrip(cfg);
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }

  @Test
  public void testRoundtrip_chainOverridesSharedDefault() {
    ChainConfig orders = ChainConfig.EMPTY
        .withChainUrl("https://micro.crums.io/bikeshop/orders");
    ChainConfig customers = ChainConfig.EMPTY
        .withAccessToken("mcw_customers_only"); // overrides the shared token
    LocalConfig cfg = new LocalConfig(
        Optional.of("https://micro.crums.io/bikeshop"),
        Optional.of("mcw_shared"),
        DbConfig.EMPTY,
        Optional.empty(), Optional.empty(),
        orderedChains("orders", orders, "customers", customers),
        Map.of());
    assertRoundtrip(cfg);
  }

  @Test
  public void testRoundtrip_multipleChains_preservesOrder() {
    // Insertion order deliberately not alphabetical, to catch any accidental
    // re-sorting (e.g. from Map.copyOf(..), which does not preserve order).
    Map<String, ChainConfig> chains = orderedChains(
        "zebra",  ChainConfig.EMPTY.withAccessToken("z"),
        "alpha",  ChainConfig.EMPTY.withAccessToken("a"),
        "mango",  ChainConfig.EMPTY.withAccessToken("m"));
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(), DbConfig.EMPTY,
        Optional.empty(), Optional.empty(), chains, Map.of());

    assertEquals(List.of("zebra", "alpha", "mango"), new ArrayList<>(cfg.chainAliases()));

    JSONObject jObj = PARSER.toJsonObject(cfg);
    LocalConfig cfg2 = PARSER.toEntity(jObj);
    assertEquals(List.of("zebra", "alpha", "mango"), new ArrayList<>(cfg2.chainAliases()),
        "chain order must survive an in-memory roundtrip");
  }

  @Test
  public void testRoundtrip_withReceiptGeneratorTemplate() {
    ReceiptGeneratorTemplate template = new ReceiptGeneratorTemplate(List.of(
        new RowSelector("orders", Optional.empty(), List.of()),
        new RowSelector("order_items",
            Optional.of("order_id = ?"), List.of("ROW_NO(:orders)")),
        new RowSelector("customers",
            Optional.of("customer_id = ?"), List.of(":orders.customer_id"))));

    LocalConfig cfg = new LocalConfig(
        Optional.of("https://micro.crums.io/bikeshop"),
        Optional.empty(), DbConfig.EMPTY, Optional.empty(), Optional.empty(),
        orderedChains(
            "orders", ChainConfig.EMPTY,
            "order_items", ChainConfig.EMPTY,
            "customers", ChainConfig.EMPTY),
        Map.of("order-receipt", template));

    assertRoundtrip(cfg);
    JSONObject jObj = PARSER.toJsonObject(cfg);
    JsonPrinter.println(jObj);
    assertTrue(jObj.containsKey(RECEIPT_TEMPLATES_KEY));

    LocalConfig cfg2 = PARSER.toEntity(jObj);
    assertEquals(Set.of("order-receipt"), cfg2.receiptTemplateNames());
    assertEquals(template, cfg2.resolveTemplate("order-receipt"));
  }

  @Test
  public void testRoundtrip_multipleReceiptGeneratorTemplates_preservesOrder() {
    ReceiptGeneratorTemplate t1 = new ReceiptGeneratorTemplate(List.of(
        new RowSelector("orders", Optional.empty(), List.of())));
    ReceiptGeneratorTemplate t2 = new ReceiptGeneratorTemplate(List.of(
        new RowSelector("customers", Optional.empty(), List.of())));

    Map<String, ReceiptGeneratorTemplate> templates = new LinkedHashMap<>();
    templates.put("zebra-receipt", t1);
    templates.put("alpha-receipt", t2);

    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(), DbConfig.EMPTY, Optional.empty(), Optional.empty(),
        orderedChains("orders", ChainConfig.EMPTY, "customers", ChainConfig.EMPTY),
        templates);

    JSONObject jObj = PARSER.toJsonObject(cfg);
    LocalConfig cfg2 = PARSER.toEntity(jObj);
    assertEquals(List.of("zebra-receipt", "alpha-receipt"),
        new ArrayList<>(cfg2.receiptTemplateNames()),
        "receipt template order must survive an in-memory roundtrip");
  }

  @Test
  public void testRoundtrip_full() {
    LocalConfig cfg = new LocalConfig(
        Optional.of("https://micro.crums.io/bikeshop"),
        Optional.of("mcw_shared"),
        DbConfig.EMPTY.withJdbcUrl("jdbc:sqlite:/data/bikes.db")
            .withJdbcDriverJar(java.nio.file.Path.of("drivers/sqlite.jar")),
        Optional.of("UTC"),
        Optional.of(3L),
        orderedChains(
            "orders", ChainConfig.EMPTY.withAccessToken("mcw_orders"),
            "customers", ChainConfig.EMPTY),
        Map.of("order-receipt", new ReceiptGeneratorTemplate(List.of(
            new RowSelector("orders", Optional.empty(), List.of())))));
    assertRoundtrip(cfg);
    JsonPrinter.println(PARSER.toJsonObject(cfg));
  }


  // ── structural / error cases ──────────────────────────────────────────────

  @Test
  public void testMalformedChainsEntryThrows() {
    JSONObject jObj = new JSONObject();
    JSONObject chainsObj = new JSONObject();
    chainsObj.put("orders", "not an object");
    jObj.put(CHAINS_KEY, chainsObj);
    assertThrows(JsonParsingException.class, () -> PARSER.toEntity(jObj));
  }

  @Test
  public void testMalformedReceiptGeneratorTemplatesEntryThrows() {
    JSONObject jObj = new JSONObject();
    JSONObject templatesObj = new JSONObject();
    templatesObj.put("order-receipt", "not an object");
    jObj.put(RECEIPT_TEMPLATES_KEY, templatesObj);
    assertThrows(JsonParsingException.class, () -> PARSER.toEntity(jObj));
  }

  @Test
  public void testWrongTypeChainsThrows() {
    // 'chains' present but not a JSON object at all
    JSONObject jObj = new JSONObject();
    jObj.put(CHAINS_KEY, "not an object");
    assertThrows(JsonParsingException.class, () -> PARSER.toEntity(jObj));
  }

  @Test
  public void testNoChainsOrTemplates_parsesToEmptyMaps() {
    LocalConfig cfg = PARSER.toEntity(new JSONObject());
    assertTrue(cfg.chains().isEmpty());
    assertTrue(cfg.receiptTemplates().isEmpty());
  }


  // ── resolve(String alias) ─────────────────────────────────────────────────

  @Test
  public void testResolve_chainUrlInferredFromBaseChainUrl() {
    LocalConfig cfg = new LocalConfig(
        Optional.of("https://micro.crums.io/bikeshop"),
        Optional.empty(), DbConfig.EMPTY, Optional.empty(), Optional.empty(),
        Map.of("orders", ChainConfig.EMPTY), Map.of());

    ChainConfig resolved = cfg.resolve("orders");
    assertEquals("https://micro.crums.io/bikeshop/orders", resolved.chainUrl().orElseThrow());
  }

  @Test
  public void testResolve_ownChainUrlWinsOverBaseChainUrl() {
    LocalConfig cfg = new LocalConfig(
        Optional.of("https://micro.crums.io/bikeshop"),
        Optional.empty(), DbConfig.EMPTY, Optional.empty(), Optional.empty(),
        Map.of("customers", ChainConfig.EMPTY.withChainUrl("https://micro.crums.io/other/customers")),
        Map.of());

    ChainConfig resolved = cfg.resolve("customers");
    assertEquals("https://micro.crums.io/other/customers", resolved.chainUrl().orElseThrow());
  }

  @Test
  public void testResolve_ownAccessTokenWinsOverSharedDefault() {
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.of("mcw_shared"), DbConfig.EMPTY,
        Optional.empty(), Optional.empty(),
        Map.of("orders", ChainConfig.EMPTY.withAccessToken("mcw_own")),
        Map.of());

    assertEquals("mcw_own", cfg.resolve("orders").accessToken().orElseThrow());
  }

  @Test
  public void testResolve_fallsBackToSharedAccessToken() {
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.of("mcw_shared"), DbConfig.EMPTY,
        Optional.empty(), Optional.empty(),
        Map.of("orders", ChainConfig.EMPTY),
        Map.of());

    assertEquals("mcw_shared", cfg.resolve("orders").accessToken().orElseThrow());
  }

  @Test
  public void testResolve_sqlConfigMergesSharedDbAndTimezoneAndLag() {
    SqlConfig sql = new SqlConfig(BY_NO, COUNT, Optional.empty(), Optional.empty(), Optional.empty());
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(),
        DbConfig.EMPTY.withJdbcUrl("jdbc:sqlite:/shared.db"),
        Optional.of("America/New_York"),
        Optional.of(7L),
        Map.of("orders", ChainConfig.EMPTY.withSqlConfig(sql)),
        Map.of());

    ChainConfig resolved = cfg.resolve("orders");
    assertTrue(resolved.sqlConfig().isPresent());
    assertEquals("jdbc:sqlite:/shared.db", resolved.jdbcUrl().orElseThrow());
    assertEquals("America/New_York", resolved.sqlConfig().get().timezone().orElseThrow());
    assertEquals(7L, resolved.sqlConfig().get().defaultLag().orElseThrow());
  }

  @Test
  public void testResolve_unknownAliasThrows() {
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(), DbConfig.EMPTY,
        Optional.empty(), Optional.empty(), Map.of("orders", ChainConfig.EMPTY), Map.of());
    assertThrows(NoSuchElementException.class, () -> cfg.resolve("no-such-chain"));
  }


  // ── resolveTemplate(String name) ──────────────────────────────────────────

  @Test
  public void testResolveTemplate_found() {
    ReceiptGeneratorTemplate template = new ReceiptGeneratorTemplate(List.of(
        new RowSelector("orders", Optional.empty(), List.of())));
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(), DbConfig.EMPTY, Optional.empty(), Optional.empty(),
        Map.of("orders", ChainConfig.EMPTY),
        Map.of("order-receipt", template));

    assertEquals(template, cfg.resolveTemplate("order-receipt"));
  }

  @Test
  public void testResolveTemplate_unknownThrows() {
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(), DbConfig.EMPTY,
        Optional.empty(), Optional.empty(), Map.of(), Map.of());
    assertThrows(NoSuchElementException.class, () -> cfg.resolveTemplate("no-such-template"));
  }


  // ── resolve(Path configDir) ────────────────────────────────────────────────

  @Test
  public void testResolvePath_resolvesSharedAndPerChainRelativeJar() throws Exception {
    final Object label = new Object() {};
    File dir = newMethodRunDir(label);

    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(),
        DbConfig.EMPTY.withJdbcDriverJar(java.nio.file.Path.of("drivers/shared.jar")),
        Optional.empty(), Optional.empty(),
        Map.of("orders", ChainConfig.EMPTY.withDbConfig(
            DbConfig.EMPTY.withJdbcDriverJar(java.nio.file.Path.of("drivers/orders.jar")))),
        Map.of());

    LocalConfig resolved = cfg.resolve(dir.toPath().toAbsolutePath());

    Path sharedJar = resolved.dbConfig().jdbcDriverJar().orElseThrow();
    assertTrue(sharedJar.isAbsolute());
    assertEquals(dir.toPath().resolve("drivers/shared.jar").toAbsolutePath(), sharedJar);

    Path chainJar = resolved.resolve("orders").dbConfig()
        .flatMap(DbConfig::jdbcDriverJar).orElseThrow();
    assertTrue(chainJar.isAbsolute());
    assertEquals(dir.toPath().resolve("drivers/orders.jar").toAbsolutePath(), chainJar);
  }

  @Test
  public void testResolvePath_preservesReceiptGeneratorTemplates() {
    ReceiptGeneratorTemplate template = new ReceiptGeneratorTemplate(List.of(
        new RowSelector("orders", Optional.empty(), List.of())));
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(), DbConfig.EMPTY, Optional.empty(), Optional.empty(),
        Map.of("orders", ChainConfig.EMPTY),
        Map.of("order-receipt", template));

    LocalConfig resolved = cfg.resolve(Path.of("/some/dir"));
    assertEquals(template, resolved.resolveTemplate("order-receipt"));
  }

  @Test
  public void testToEntityFile_resolvesRelativeDriverJarAndLoadsTemplates() throws Exception {
    final Object label = new Object() {};
    File dir = newMethodRunDir(label);

    JSONObject jObj = new JSONObject();
    jObj.put(JDBC_URL_KEY,        "jdbc:sqlite:/data/bikes.db");
    jObj.put(JDBC_DRIVER_JAR_KEY, "drivers/sqlite.jar");

    JSONObject chainsObj = new JSONObject();
    chainsObj.put("orders", new JSONObject());
    jObj.put(CHAINS_KEY, chainsObj);

    JSONObject templatesObj = new JSONObject();
    JSONObject templateObj = new JSONObject();
    JSONObject selectorObj = new JSONObject();
    selectorObj.put(CHAIN_KEY, "orders");
    var selectorsArr = new JSONArray();
    selectorsArr.add(selectorObj);
    templateObj.put(CHAINS_KEY, selectorsArr);
    templatesObj.put("order-receipt", templateObj);
    jObj.put(RECEIPT_TEMPLATES_KEY, templatesObj);

    File cfgFile = new File(dir, "local-config.json");
    Files.writeString(cfgFile.toPath(), JsonPrinter.toJson(jObj));

    LocalConfig cfg = PARSER.toEntity(cfgFile);

    Path jar = cfg.dbConfig().jdbcDriverJar().orElseThrow();
    assertTrue(jar.isAbsolute(), "jdbc_driver_jar should be absolute after loading from file");
    assertEquals(dir.toPath().resolve("drivers/sqlite.jar").toAbsolutePath(), jar);

    ReceiptGeneratorTemplate template = cfg.resolveTemplate("order-receipt");
    assertEquals(1, template.chains().size());
    assertEquals("orders", template.chains().get(0).chain());
  }


  // ── misc ──────────────────────────────────────────────────────────────────

  @Test
  public void testChainAliasesAndTemplateNames() {
    LocalConfig cfg = new LocalConfig(
        Optional.empty(), Optional.empty(), DbConfig.EMPTY, Optional.empty(), Optional.empty(),
        orderedChains("orders", ChainConfig.EMPTY, "customers", ChainConfig.EMPTY),
        Map.of("order-receipt", new ReceiptGeneratorTemplate(List.of(
            new RowSelector("orders", Optional.empty(), List.of())))));

    assertEquals(Set.of("orders", "customers"), cfg.chainAliases());
    assertEquals(Set.of("order-receipt"), cfg.receiptTemplateNames());
  }


  // ── local helpers ─────────────────────────────────────────────────────────

  /** Builds an order-preserving {@code Map<String, ChainConfig>} from alternating key/value pairs. */
  private static Map<String, ChainConfig> orderedChains(Object... kv) {
    Map<String, ChainConfig> map = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2)
      map.put((String) kv[i], (ChainConfig) kv[i + 1]);
    return map;
  }

}
