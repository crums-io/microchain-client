/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.receipt;

import static io.crums.micro.api.ApiConstants.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.crums.sldg.HashConflictException;
import io.crums.sldg.Path;
import io.crums.sldg.PathPack;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.json.PathPackParser;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.json.SourceRowParser;
import io.crums.util.Lists;
import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONArray;
import io.crums.util.json.simple.JSONObject;

/**
 * One chain's contribution to a {@link Receipt}: the revealed source rows
 * from that chain, and (once anchored) the skipledger commitment path
 * linking those rows to the chain's public state.
 *
 * <h2>Draft vs. anchored</h2>
 * <p>A {@code ChainReceipt} with an empty {@link #state()} is a <em>draft</em>:
 * the row content is asserted but not yet tied to the chain's commitment
 * chain. Attaching a {@link PathPack} — fetched from {@code GET
 * /{user}/{chain}/state} for exactly this entry's row numbers (plus the
 * chain's latest row) — upgrades it to <em>anchored</em>. There is no
 * separate type for each state: the same record just carries more.</p>
 *
 * <h2>Verification</h2>
 * <p>Construction itself enforces the anchored case: for each row in
 * {@link #rows()}, its hash (via {@link SourceRow#hash()}, over its
 * possibly-redacted cells) is compared against {@link PathPack}'s input
 * hash at that row number, so an instance with a {@link #state()} present
 * can never disagree with it. Both steps are pure hashing/comparison — no
 * secrets, no server trust beyond the {@code /state} response itself.</p>
 *
 * @param chainUrl full chain URL ({@code <server>/{user}/{chain}})
 * @param rows     the revealed rows, strictly ascending by
 *                 {@link SourceRow#no()} with no duplicates; never empty —
 *                 an entry with nothing to show is simply omitted from the
 *                 receipt
 * @param state    skipledger commitment path covering every row number in
 *                 {@link #rows()}; empty for a draft receipt
 */
public record ChainReceipt(
    String chainUrl,
    List<SourceRow> rows,
    Optional<PathPack> state) {

  public static final Parser PARSER = new Parser();

  /**
   * @throws IllegalArgumentException if {@code rows} is empty, or not
   *         strictly ascending by row number with no duplicates
   * @throws HashConflictException if {@code state} is present and its input
   *         hash at some row number in {@code rows} disagrees with that
   *         row's own {@link SourceRow#hash()}
   */

  public ChainReceipt {
    Objects.requireNonNull(chainUrl, "chainUrl");
    rows = List.copyOf(rows);
    if (rows.isEmpty())
      throw new IllegalArgumentException("rows must not be empty");
    List<Long> rowNos = Lists.map(rows, SourceRow::no);
    if (!Lists.isSortedNoDups(rowNos))
      throw new IllegalArgumentException(
        "source rows must be sorted by row-number and with no duplicates. Row no.s given: " +
        rowNos);

    if (state.isPresent()) {
      // getPath(..) requires >= 2 targets; state is a narrowly-packed PathPack
      // (covering only these rows' numbers, exactly as /state?rows=... and
      // AnchorCommand produce it) so its own lo()/hi() already equal this
      // set's min/max — getFullPath(..)'s lo/hi padding is a no-op here and
      // does not sidestep that restriction. Special-case the single-row rest.
      if (rowNos.size() == 1) {
        var row = state.get().getRow(rowNos.get(0));
        if (!row.inputHash().equals(rows.get(0).hash()))
          throw new HashConflictException(
            "input hash conflict at row[%d] in chain %s"
            .formatted(row.no(), chainUrl));
      } else {
        var crows = state.get().getPath(rowNos).rows();
        var srSeq = rows.iterator();
        SourceRow nextRow = srSeq.next();

        for (var crow : crows) {
          if (crow.no() < nextRow.no())
            continue;

          assert crow.no() == nextRow.no();

          if (!crow.inputHash().equals(nextRow.hash()))
            throw new HashConflictException(
              "input hash conflict at row[%d] in chain %s"
              .formatted(crow.no(), chainUrl));

          if (!srSeq.hasNext())
            break;
          nextRow = srSeq.next();
        }
      }
    }
  }

  /** {@code true} iff this entry's rows are anchored to a commitment path. */
  public boolean isAnchored() {
    return state.isPresent();
  }

  /** The row numbers in {@link #rows()}, in order. */
  public List<Long> rowNumbers() {
    return rows.stream().map(SourceRow::no).toList();
  }

  /** Returns a copy with {@code path} attached as this entry's {@link #state()}. */
  public ChainReceipt withState(Path path) {
    return new ChainReceipt(chainUrl, rows, Optional.of(PathPack.forPath(path)));
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<ChainReceipt> {

    /**
     * Matches the field names {@code GET /{user}/{chain}/state} uses
     * ({@code stitch_rows} / {@code compression} / {@code hashes}; see
     * {@code ChainClient.STATE_PARSER}). Not centralized — {@code
     * PathPackParser} itself defines no constants for these; see the
     * "deduplicate API constants" TODO.
     */
    private static final PathPackParser STATE_PARSER = new PathPackParser(
        HashEncoding.BASE64_32, "stitch_rows", "compression", "hashes");

    @Override
    public JSONObject injectEntity(ChainReceipt cr, JSONObject jObj) {
      jObj.put(CHAIN_URL_KEY, cr.chainUrl());
      JSONArray rowsArr = new JSONArray(cr.rows().size());
      for (SourceRow row : cr.rows()) {
        JSONObject rowObj = SourceRowParser.INSTANCE.toJsonObject(row);
        rowObj.put(SourceRowParser.HASH_KEY, HashEncoding.BASE64_32.encode(row.hash()));
        rowsArr.add(rowObj);
      }
      jObj.put(ROWS_KEY, rowsArr);
      cr.state().ifPresent(pack -> jObj.put(STATE_KEY, STATE_PARSER.toJsonObject(pack)));
      return jObj;
    }

    @Override
    public ChainReceipt toEntity(JSONObject jObj) throws JsonParsingException {
      String chainUrl = JsonUtils.getString(jObj, CHAIN_URL_KEY, true);

      List<SourceRow> rows =
          SourceRowParser.INSTANCE.toEntityList(JsonUtils.getJsonArray(jObj, ROWS_KEY, true));

      JSONObject stateObj = JsonUtils.getJsonObject(jObj, STATE_KEY, false);
      Optional<PathPack> state = stateObj == null
          ? Optional.empty() : Optional.of(STATE_PARSER.toEntity(stateObj));

      return new ChainReceipt(chainUrl, rows, state);
    }
  }
}
