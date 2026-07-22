/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.util.List;
import java.util.Optional;

import io.crums.sldg.salt.EpochedTableSalt;
import io.crums.sldg.salt.EpochedTableSalt.EpochSeed;
import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.json.EpochSeedParser;
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
 *   "row_count_query":   "SELECT COUNT(*) FROM ...",
 *   "jdbc_url":          "jdbc:postgresql://...",
 *   "jdbc_driver_class": "org.postgresql.Driver",
 *   "timezone":          "America/New_York",
 *   "default_lag":       5,
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
 * @param accessToken   bearer access token; overrides {@code --token} when not
 *                      also given on the command line
 * @param sqlConfig     complete SQL ledger configuration; present when both SQL
 *                      queries are configured — skips {@code GET /sql-config}
 * @param looseDbConfig JDBC connection details held before SQL queries are known;
 *                      empty when {@code sqlConfig} is present
 * @param saltEpochs    epoch seeds from the {@code salts} array; empty when the
 *                      key is absent — skips {@code GET /salts} when non-empty
 */
public record LocalConfig(
    Optional<String>    accessToken,
    Optional<SqlConfig> sqlConfig,
    Optional<DbConfig>  looseDbConfig,
    List<EpochSeed>     saltEpochs) {

  /** Sentinel representing an entirely absent local config. */
  public static final LocalConfig EMPTY = new LocalConfig(
      Optional.empty(), Optional.empty(), Optional.empty(), List.of());

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

  public LocalConfig withAccessToken(String token) {
    return new LocalConfig(Optional.of(token), sqlConfig, looseDbConfig, saltEpochs);
  }

  /**
   * Sets a complete {@link SqlConfig}, clearing {@link #looseDbConfig()}
   * (its values are now embedded in {@code cfg}).
   */
  public LocalConfig withSqlConfig(SqlConfig cfg) {
    return new LocalConfig(accessToken, Optional.of(cfg), Optional.empty(), saltEpochs);
  }

  /**
   * Updates the JDBC connection details: within {@link #sqlConfig()} if present,
   * otherwise in {@link #looseDbConfig()}.
   */
  public LocalConfig withDbConfig(DbConfig db) {
    if (sqlConfig.isPresent())
      return new LocalConfig(accessToken,
          Optional.of(sqlConfig.get().withDbConfig(db)), Optional.empty(), saltEpochs);
    return new LocalConfig(accessToken, Optional.empty(), Optional.of(db), saltEpochs);
  }

  public LocalConfig withSaltEpochs(List<EpochSeed> epochs) {
    return new LocalConfig(accessToken, sqlConfig, looseDbConfig, epochs);
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
     * Salt epochs go under {@link ApiConstants#SALTS_KEY}.</p>
     */
    @Override
    public JSONObject injectEntity(LocalConfig cfg, JSONObject jObj) {
      cfg.accessToken().ifPresent(t -> jObj.put(ACCESS_TOKEN_KEY, t));
      if (cfg.sqlConfig().isPresent()) {
        SqlConfig.PARSER.injectEntity(cfg.sqlConfig().get(), jObj);
      } else {
        cfg.looseDbConfig().ifPresent(db -> DbConfig.PARSER.injectEntity(db, jObj));
      }
      if (!cfg.saltEpochs().isEmpty())
        jObj.put(SALTS_KEY, EpochSeedParser.PARSER.toJsonArray(cfg.saltEpochs()));
      return jObj;
    }

    @Override
    public LocalConfig toEntity(JSONObject jObj) throws JsonParsingException {
      Optional<String> token = Optional.ofNullable(
          JsonUtils.getString(jObj, ACCESS_TOKEN_KEY, false));

      Optional<SqlConfig> sqlConfig = Optional.empty();
      Optional<DbConfig>  looseDbConfig = Optional.empty();

      if (jObj.containsKey(BY_NO_QUERY_KEY)) {
        sqlConfig = Optional.of(SqlConfig.PARSER.toEntity(jObj));
      } else {
        // Partial state: carry loose DbConfig until queries are configured
        DbConfig db = DbConfig.PARSER.toEntity(jObj);
        if (db.jdbcUrl().isPresent() || db.jdbcDriverClass().isPresent())
          looseDbConfig = Optional.of(db);
      }

      List<EpochSeed> saltEpochs = List.of();
      Object saltsVal = jObj.get(SALTS_KEY);
      if (saltsVal instanceof JSONArray arr)
        saltEpochs = EpochSeedParser.PARSER.toEntityList(arr);

      return new LocalConfig(token, sqlConfig, looseDbConfig, saltEpochs);
    }
  }
}
