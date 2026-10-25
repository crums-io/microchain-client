/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.receipt;

import static io.crums.micro.api.ApiConstants.*;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.crums.sldg.HashConflictException;
import io.crums.sldg.Path;
import io.crums.sldg.PathPack;
import io.crums.sldg.Row;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.json.PathPackParser;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.json.SourceRowParser;
import io.crums.util.collections.Lists;
import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONArray;
import io.crums.util.json.simple.JSONObject;

/**
 * Revealed source rows from a {@code SourceLedger}, and (once anchored) the
 * skipledger commitment path linking those rows to the ledger's committed
 * state. This is the chain-agnostic building block: it knows nothing about
 * a chain's URL or identity, only about rows and (optionally) a commitment
 * path over them. {@link ChainReceipt} adds the chain identity on top.
 *
 * <h2>Draft vs. anchored</h2>
 * <p>An instance with an empty {@link #state()} is a <em>draft</em>: the row
 * content is asserted but not yet tied to any commitment chain. Attaching a
 * {@link PathPack} upgrades it to <em>anchored</em>. There is no separate
 * type for each state: the same instance just carries more.</p>
 *
 * <h2>Verification</h2>
 * <p>Construction itself enforces the anchored case: for each row in
 * {@link #rows()}, its hash (via {@link SourceRow#hash()}, over its
 * possibly-redacted cells) is compared against {@link PathPack}'s input
 * hash at that row number, so an instance with a {@link #state()} present
 * can never disagree with it. Both steps are pure hashing/comparison — no
 * secrets, no server trust beyond wherever the {@link PathPack} came from.</p>
 */
public class SourceReceipt {

  public static final Parser PARSER = new Parser();

  private final List<SourceRow> rows;
  private final Optional<PathPack> state;

  /**
   * @throws IllegalArgumentException if {@code rows} is not strictly
   *         ascending by row number with no duplicates
   * @throws HashConflictException if {@code state} is present and its input
   *         hash at some row number in {@code rows} disagrees with that
   *         row's own {@link SourceRow#hash()}
   */
  public SourceReceipt(List<SourceRow> rows, Optional<PathPack> state) {
    if (rows.isEmpty())
      throw new IllegalArgumentException("rows must not be empty");
    
    this.rows = List.copyOf(rows);
    this.state = Objects.requireNonNull(state, "state");

    List<Long> rowNos = Lists.map(this.rows, SourceRow::no);
    if (!Lists.isSortedNoDups(rowNos))
      throw new IllegalArgumentException(
        "source rows must be sorted by row-number and with no duplicates. Row no.s given: " +
        rowNos);

    if (state.isPresent())
      verifyAgainstState();
  }

  private void verifyAgainstState() {

    Path commitPath = state.get().path();
    Iterator<Row> commits = commitPath.rows().iterator();
    Iterator<SourceRow> sourceRows = rows.iterator();

    Row nextCommit = commits.next();
    while (sourceRows.hasNext()) {
      SourceRow srcRow = sourceRows.next();

      while (nextCommit.no() < srcRow.no() && commits.hasNext()) {
        nextCommit = commits.next();
      }
      if (nextCommit.no() != srcRow.no())
        throw new IllegalArgumentException(
            stateErrorMessage(srcRow, commitPath));
      
      if (!nextCommit.inputHash().equals(srcRow.hash()))
        throw new HashConflictException(
            "source-row / committed input-hash conflict at row [" + srcRow.no() + "]");
    }
  }

  private String stateErrorMessage(SourceRow srcRow, Path commitPath) {
    return "state must include row [%d]: %s".formatted(srcRow.no(), commitPath.nos());
  }

  /** The revealed rows, strictly ascending by {@link SourceRow#no()} with no duplicates. */
  public List<SourceRow> rows() {
    return rows;
  }

  /** Commitment path covering every row number in {@link #rows()}; empty for a draft. */
  public Optional<PathPack> state() {
    return state;
  }

  /** {@code true} iff this instance's rows are anchored to a commitment path. */
  public boolean isAnchored() {
    return state.isPresent();
  }

  /** The row numbers in {@link #rows()}, in order. */
  public List<Long> rowNumbers() {
    return Lists.map(rows, SourceRow::no);
  }

  /** Returns a copy with {@code path} attached as this instance's {@link #state()}. */
  public SourceReceipt withState(Path path) {
    return new SourceReceipt(rows, Optional.of(PathPack.forPath(path)));
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<SourceReceipt> {

    /**
     * Matches the field names {@code GET /{user}/{chain}/state} uses
     * ({@code stitch_rows} / {@code compression} / {@code hashes}; see
     * {@code ChainClient.STATE_PARSER}). Not centralized — {@code
     * PathPackParser} itself defines no constants for these; see the
     * "deduplicate API constants" TODO.
     */
    static final PathPackParser STATE_PARSER = new PathPackParser(
        HashEncoding.BASE64_32, "stitch_rows", "compression", "hashes");

    @Override
    public JSONObject injectEntity(SourceReceipt sr, JSONObject jObj) {
      JSONArray rowsArr = new JSONArray(sr.rows().size());
      for (SourceRow row : sr.rows()) {
        JSONObject rowObj = SourceRowParser.INSTANCE.toJsonObject(row);
        rowObj.put(SourceRowParser.HASH_KEY, HashEncoding.BASE64_32.encode(row.hash()));
        rowsArr.add(rowObj);
      }
      jObj.put(ROWS_KEY, rowsArr);
      sr.state().ifPresent(pack -> jObj.put(STATE_KEY, STATE_PARSER.toJsonObject(pack)));
      return jObj;
    }

    @Override
    public SourceReceipt toEntity(JSONObject jObj) throws JsonParsingException {
      return new SourceReceipt(parseRows(jObj), parseState(jObj));
    }

    /** Exposed so subtype parsers (e.g. {@link ChainReceipt.Parser}) can reuse field parsing
     *  without round-tripping through a throwaway {@code SourceReceipt} (which would re-run
     *  the hash-conflict check a second time). */
    static List<SourceRow> parseRows(JSONObject jObj) throws JsonParsingException {
      return SourceRowParser.INSTANCE.toEntityList(JsonUtils.getJsonArray(jObj, ROWS_KEY, true));
    }

    static Optional<PathPack> parseState(JSONObject jObj) throws JsonParsingException {
      JSONObject stateObj = JsonUtils.getJsonObject(jObj, STATE_KEY, false);
      return stateObj == null
          ? Optional.empty() : Optional.of(STATE_PARSER.toEntity(stateObj));
    }
  }
}
