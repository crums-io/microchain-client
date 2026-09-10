/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * JDBC connection parameters: URL, optional driver class or driver JAR, and
 * optional connection properties (e.g. {@code user}, {@code password}).
 *
 * <p>Extracted from {@link SqlConfig} so that the connection details can be
 * carried independently — in particular, a {@link LocalConfig.ChainConfig} may hold a
 * {@code DbConfig} before its SQL queries are known (the partial / dry-run
 * setup state).</p>
 *
 * @param jdbcUrl         optional JDBC connection URL
 * @param jdbcDriverClass optional fully-qualified JDBC driver class name;
 *                        used when the driver is already on the classpath
 * @param jdbcDriverJar   optional path to a JDBC driver JAR to load
 *                        dynamically; when present, takes priority over
 *                        {@link #jdbcDriverClass()}; relative paths are
 *                        resolved against the config file's directory at
 *                        connection time
 * @param jdbcProps       optional extra connection properties passed to the
 *                        driver (e.g. {@code user}, {@code password},
 *                        {@code ssl}); stored as an unmodifiable map
 */
public record DbConfig(
    Optional<String>      jdbcUrl,
    Optional<String>      jdbcDriverClass,
    Optional<Path>        jdbcDriverJar,
    Map<String, String>   jdbcProps) {

  public DbConfig {
    jdbcProps = Collections.unmodifiableMap(jdbcProps);
  }

  /** Empty instance: no URL, driver class, driver JAR, or properties configured. */
  public static final DbConfig EMPTY =
      new DbConfig(Optional.empty(), Optional.empty(), Optional.empty(), Map.of());

  public static final Parser PARSER = new Parser();


  // ── Connection factory ────────────────────────────────────────────────────

  /** Opens a JDBC connection; equivalent to {@code openConnection(null)}. */
  public Connection openConnection() throws SQLException {
    return openConnection(null);
  }

  /**
   * Opens a JDBC connection, loading the driver as needed and merging
   * {@link #jdbcProps()} with {@code overrides}.
   *
   * <p>Call {@link #resolve(Path)} first to absolutize any relative
   * {@link #jdbcDriverJar()} path before opening.</p>
   *
   * <p>Driver loading priority:
   * <ol>
   *   <li>{@link #jdbcDriverJar()} — loaded dynamically via the JPMS module layer.</li>
   *   <li>{@link #jdbcDriverClass()} — loaded via {@link Class#forName(String)};
   *       the driver must already be on the classpath.</li>
   *   <li>Neither — relies on {@link DriverManager} auto-detection.</li>
   * </ol>
   *
   * <p>{@code overrides} (typically from CLI flags) takes precedence over
   * {@link #jdbcProps()} when the same key appears in both.</p>
   *
   * @param overrides extra connection properties, or {@code null}
   * @throws IllegalStateException if {@link #jdbcUrl()} is not configured
   * @throws SQLException          on driver loading or connection failure
   */
  public Connection openConnection(Properties overrides) throws SQLException {
    String url = jdbcUrl.orElseThrow(
        () -> new IllegalStateException("jdbcUrl not configured"));

    // Merge: jdbcProps as base, overrides win
    Properties effective = new Properties();
    jdbcProps.forEach(effective::setProperty);
    if (overrides != null)
      overrides.forEach((k, v) -> effective.setProperty((String) k, (String) v));

    if (jdbcDriverJar.isPresent()) {
      // Call driver.connect() directly, bypassing DriverManager entirely.
      // DriverManager.isDriverAllowed() rejects drivers loaded into child
      // module layers via the classloader visibility check, so we avoid it.
      var drivers = loadDriversFromJar(jdbcDriverJar.get());
      for (Driver d : drivers) {
        Connection conn = d.connect(url, effective);
        if (conn != null)
          return readOnly(conn);
      }
      throw new SQLException("No driver in JAR accepted URL: " + url);
    }

    if (jdbcDriverClass.isPresent()) {
      try {
        Class.forName(jdbcDriverClass.get());
      } catch (ClassNotFoundException e) {
        throw new SQLException(
            "JDBC driver class not found: " + jdbcDriverClass.get(), e);
      }
    }

    Connection conn = effective.isEmpty()
        ? DriverManager.getConnection(url)
        : DriverManager.getConnection(url, effective);
    return readOnly(conn);
  }

  // Some drivers (e.g. SQLite) don't allow setReadOnly after connect; ignore.
  private static Connection readOnly(Connection conn) {
    try { conn.setReadOnly(true); } catch (SQLException ignored) {}
    return conn;
  }


  // ── Resolve and merge ─────────────────────────────────────────────────────

  /**
   * Returns a copy of this config with any relative {@link #jdbcDriverJar()}
   * path resolved against {@code configDir}.  Returns {@code this} unchanged
   * when the path is absent, already absolute, or {@code configDir} is {@code null}.
   */
  public DbConfig resolve(Path configDir) {
    if (jdbcDriverJar.isEmpty() || configDir == null) return this;
    Path jar = jdbcDriverJar.get();
    return jar.isAbsolute() ? this : withJdbcDriverJar(configDir.resolve(jar));
  }

  /**
   * Returns a copy of this config with non-empty fields from {@code overrides}
   * replacing the corresponding fields in this instance.  Empty fields in
   * {@code overrides} leave this instance's values intact.
   *
   * <p>{@link #jdbcProps()} follows the same rule: an empty map in
   * {@code overrides} leaves the base map unchanged.</p>
   */
  public DbConfig merge(DbConfig o) {
    return new DbConfig(
        o.jdbcUrl.isPresent()         ? o.jdbcUrl         : jdbcUrl,
        o.jdbcDriverClass.isPresent() ? o.jdbcDriverClass : jdbcDriverClass,
        o.jdbcDriverJar.isPresent()   ? o.jdbcDriverJar   : jdbcDriverJar,
        o.jdbcProps.isEmpty()         ? jdbcProps          : o.jdbcProps);
  }

  // Cache: defineModulesWithOneLoader(..) creates a new ClassLoader (and thus
  // reloads the driver's native library, e.g. sqlite-jdbc) on every call;
  // keyed by absolute path so distinct jars still load independently.
  // Values are weakly held so the cache self-releases: once nothing (e.g. no
  // open Connection, which keeps its defining ClassLoader reachable) still
  // needs the loaded drivers, the entry can be collected without an explicit
  // close/shutdown call — java.sql.Driver has no such method anyway.
  private static final Map<Path, java.lang.ref.WeakReference<List<Driver>>> DRIVER_JAR_CACHE =
      new java.util.HashMap<>();

  /** Loads (and caches, per jar) all JDBC drivers from {@code jar} via a child JPMS module layer. */
  private static List<Driver> loadDriversFromJar(Path jar) throws SQLException {
    Path key = jar.toAbsolutePath().normalize();
    synchronized (DRIVER_JAR_CACHE) {
      var ref = DRIVER_JAR_CACHE.get(key);
      List<Driver> cached = ref == null ? null : ref.get();
      if (cached != null)
        return cached;
      List<Driver> drivers = loadDriversFromJarUncached(key);
      DRIVER_JAR_CACHE.put(key, new java.lang.ref.WeakReference<>(drivers));
      return drivers;
    }
  }

  private static List<Driver> loadDriversFromJarUncached(Path jar) throws SQLException {
    var moduleFinder = ModuleFinder.of(jar);
    var moduleNames  = moduleFinder.findAll().stream()
        .map(ModuleReference::descriptor)
        .map(ModuleDescriptor::name)
        .collect(Collectors.toSet());

    var bootLayer = ModuleLayer.boot();
    var config    = bootLayer.configuration()
        .resolve(moduleFinder, ModuleFinder.of(), moduleNames);
    var driverLayer = bootLayer.defineModulesWithOneLoader(
        config, ClassLoader.getSystemClassLoader());

    var drivers = ServiceLoader.load(driverLayer, Driver.class)
        .stream()
        .map(ServiceLoader.Provider::get)
        .toList();

    if (drivers.isEmpty())
      throw new SQLException("No JDBC driver found in JAR: " + jar);

    return drivers;
  }


  // ── Wither methods ────────────────────────────────────────────────────────

  public DbConfig withJdbcUrl(String url) {
    return new DbConfig(Optional.of(url), jdbcDriverClass, jdbcDriverJar, jdbcProps);
  }

  public DbConfig withJdbcDriverClass(String cls) {
    return new DbConfig(jdbcUrl, Optional.of(cls), jdbcDriverJar, jdbcProps);
  }

  public DbConfig withJdbcDriverJar(Path jar) {
    return new DbConfig(jdbcUrl, jdbcDriverClass, Optional.of(jar), jdbcProps);
  }

  public DbConfig withJdbcProps(Map<String, String> props) {
    return new DbConfig(jdbcUrl, jdbcDriverClass, jdbcDriverJar, props);
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  /**
   * Parses and writes {@code jdbc_url}, {@code jdbc_driver_class},
   * {@code jdbc_driver_jar}, and {@code jdbc_props} fields (all optional)
   * in a flat JSON object shared with other entities.
   *
   * <p>The {@code jdbc_driver_jar} value is stored as a plain string so that
   * relative paths are preserved exactly as written.</p>
   */
  public static class Parser implements JsonEntityParser<DbConfig> {

    @Override
    public JSONObject injectEntity(DbConfig cfg, JSONObject jObj) {
      cfg.jdbcUrl().ifPresent(u -> jObj.put(JDBC_URL_KEY, u));
      cfg.jdbcDriverClass().ifPresent(d -> jObj.put(JDBC_DRIVER_CLASS_KEY, d));
      cfg.jdbcDriverJar().ifPresent(j -> jObj.put(JDBC_DRIVER_JAR_KEY, j.toString()));
      if (!cfg.jdbcProps().isEmpty()) {
        JSONObject props = new JSONObject();
        cfg.jdbcProps().forEach(props::put);
        jObj.put(JDBC_PROPS_KEY, props);
      }
      return jObj;
    }

    @SuppressWarnings("unchecked")
    @Override
    public DbConfig toEntity(JSONObject jObj) throws JsonParsingException {
      var url = Optional.ofNullable(JsonUtils.getString(jObj, JDBC_URL_KEY, false));
      var cls = Optional.ofNullable(JsonUtils.getString(jObj, JDBC_DRIVER_CLASS_KEY, false));
      var jarStr = JsonUtils.getString(jObj, JDBC_DRIVER_JAR_KEY, false);
      var jar = jarStr != null ? Optional.of(Path.of(jarStr)) : Optional.<Path>empty();
      Map<String, String> props = Map.of();
      Object propsVal = jObj.get(JDBC_PROPS_KEY);
      if (propsVal instanceof JSONObject propsObj) {
        var map = new java.util.HashMap<String, String>();
        propsObj.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
        props = map;
      }
      return new DbConfig(url, cls, jar, props);
    }
  }
}
