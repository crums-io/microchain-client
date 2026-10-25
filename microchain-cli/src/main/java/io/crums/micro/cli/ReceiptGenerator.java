/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.cli;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.crums.micro.api.LocalConfig;
import io.crums.micro.api.LocalConfig.ChainConfig;
import io.crums.micro.api.SqlConfig;
import io.crums.micro.api.receipt.ChainReceipt;
import io.crums.micro.api.receipt.Receipt;
import io.crums.micro.api.receipt.ReceiptGeneratorTemplate;
import io.crums.micro.api.receipt.RowSelector;
import io.crums.micro.cli.Main.ResolvedQueryConfig;
import io.crums.micro.http.ChainClient;
import io.crums.sldg.src.SourceRow;

/**
 * Resolves a {@link ReceiptGeneratorTemplate} into a draft {@link Receipt} by walking
 * its {@link RowSelector}s, in order, against the local SQL database(s)
 * named in a {@link LocalConfig}. See {@link RowSelector}'s javadoc for the
 * full binding rules this implements (the first chain is the "driver",
 * fed directly from caller-supplied inputs; every later chain derives its
 * own bound values from a row already resolved for an earlier chain).
 *
 * <p>Every chain touched must resolve a complete {@code sql-config} and
 * salt story — locally, or (for the salt/sql-config pieces) from the
 * server, exactly as {@code mchain rows} does via {@link
 * Main#resolveQueryConfig}. There is no notion of "dry run" here: a
 * receipt's rows must hash the same way the real chain would commit them.</p>
 */
public class ReceiptGenerator {

  private static final Pattern ROW_NO_PATTERN = Pattern.compile("^ROW_NO\\(:([\\w-]+)\\)$");
  private static final Pattern COLUMN_PATTERN  = Pattern.compile("^:([\\w-]+)\\.(.+)$");

  private final LocalConfig localConfig;
  private final String token;
  private final String jdbcUrlOverride;
  private final File jdbcDriverJarOverride;
  private final String jdbcUser;
  private final char[] jdbcPass;

  public ReceiptGenerator(
      LocalConfig localConfig, String token,
      String jdbcUrlOverride, File jdbcDriverJarOverride,
      String jdbcUser, char[] jdbcPass) {
    this.localConfig = localConfig;
    this.token = token;
    this.jdbcUrlOverride = jdbcUrlOverride;
    this.jdbcDriverJarOverride = jdbcDriverJarOverride;
    this.jdbcUser = jdbcUser;
    this.jdbcPass = jdbcPass;
  }

  /** One already-resolved chain selector's outcome. */
  private record Resolved(List<SourceRow> rows, List<String> columnLabels) {
    boolean isSingular() { return rows.size() == 1; }
  }

  /**
   * Generates a draft receipt (no {@code state} attached yet — see {@code
   * mchain receipt anchor}) for {@code template}, binding its first
   * selector's {@code ?} placeholders — or, if it has no {@code where},
   * the single row-number lookup — to {@code inputs}, in order.
   *
   * @throws IllegalStateException   if the first chain doesn't resolve to
   *         exactly one row, or any referenced chain alias/column can't be
   *         resolved
   * @throws IllegalArgumentException on a malformed {@code args} expression
   */
  public Receipt generate(ReceiptGeneratorTemplate template, List<String> inputs)
      throws SQLException, IOException, InterruptedException {

    Map<String, Resolved> resolvedByAlias = new LinkedHashMap<>();
    List<ChainReceipt> chainReceipts = new ArrayList<>();

    List<RowSelector> selectors = template.chains();
    for (int i = 0; i < selectors.size(); i++) {
      RowSelector sel = selectors.get(i);
      Resolved resolved = (i == 0) ? resolveFirst(sel, inputs) : resolveLater(sel, resolvedByAlias);
      resolvedByAlias.put(sel.chain(), resolved);

      if (!resolved.rows().isEmpty())
        chainReceipts.add(toChainReceipt(sel.chain(), resolved));
    }

    return new Receipt(chainReceipts);
  }

  private ChainReceipt toChainReceipt(String alias, Resolved resolved) {
    ChainConfig chainCfg = localConfig.resolve(alias);
    String chainUrl = chainCfg.chainUrl().orElseThrow(() -> new IllegalStateException(
        "no resolvable chain_url for '" + alias + "' — set it explicitly or add " +
        "'base_chain_url' to --local-config"));
    return new ChainReceipt(chainUrl, resolved.rows(), Optional.empty());
  }

  // ── first (driver) chain ─────────────────────────────────────────────────

  private Resolved resolveFirst(RowSelector sel, List<String> inputs)
      throws SQLException, IOException, InterruptedException {
    String alias = sel.chain();

    if (sel.where().isEmpty()) {
      if (inputs.size() != 1)
        throw new IllegalStateException(
            "'" + alias + "' has no 'where' (row-number lookup) — expects exactly 1 input, got "
            + inputs.size());
      long rowNo;
      try {
        rowNo = Long.parseLong(inputs.get(0));
      } catch (NumberFormatException e) {
        throw new IllegalStateException(
            "'" + alias + "' expects a row number; not a valid long: '" + inputs.get(0) + "'");
      }
      try (OpenLedger open = open(alias)) {
        SourceRow row;
        try {
          row = open.ledger().getSourceRow(rowNo);
        } catch (IndexOutOfBoundsException e) {
          throw new IllegalStateException("'" + alias + "': " + e.getMessage());
        }
        if (row.cells().isEmpty())
          throw new IllegalStateException(
              "'" + alias + "' (first chain) resolved to no row at row number " + rowNo);
        return new Resolved(List.of(row), open.ledger().columnLabels());
      }
    }

    // where + raw caller inputs, bound in order
    try (OpenLedger open = open(alias)) {
      List<Object> args = new ArrayList<>(inputs);
      List<SourceRow> rows = open.ledger().query(sel.where().get(), args);
      if (rows.size() != 1)
        throw new IllegalStateException(
            "'" + alias + "' (first chain) must resolve to exactly one row; got " + rows.size());
      return new Resolved(rows, open.ledger().columnLabels());
    }
  }

  // ── later chains ─────────────────────────────────────────────────────────

  private Resolved resolveLater(RowSelector sel, Map<String, Resolved> resolvedByAlias)
      throws SQLException, IOException, InterruptedException {
    String alias = sel.chain();
    if (sel.where().isEmpty())
      throw new IllegalStateException(
          "'" + alias + "': only the first chain in a template may omit 'where'");

    List<Object> boundArgs = new ArrayList<>(sel.args().size());
    for (String expr : sel.args()) {
      Object value = resolveArg(expr, resolvedByAlias);
      if (value == null)
        return new Resolved(List.of(), List.of()); // no value upstream → no rows, not an error
      boundArgs.add(value);
    }

    try (OpenLedger open = open(alias)) {
      List<SourceRow> rows = open.ledger().query(sel.where().get(), boundArgs);
      return new Resolved(rows, open.ledger().columnLabels());
    }
  }

  /** Resolves one {@code args} expression; {@code null} means "no value" (propagates). */
  private Object resolveArg(String expr, Map<String, Resolved> resolvedByAlias) {
    Matcher m = ROW_NO_PATTERN.matcher(expr);
    if (m.matches()) {
      Resolved r = requireResolved(m.group(1), resolvedByAlias, expr);
      return r.isSingular() ? r.rows().get(0).no() : null;
    }

    m = COLUMN_PATTERN.matcher(expr);
    if (m.matches()) {
      String refAlias = m.group(1);
      String column   = m.group(2);
      Resolved r = requireResolved(refAlias, resolvedByAlias, expr);
      if (!r.isSingular())
        return null;
      int idx = findColumnIndex(r.columnLabels(), column);
      if (idx < 0)
        throw new IllegalStateException(
            "column '" + column + "' not found among " + r.columnLabels() +
            " for ':" + refAlias + "." + column + "'");
      return r.rows().get(0).cells().get(idx).value();
    }

    throw new IllegalArgumentException("unrecognized args expression: '" + expr + "'");
  }

  private static Resolved requireResolved(
      String alias, Map<String, Resolved> resolvedByAlias, String expr) {
    Resolved r = resolvedByAlias.get(alias);
    if (r == null)
      throw new IllegalStateException(
          "'" + expr + "' refers to an unknown or not-yet-resolved chain alias '" + alias + "'" +
          " (args may only reference chains declared earlier in the same template)");
    return r;
  }

  private static int findColumnIndex(List<String> labels, String name) {
    for (int i = 0; i < labels.size(); i++)
      if (name.equalsIgnoreCase(labels.get(i)))
        return i;
    return -1;
  }

  // ── DB plumbing ──────────────────────────────────────────────────────────

  /**
   * A chain's open connection and {@link DisplaySqlLedger} — used both for
   * row-number lookups (it extends {@link LabeledSqlLedger}, so {@code
   * getSourceRow(long)} is inherited) and WHERE-clause lookups.
   */
  private record OpenLedger(Connection connection, DisplaySqlLedger ledger)
      implements AutoCloseable {
    @Override public void close() throws SQLException {
      try (connection) {
        ledger.close();
      }
    }
  }

  private OpenLedger open(String alias) throws SQLException, IOException, InterruptedException {
    ChainConfig chainCfg = localConfig.resolve(alias);
    String effectiveToken = token != null ? token : chainCfg.accessToken().orElse(null);
    URI effectiveUrl = chainCfg.chainUrl().map(URI::create).orElse(null);
    ChainClient client = effectiveUrl != null ? Main.newClient(effectiveUrl) : null;

    ResolvedQueryConfig rqc;
    try {
      rqc = Main.resolveQueryConfig(chainCfg, effectiveUrl, client, effectiveToken);
    } catch (IllegalStateException e) {
      throw new IllegalStateException("'" + alias + "': " + e.getMessage(), e);
    }

    SqlConfig effectiveCfg = rqc.sqlConfig().withDbConfig(
        Main.effectiveDbConfig(rqc.sqlConfig().dbConfig(), chainCfg,
            jdbcUrlOverride, jdbcDriverJarOverride));
    Properties props = Main.cliJdbcProps(jdbcUser, jdbcPass);
    Connection conn = effectiveCfg.openConnection(props.isEmpty() ? null : props);

    try {
      DisplaySqlLedger ledger = DisplaySqlLedger.open(
          conn, effectiveCfg.rowCountQuery(), effectiveCfg.byNoQuery(),
          rqc.salt());
      return new OpenLedger(conn, ledger);
    } catch (RuntimeException | SQLException e) {
      conn.close();
      throw e;
    }
  }
}
