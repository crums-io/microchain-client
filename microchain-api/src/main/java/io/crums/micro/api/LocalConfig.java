/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.crums.sldg.salt.EpochedTableSalt;
import io.crums.sldg.salt.EpochedTableSalt.EpochSeed;
import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.SaltScheme;
import io.crums.sldg.src.json.EpochSeedParser;
import io.crums.sldg.src.json.SaltSchemeParser;
import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONArray;
import io.crums.util.json.simple.JSONObject;

/**
 * Local configuration file for microchain CLI tools.
 *
 * <p>Combines the server-side {@code /sql-config} and {@code /salts} responses,
 * plus the chain's bearer access token, in a single JSON file.  This lets a
 * chain owner operate without storing sensitive data on the server, and also
 * supports dry-run workflow validation before registering queries on the website.</p>
 *
 * <h2>File format</h2>
 * <p>All fields are optional.  Fields absent from the file are fetched from the
 * server instead (where applicable).  CLI flags always take precedence.</p>
 * <pre>{@code
 * {
 *   "access_token":      "mcw_...",
 *   "by_no_query":       "SELECT ... WHERE row_no = ?",
 *   "row_count_query":   "SELECT MAX(row_no) FROM ...",
 *   "jdbc_url":          "jdbc:postgresql://...",
 *   "jdbc_driver_class": "org.postgresql.Driver",
 *   "timezone":          "America/New_York",
 *   "default_lag":       5,
 *   "salt_scheme":       { "salt_code": 0, "cell_indices": [] },
 *   "salts": [
 *     { "seedStart": 1, "seed": "<43-char Base64_32>" }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Partial state</h2>
 * <p>Before both SQL queries are known, a {@link SqlConfig} cannot be
 * constructed.  In that intermediate state, JDBC connection details are held
 * in {@link #looseDbConfig()}.  Use the resolving method {@link #dbConfig()}
 * to retrieve whichever source is active without caring which it is.  Exactly
 * one of {@link #sqlConfig()} and {@link #looseDbConfig()} is non-empty at
 * any given time.</p>
 *
 * @param chainUrl      full chain URL ({@code <server>/{user}/{chain}}); when
 *                      present, the {@code <chain-url>} CLI argument may be omitted
 * @param accessToken   bearer access token; overrides {@code --token} when not
 *                      also given on the command line
 * @param sqlConfig     complete SQL ledger configuration; present when both SQL
 *                      queries are configured — skips {@code GET /sql-config}
 * @param looseDbConfig JDBC connection details held before SQL queries are known;
 *                      empty when {@code sqlConfig} is present
 * @param saltEpochs    epoch seeds from the {@code salts} array; empty when the
 *                      key is absent — skips {@code GET /salts} when non-empty
 * @param saltScheme    cell salting scheme; when present alongside
 *                      {@link #saltEpochs()}, enables fully offline operation
 *                      without fetching {@code GET /info} from the server
 */
public record LocalConfig(
    Optional<String>     chainUrl,
    Optional<String>     accessToken,
    Optional<SqlConfig>  sqlConfig,
    Optional<DbConfig>   looseDbConfig,
    List<EpochSeed>      saltEpochs,
    Optional<SaltScheme> saltScheme) {

  /** Sentinel representing an entirely absent local config. */
  public static final LocalConfig EMPTY = new LocalConfig(
      Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
      List.of(), Optional.empty());

  public static final Parser PARSER = new Parser();

  public LocalConfig {
    saltEpochs = List.copyOf(saltEpochs);
  }


  // ── Resolving convenience methods ─────────────────────────────────────────

  /**
   * Resolves {@link DbConfig} from {@link #sqlConfig()} when present,
   * falling back to {@link #looseDbConfig()}.
   */
  public Optional<DbConfig> dbConfig() {
    return sqlConfig.flatMap(SqlConfig::dbConfig).or(() -> looseDbConfig);
  }

  /**
   * Convenience: resolves the JDBC URL through {@link #dbConfig()}.
   */
  public Optional<String> jdbcUrl() {
    return dbConfig().flatMap(DbConfig::jdbcUrl);
  }

  /**
   * Returns a {@link TableSalt} assembled from {@link #saltEpochs()},
   * or empty when no salt epochs are configured.
   */
  public Optional<TableSalt> tableSalt() {
    return saltEpochs.isEmpty()
        ? Optional.empty()
        : Optional.of(EpochedTableSalt.createTableSalt(saltEpochs));
  }


  // ── Wither methods ────────────────────────────────────────────────────────

  public LocalConfig withChainUrl(String url) {
    return new LocalConfig(Optional.of(url), accessToken, sqlConfig, looseDbConfig, saltEpochs, saltScheme);
  }

  public LocalConfig withAccessToken(String token) {
    return new LocalConfig(chainUrl, Optional.of(token), sqlConfig, looseDbConfig, saltEpochs, saltScheme);
  }

  /**
   * Sets a complete {@link SqlConfig}, clearing {@link #looseDbConfig()}
   * (its values are now embedded in {@code cfg}).
   */
  public LocalConfig withSqlConfig(SqlConfig cfg) {
    return new LocalConfig(chainUrl, accessToken, Optional.of(cfg), Optional.empty(), saltEpochs, saltScheme);
  }

  /**
   * Updates the JDBC connection details: within {@link #sqlConfig()} if present,
   * otherwise in {@link #looseDbConfig()}.
   */
  public LocalConfig withDbConfig(DbConfig db) {
    if (sqlConfig.isPresent())
      return new LocalConfig(chainUrl, accessToken,
          Optional.of(sqlConfig.get().withDbConfig(db)), Optional.empty(), saltEpochs, saltScheme);
    return new LocalConfig(chainUrl, accessToken, Optional.empty(), Optional.of(db), saltEpochs, saltScheme);
  }

  public LocalConfig withSaltEpochs(List<EpochSeed> epochs) {
    return new LocalConfig(chainUrl, accessToken, sqlConfig, looseDbConfig, epochs, saltScheme);
  }

  public LocalConfig withSaltScheme(SaltScheme scheme) {
    return new LocalConfig(chainUrl, accessToken, sqlConfig, looseDbConfig, saltEpochs, Optional.of(scheme));
  }


  // ── Path resolution ───────────────────────────────────────────────────────

  /**
   * Returns a copy of this config with any relative {@code jdbc_driver_jar}
   * path in {@link #sqlConfig()} and/or {@link #looseDbConfig()} resolved
   * against {@code configDir}.
   *
   * <p>Called automatically by {@link Parser#toEntity(File)} so that callers
   * loading from a file always receive absolute paths.</p>
   */
  public LocalConfig resolve(Path configDir) {
    Optional<SqlConfig> resolvedSql = sqlConfig.map(sc -> {
      Optional<DbConfig> db = sc.dbConfig();
      return db.isPresent() ? sc.withDbConfig(db.get().resolve(configDir)) : sc;
    });
    Optional<DbConfig> resolvedLoose = looseDbConfig.map(db -> db.resolve(configDir));
    return new LocalConfig(chainUrl, accessToken, resolvedSql, resolvedLoose, saltEpochs, saltScheme);
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<LocalConfig> {

    /**
     * Flattens all configured fields into {@code jObj}.
     *
     * <p>When {@link LocalConfig#sqlConfig()} is present, its fields (including
     * JDBC details) are injected via {@link SqlConfig.Parser#injectEntity}.
     * When only {@link LocalConfig#looseDbConfig()} is present, those JDBC
     * fields are injected via {@link DbConfig.Parser#injectEntity}.
     * Salt epochs go under {@link ApiConstants#SALTS_KEY}.
     * The salt scheme, when present, goes under {@link ApiConstants#SALT_SCHEME_KEY}
     * as a nested object.</p>
     */
    @Override
    public JSONObject injectEntity(LocalConfig cfg, JSONObject jObj) {
      cfg.chainUrl().ifPresent(u -> jObj.put(CHAIN_URL_KEY, u));
      cfg.accessToken().ifPresent(t -> jObj.put(ACCESS_TOKEN_KEY, t));
      if (cfg.sqlConfig().isPresent()) {
        SqlConfig.PARSER.injectEntity(cfg.sqlConfig().get(), jObj);
      } else {
        cfg.looseDbConfig().ifPresent(db -> DbConfig.PARSER.injectEntity(db, jObj));
      }
      cfg.saltScheme().ifPresent(s ->
          jObj.put(SALT_SCHEME_KEY, SaltSchemeParser.PARSER.toJsonObject(s)));
      if (!cfg.saltEpochs().isEmpty())
        jObj.put(SALTS_KEY, EpochSeedParser.PARSER.toJsonArray(cfg.saltEpochs()));
      return jObj;
    }

    /**
     * Loads {@link LocalConfig} from {@code file} and resolves any relative
     * {@code jdbc_driver_jar} path against the file's parent directory.
     */
    @Override
    public LocalConfig toEntity(File file) throws JsonParsingException, UncheckedIOException {
      LocalConfig cfg = JsonEntityParser.super.toEntity(file);
      Path configDir = file.toPath().toAbsolutePath().getParent();
      return cfg.resolve(configDir);
    }

    @Override
    public LocalConfig toEntity(JSONObject jObj) throws JsonParsingException {
      Optional<String> chainUrl = Optional.ofNullable(
          JsonUtils.getString(jObj, CHAIN_URL_KEY, false));
      Optional<String> token = Optional.ofNullable(
          JsonUtils.getString(jObj, ACCESS_TOKEN_KEY, false));

      Optional<SqlConfig> sqlConfig = Optional.empty();
      Optional<DbConfig>  looseDbConfig = Optional.empty();

      if (jObj.containsKey(BY_NO_QUERY_KEY)) {
        sqlConfig = Optional.of(SqlConfig.PARSER.toEntity(jObj));
      } else {
        // Partial state: carry loose DbConfig until queries are configured
        DbConfig db = DbConfig.PARSER.toEntity(jObj);
        if (db.jdbcUrl().isPresent() || db.jdbcDriverClass().isPresent()
            || db.jdbcDriverJar().isPresent())
          looseDbConfig = Optional.of(db);
      }

      List<EpochSeed> saltEpochs = List.of();
      Object saltsVal = jObj.get(SALTS_KEY);
      if (saltsVal instanceof JSONArray arr)
        saltEpochs = EpochSeedParser.PARSER.toEntityList(arr);

      Optional<SaltScheme> saltScheme = Optional.empty();
      Object ssVal = jObj.get(SALT_SCHEME_KEY);
      if (ssVal instanceof JSONObject ssObj)
        saltScheme = Optional.of(SaltSchemeParser.PARSER.toEntity(ssObj));

      return new LocalConfig(chainUrl, token, sqlConfig, looseDbConfig, saltEpochs, saltScheme);
    }
  }
}
