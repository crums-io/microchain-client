/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

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
 * The local config file format for microchain CLI tools ({@code --local-config}):
 * shared connection and token defaults for a set of related chains — e.g.
 * chains backed by tables in the same on-prem database — plus one
 * {@link ChainConfig} entry per chain, keyed by a short local alias. This is
 * the only local config file format; a lone chain is simply a file with a
 * single {@code chains} entry.
 *
 * <h2>File format</h2>
 * <pre>{@code
 * {
 *   "base_chain_url":    "https://micro.crums.io/bikeshop",
 *   "jdbc_url":          "jdbc:postgresql://db.example.com:5432/mydb",
 *   "jdbc_driver_class": "org.postgresql.Driver",
 *   "access_token":      "mcw_...",
 *
 *   "chains": {
 *     "orders":      { "by_no_query": "...", "row_count_query": "..." },
 *     "order_items": { "by_no_query": "...", "row_count_query": "..." }
 *   }
 * }
 * }</pre>
 *
 * <p>{@link #resolve(String)} merges the shared defaults under a chain's own
 * fields (the chain's own fields win), inferring {@code chain_url} as
 * {@code base_chain_url + "/" + alias} when the chain doesn't set its own —
 * producing a fully-resolved {@link ChainConfig}, ready to feed through the
 * usual CLI-flag / local-config / server precedence for that one chain.</p>
 *
 * @param baseChainUrl URL prefix ({@code <server>/{user}}); combined with a
 *                      chain's alias to infer its {@code chain_url} when the
 *                      chain doesn't set one explicitly
 * @param accessToken   default bearer token, used when a chain doesn't set its own
 * @param dbConfig      default JDBC connection details, merged under each
 *                      chain's own (see {@link DbConfig#merge})
 * @param timezone      default timezone, used when a chain doesn't set its own
 * @param defaultLag    default trailing-row lag, used when a chain doesn't set its own
 * @param chains        per-chain configs, keyed by local alias
 */
public record LocalConfig(
    Optional<String>          baseChainUrl,
    Optional<String>          accessToken,
    DbConfig                  dbConfig,
    Optional<String>          timezone,
    Optional<Long>            defaultLag,
    Map<String, ChainConfig>  chains) {

  public static final Parser PARSER = new Parser();

  public LocalConfig {
    dbConfig = dbConfig == null ? DbConfig.EMPTY : dbConfig;
    chains = Map.copyOf(chains);
  }

  /** The local aliases (the {@code chains} object's keys) configured in this file. */
  public Set<String> chainAliases() {
    return chains.keySet();
  }

  /**
   * Resolves the effective {@link ChainConfig} for {@code alias}: the
   * chain's own fields override the shared defaults, and {@code chain_url}
   * is inferred from {@link #baseChainUrl()} + {@code alias} when the chain
   * doesn't set its own. Fields neither the chain nor the shared defaults
   * configure are left absent — still subject to CLI-flag overrides and
   * server fallback downstream.
   *
   * @throws NoSuchElementException if {@code alias} isn't in {@link #chains()}
   */
  public ChainConfig resolve(String alias) {
    ChainConfig chainCfg = chains.get(alias);
    if (chainCfg == null)
      throw new NoSuchElementException("no such chain: " + alias);

    Optional<String> effChainUrl = chainCfg.chainUrl()
        .or(() -> baseChainUrl.map(b -> b + "/" + alias));
    Optional<String> effToken = chainCfg.accessToken().or(() -> accessToken);

    Optional<SqlConfig> effSqlConfig = chainCfg.sqlConfig().map(sql -> {
      DbConfig mergedDb = dbConfig.merge(sql.dbConfig().orElse(DbConfig.EMPTY));
      SqlConfig merged = sql.withDbConfig(mergedDb);
      if (merged.timezone().isEmpty() && timezone.isPresent())
        merged = merged.withTimezone(timezone.get());
      if (merged.defaultLag().isEmpty() && defaultLag.isPresent())
        merged = merged.withDefaultLag(defaultLag.get());
      return merged;
    });

    Optional<DbConfig> effLooseDb = Optional.empty();
    if (effSqlConfig.isEmpty()) {
      DbConfig merged = dbConfig.merge(chainCfg.looseDbConfig().orElse(DbConfig.EMPTY));
      if (!merged.equals(DbConfig.EMPTY))
        effLooseDb = Optional.of(merged);
    }

    return new ChainConfig(
        effChainUrl, effToken, effSqlConfig, effLooseDb,
        chainCfg.saltEpochs(), chainCfg.saltScheme());
  }

  /**
   * Returns a copy with any relative {@code jdbc_driver_jar} path — in the
   * shared defaults and in each chain — resolved against {@code configDir}.
   * Called automatically by {@link Parser#toEntity(File)}.
   */
  public LocalConfig resolve(Path configDir) {
    Map<String, ChainConfig> resolvedChains = new LinkedHashMap<>();
    chains.forEach((alias, cfg) -> resolvedChains.put(alias, cfg.resolve(configDir)));
    return new LocalConfig(
        baseChainUrl, accessToken, dbConfig.resolve(configDir), timezone, defaultLag,
        resolvedChains);
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<LocalConfig> {

    @Override
    public JSONObject injectEntity(LocalConfig cfg, JSONObject jObj) {
      cfg.baseChainUrl().ifPresent(u -> jObj.put(BASE_CHAIN_URL_KEY, u));
      cfg.accessToken().ifPresent(t -> jObj.put(ACCESS_TOKEN_KEY, t));
      DbConfig.PARSER.injectEntity(cfg.dbConfig(), jObj);
      cfg.timezone().ifPresent(tz -> jObj.put(TIMEZONE_KEY, tz));
      cfg.defaultLag().ifPresent(l -> jObj.put(DEFAULT_LAG_KEY, l));
      JSONObject chainsObj = new JSONObject();
      cfg.chains().forEach((alias, chainCfg) ->
          chainsObj.put(alias, ChainConfig.PARSER.injectEntity(chainCfg, new JSONObject())));
      jObj.put(CHAINS_KEY, chainsObj);
      return jObj;
    }

    /**
     * Loads {@link LocalConfig} from {@code file} and resolves any
     * relative {@code jdbc_driver_jar} paths against the file's parent
     * directory.
     */
    @Override
    public LocalConfig toEntity(File file) throws JsonParsingException, UncheckedIOException {
      LocalConfig cfg = JsonEntityParser.super.toEntity(file);
      Path configDir = file.toPath().toAbsolutePath().getParent();
      return cfg.resolve(configDir);
    }

    @Override
    public LocalConfig toEntity(JSONObject jObj) throws JsonParsingException {
      Optional<String> baseChainUrl = Optional.ofNullable(
          JsonUtils.getString(jObj, BASE_CHAIN_URL_KEY, false));
      Optional<String> token = Optional.ofNullable(
          JsonUtils.getString(jObj, ACCESS_TOKEN_KEY, false));
      DbConfig dbConfig = DbConfig.PARSER.toEntity(jObj);
      Optional<String> timezone = Optional.ofNullable(
          JsonUtils.getString(jObj, TIMEZONE_KEY, false));
      Optional<Long> defaultLag = Optional.empty();
      Object lagVal = jObj.get(DEFAULT_LAG_KEY);
      if (lagVal instanceof Number n) defaultLag = Optional.of(n.longValue());

      Map<String, ChainConfig> chains = new LinkedHashMap<>();
      Object chainsVal = jObj.get(CHAINS_KEY);
      if (chainsVal instanceof JSONObject chainsObj) {
        for (Object keyObj : chainsObj.keySet()) {
          String alias = String.valueOf(keyObj);
          Object entryVal = chainsObj.get(keyObj);
          if (!(entryVal instanceof JSONObject entryObj))
            throw new JsonParsingException("chains." + alias + " must be a JSON object");
          chains.put(alias, ChainConfig.PARSER.toEntity(entryObj));
        }
      }

      return new LocalConfig(baseChainUrl, token, dbConfig, timezone, defaultLag, chains);
    }
  }


  // ── ChainConfig ──────────────────────────────────────────────────────────

  /**
   * One chain's config: either a raw entry under a {@link LocalConfig}'s
   * {@code chains} object, or the fully-resolved view {@link #resolve(String)}
   * returns after merging in the shared defaults.
   *
   * <p>Combines the server-side {@code /sql-config} and {@code /salts}
   * responses, plus the chain's bearer access token. This lets a chain owner
   * operate without storing sensitive data on the server, and also supports
   * dry-run workflow validation before registering queries on the website.
   * Fields absent here (and not supplied by the shared defaults) are fetched
   * from the server instead, where applicable; CLI flags always take
   * precedence over both.</p>
   *
   * <h2>Partial state</h2>
   * <p>Before both SQL queries are known, a {@link SqlConfig} cannot be
   * constructed. In that intermediate state, JDBC connection details are held
   * in {@link #looseDbConfig()}. Use the resolving method {@link #dbConfig()}
   * to retrieve whichever source is active without caring which it is. Exactly
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
  public record ChainConfig(
      Optional<String>     chainUrl,
      Optional<String>     accessToken,
      Optional<SqlConfig>  sqlConfig,
      Optional<DbConfig>   looseDbConfig,
      List<EpochSeed>      saltEpochs,
      Optional<SaltScheme> saltScheme) {

    /** Sentinel representing an entirely absent chain config. */
    public static final ChainConfig EMPTY = new ChainConfig(
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        List.of(), Optional.empty());

    public static final Parser PARSER = new Parser();

    public ChainConfig {
      saltEpochs = List.copyOf(saltEpochs);
    }


    // ── Resolving convenience methods ─────────────────────────────────────

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


    // ── Wither methods ──────────────────────────────────────────────────────

    public ChainConfig withChainUrl(String url) {
      return new ChainConfig(Optional.of(url), accessToken, sqlConfig, looseDbConfig, saltEpochs, saltScheme);
    }

    public ChainConfig withAccessToken(String token) {
      return new ChainConfig(chainUrl, Optional.of(token), sqlConfig, looseDbConfig, saltEpochs, saltScheme);
    }

    /**
     * Sets a complete {@link SqlConfig}, clearing {@link #looseDbConfig()}
     * (its values are now embedded in {@code cfg}).
     */
    public ChainConfig withSqlConfig(SqlConfig cfg) {
      return new ChainConfig(chainUrl, accessToken, Optional.of(cfg), Optional.empty(), saltEpochs, saltScheme);
    }

    /**
     * Updates the JDBC connection details: within {@link #sqlConfig()} if present,
     * otherwise in {@link #looseDbConfig()}.
     */
    public ChainConfig withDbConfig(DbConfig db) {
      if (sqlConfig.isPresent())
        return new ChainConfig(chainUrl, accessToken,
            Optional.of(sqlConfig.get().withDbConfig(db)), Optional.empty(), saltEpochs, saltScheme);
      return new ChainConfig(chainUrl, accessToken, Optional.empty(), Optional.of(db), saltEpochs, saltScheme);
    }

    public ChainConfig withSaltEpochs(List<EpochSeed> epochs) {
      return new ChainConfig(chainUrl, accessToken, sqlConfig, looseDbConfig, epochs, saltScheme);
    }

    public ChainConfig withSaltScheme(SaltScheme scheme) {
      return new ChainConfig(chainUrl, accessToken, sqlConfig, looseDbConfig, saltEpochs, Optional.of(scheme));
    }


    // ── Path resolution ─────────────────────────────────────────────────────

    /**
     * Returns a copy of this config with any relative {@code jdbc_driver_jar}
     * path in {@link #sqlConfig()} and/or {@link #looseDbConfig()} resolved
     * against {@code configDir}.
     *
     * <p>Called automatically by {@link Parser#toEntity(File)} so that callers
     * loading from a file always receive absolute paths.</p>
     */
    public ChainConfig resolve(Path configDir) {
      Optional<SqlConfig> resolvedSql = sqlConfig.map(sc -> {
        Optional<DbConfig> db = sc.dbConfig();
        return db.isPresent() ? sc.withDbConfig(db.get().resolve(configDir)) : sc;
      });
      Optional<DbConfig> resolvedLoose = looseDbConfig.map(db -> db.resolve(configDir));
      return new ChainConfig(chainUrl, accessToken, resolvedSql, resolvedLoose, saltEpochs, saltScheme);
    }


    // ── JSON parser ───────────────────────────────────────────────────────

    public static class Parser implements JsonEntityParser<ChainConfig> {

      /**
       * Flattens all configured fields into {@code jObj}.
       *
       * <p>When {@link ChainConfig#sqlConfig()} is present, its fields (including
       * JDBC details) are injected via {@link SqlConfig.Parser#injectEntity}.
       * When only {@link ChainConfig#looseDbConfig()} is present, those JDBC
       * fields are injected via {@link DbConfig.Parser#injectEntity}.
       * Salt epochs go under {@link ApiConstants#SALTS_KEY}.
       * The salt scheme, when present, goes under {@link ApiConstants#SALT_SCHEME_KEY}
       * as a nested object.</p>
       */
      @Override
      public JSONObject injectEntity(ChainConfig cfg, JSONObject jObj) {
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
       * Loads {@link ChainConfig} from {@code file} and resolves any relative
       * {@code jdbc_driver_jar} path against the file's parent directory.
       */
      @Override
      public ChainConfig toEntity(File file) throws JsonParsingException, UncheckedIOException {
        ChainConfig cfg = JsonEntityParser.super.toEntity(file);
        Path configDir = file.toPath().toAbsolutePath().getParent();
        return cfg.resolve(configDir);
      }

      @Override
      public ChainConfig toEntity(JSONObject jObj) throws JsonParsingException {
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

        return new ChainConfig(chainUrl, token, sqlConfig, looseDbConfig, saltEpochs, saltScheme);
      }
    }
  }
}
