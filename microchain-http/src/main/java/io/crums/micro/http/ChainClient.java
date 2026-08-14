/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import io.crums.micro.api.ChainInfo;
import io.crums.micro.api.ColumnInfo;
import io.crums.micro.api.CommitRequest;
import io.crums.micro.api.CommitResponse;
import io.crums.micro.api.SqlConfig;
import io.crums.sldg.HashConflictException;
import io.crums.sldg.Path;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.json.PathPackParser;
import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.json.TableSaltReader;
import io.crums.util.json.simple.JSONArray;
import io.crums.util.json.simple.parser.JSONParser;
import io.crums.util.json.simple.parser.ParseException;

/**
 * HTTP client for all {@code /{user}/{chain}/} REST endpoints.
 *
 * <p>All microchain REST calls for a specific chain are centralised here
 * so that higher-level tools (CLI, rich clients) share the same networking
 * layer.</p>
 *
 * <h2>Authentication</h2>
 * <p>Public endpoints ({@code /info}, {@code /state}) require no token.
 * Authenticated endpoints ({@code /salts}, {@code /sql-config},
 * {@code /commit}) require a Bearer access token passed as a method
 * argument.  A missing or invalid token causes a {@link SecurityException}.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@link #getInfo()} — {@code GET /info}</li>
 *   <li>{@link #getSalts(String)} — {@code GET /salts}</li>
 *   <li>{@link #getSqlConfig(String)} — {@code GET /sql-config}</li>
 *   <li>{@link #getState()} — {@code GET /state?rows=last}</li>
 *   <li>{@link #getState(List, boolean, boolean)} — {@code GET /state?rows=...} with explicit rows</li>
 *   <li>{@link #commit(String, CommitRequest)} — {@code POST /commit}</li>
 * </ul>
 */
public class ChainClient {

  /**
   * PathPack parser matching the server's default {@code enc=b64} encoding
   * and the field names defined in {@code StateResource}.
   */
  static final PathPackParser STATE_PARSER = new PathPackParser(
      HashEncoding.BASE64_32, "stitch_rows", "compression", "hashes");

  private final HttpClient http;
  private final URI base;
  private final String username;
  private final String chain;

  /**
   * @param base      server base URI, e.g. {@code http://localhost:8081}
   * @param username  chain owner's username
   * @param chain     chain name
   * @param http      shared {@link HttpClient} instance
   */
  public ChainClient(URI base, String username, String chain, HttpClient http) {
    this.base     = base;
    this.username = username;
    this.chain    = chain;
    this.http     = http;
  }

  public URI    base()     { return base; }
  public String username() { return username; }
  public String chain()    { return chain; }


  // ── /info ────────────────────────────────────────────────────────────────────

  /**
   * {@code GET /{user}/{chain}/info} — public chain metadata.
   *
   * @return chain metadata, or empty if the chain does not exist
   * @throws IOException          on HTTP failure
   * @throws InterruptedException if the HTTP call is interrupted
   */
  public Optional<ChainInfo> getInfo() throws IOException, InterruptedException {
    String url = endpointUrl("info");
    var resp = httpGet(url);
    if (resp.statusCode() == 404)
      return Optional.empty();
    checkOk(resp, url);
    return Optional.of(ChainInfo.PARSER.toEntity(resp.body()));
  }


  // ── /salts ────────────────────────────────────────────────────────────────────

  /**
   * {@code GET /{user}/{chain}/salts} — salt seeds for this chain.
   *
   * <p>The returned {@link TableSalt} is assembled from all epoch seeds in the
   * response array via {@link TableSaltReader}.</p>
   *
   * @param token  Bearer access token (same token used for {@link #commit})
   * @return the chain's table-salt, covering all epochs
   * @throws SecurityException    if the token is missing or invalid (HTTP 401)
   * @throws IOException          on HTTP or JSON-parse failure
   * @throws InterruptedException if the HTTP call is interrupted
   */
  public TableSalt getSalts(String token) throws IOException, InterruptedException {
    String url = endpointUrl("salts");
    var resp = httpGet(url, token);
    checkOk(resp, url);
    return TableSaltReader.READER.toTableSalt(parseArray(resp.body(), url));
  }


  // ── /sql-config ───────────────────────────────────────────────────────────────

  /**
   * {@code GET /{user}/{chain}/sql-config} — SQL ledger configuration.
   *
   * <p>The returned {@link SqlConfig} carries the JDBC queries and optional
   * connection URL needed to open a local {@code SqlLedger}.</p>
   *
   * @param token  Bearer access token
   * @return the chain's SQL ledger configuration
   * @throws SecurityException    if the token is missing or invalid (HTTP 401)
   * @throws IOException          on HTTP failure
   * @throws InterruptedException if the HTTP call is interrupted
   */
  public SqlConfig getSqlConfig(String token) throws IOException, InterruptedException {
    String url = endpointUrl("sql-config");
    var resp = httpGet(url, token);
    checkOk(resp, url);
    return SqlConfig.PARSER.toEntity(resp.body());
  }


  // ── /columns ──────────────────────────────────────────────────────────────────

  /**
   * {@code GET /{user}/{chain}/columns} — decorative column metadata, public.
   *
   * <p>Unlike {@link #getSqlConfig(String)}, this requires no token: column
   * names/descriptions are non-sensitive and are needed to render receipts
   * for recipients who hold a source row but no bearer token.</p>
   *
   * @return column metadata ordered by {@link ColumnInfo#index()};
   *         empty when the chain owner hasn't configured any
   * @throws IOException          on HTTP failure
   * @throws InterruptedException if the HTTP call is interrupted
   */
  public List<ColumnInfo> getColumns() throws IOException, InterruptedException {
    String url = endpointUrl("columns");
    var resp = httpGet(url);
    if (resp.statusCode() == 404)
      return List.of();
    checkOk(resp, url);
    return ColumnInfo.PARSER.toEntityList(parseArray(resp.body(), url));
  }

  /**
   * {@code PUT /{user}/{chain}/columns} — replaces the chain's column metadata.
   *
   * @param token   Bearer access token
   * @param columns full replacement list; pass an empty list to clear
   * @throws SecurityException    if the token is missing or invalid (HTTP 401)
   * @throws IOException          on HTTP failure
   * @throws InterruptedException if the HTTP call is interrupted
   */
  public void putColumns(String token, List<ColumnInfo> columns)
      throws IOException, InterruptedException {
    String url  = endpointUrl("columns");
    String body = ColumnInfo.PARSER.toJsonArray(columns).toJSONString();
    var resp = httpPut(url, token, body);
    checkOk(resp, url);
  }


  // ── /state ────────────────────────────────────────────────────────────────────

  /**
   * {@code GET /{user}/{chain}/state?rows=last} — singleton path for the
   * latest committed row.
   *
   * <p>Used by the commit workflow to read the current committed row count and
   * by the {@code status} subcommand.</p>
   *
   * @return singleton path (last committed row), or empty if the chain has no
   *         committed rows
   * @throws IOException          on HTTP failure
   * @throws InterruptedException if the HTTP call is interrupted
   */
  public Optional<Path> getState() throws IOException, InterruptedException {
    return fetchState(endpointUrl("state") + "?rows=last");
  }

  /**
   * {@code GET /{user}/{chain}/state} with explicit row targets.
   *
   * <p>The server builds the shortest commitment path that links all of the
   * requested rows.  Pass {@code includeLast=true} to also include the latest
   * committed row (equivalent to appending {@code last} to the {@code rows}
   * parameter).  The server sorts and de-duplicates the list, so callers need
   * not pre-sort.</p>
   *
   * @param targetRows  specific row numbers to include (each &ge; 1);
   *                    may be empty when {@code includeLast} is {@code true}
   * @param includeLast if {@code true}, the latest committed row is always
   *                    included (appends {@code last} to the query)
   * @param compress    if {@code true}, the server returns a compressed
   *                    (not necessarily condensed) path; if {@code false},
   *                    then the returned path is <em>not condensed</em>
   * @return the requested state path, or empty if the chain has no committed rows
   * @throws IllegalArgumentException if any row number is less than 1, or both
   *                                  {@code targetRows} is empty and
   *                                  {@code includeLast} is {@code false}
   * @throws IOException              on HTTP failure
   * @throws InterruptedException     if the HTTP call is interrupted
   * 
   * @see Path#isCompressed()
   * @see Path#isCondensed()
   */
  public Optional<Path> getState(List<Long> targetRows, boolean includeLast, boolean compress)
      throws IOException, InterruptedException {
    return fetchState(endpointUrl("state") + "?" + stateQuery(targetRows, includeLast, compress));
  }


  // ── /commit ───────────────────────────────────────────────────────────────────

  /**
   * {@code POST /{user}/{chain}/commit} — appends hashed rows to the chain.
   *
   * <p>The endpoint is idempotent: re-submitting the same rows at the same
   * {@code startRow} is safe as long as the hashes match.  A mismatch
   * causes a {@link HashConflictException} (HTTP 409).</p>
   *
   * @param token    Bearer access token
   * @param request  commit payload ({@code startRow} and {@code inputHashes})
   * @return the server's response containing the updated total row count
   * @throws SecurityException     if the token is missing or invalid (HTTP 401)
   * @throws HashConflictException if submitted hashes conflict with existing rows (HTTP 409)
   * @throws IOException           on HTTP failure
   * @throws InterruptedException  if the HTTP call is interrupted
   */
  public CommitResponse commit(String token, CommitRequest request)
      throws IOException, InterruptedException {
    String url  = endpointUrl("commit");
    String body = CommitRequest.PARSER.toJsonObject(request).toJSONString();
    var resp = httpPost(url, token, body);
    if (resp.statusCode() == 409)
      throw new HashConflictException(resp.statusCode() + ": " + resp.body());
    checkOk(resp, url);
    return CommitResponse.PARSER.toEntity(resp.body());
  }


  // ── private HTTP helpers ──────────────────────────────────────────────────────

  private HttpResponse<String> httpGet(String url)
      throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("Accept", "application/json")
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> httpGet(String url, String token)
      throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> httpPost(String url, String token, String jsonBody)
      throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> httpPut(String url, String token, String jsonBody)
      throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create(url))
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Asserts HTTP 200; throws {@link SecurityException} on 401,
   * {@link IOException} on any other non-200.
   */
  private static void checkOk(HttpResponse<String> resp, String url) throws IOException {
    int s = resp.statusCode();
    if (s == 200) return;
    if (s == 401)
      throw new SecurityException(url + " → 401: unauthorized (invalid or missing token)");
    throw new IOException(url + " → " + s + ": " + resp.body());
  }

  private Optional<Path> fetchState(String url) throws IOException, InterruptedException {
    var resp = httpGet(url);
    if (resp.statusCode() == 404)
      return Optional.empty();
    checkOk(resp, url);
    return Optional.of(STATE_PARSER.toEntity(resp.body()).path());
  }

  /** Builds the {@code rows=...&compress=...} query string for a parameterised {@code /state} call. */
  private static String stateQuery(List<Long> targetRows, boolean includeLast, boolean compress) {
    if (targetRows.isEmpty() && !includeLast)
      throw new IllegalArgumentException("must specify at least one row or includeLast=true");
    var sb = new StringBuilder("rows=");
    for (int i = 0; i < targetRows.size(); i++) {
      if (i > 0) sb.append(',');
      long rn = targetRows.get(i);
      if (rn < 1)
        throw new IllegalArgumentException("row number < 1: " + rn);
      sb.append(rn);
    }
    if (includeLast) {
      if (!targetRows.isEmpty()) sb.append(',');
      sb.append("last");
    }
    sb.append("&compress=").append(compress ? 1 : 0);
    return sb.toString();
  }

  /** Returns the URL string for a named endpoint under this client's chain. */
  private String endpointUrl(String name) {
    String s = base.toString();
    if (s.endsWith("/"))
      s = s.substring(0, s.length() - 1);
    return s + "/" + username + "/" + chain + "/" + name;
  }

  /** Parses a response body as a JSON array; wraps {@link ParseException} in {@link IOException}. */
  private static JSONArray parseArray(String body, String url) throws IOException {
    try {
      return (JSONArray) new JSONParser().parse(body);
    } catch (ParseException px) {
      throw new IOException("failed to parse response from " + url + ": " + px.getMessage(), px);
    }
  }

}
