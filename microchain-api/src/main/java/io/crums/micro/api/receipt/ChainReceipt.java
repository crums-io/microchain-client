/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.receipt;

import static io.crums.micro.api.ApiConstants.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.crums.sldg.Path;
import io.crums.sldg.PathPack;
import io.crums.sldg.src.SourceRow;
import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * One chain's contribution to a {@link Receipt}: a {@link SourceReceipt}
 * (revealed rows, optionally anchored) tied to a specific chain's URL.
 *
 * <p>{@link #rows()} is always non-empty.</p>
 *
 * @see SourceReceipt for the draft/anchored distinction and the
 *      hash-verification rules enforced at construction
 */
public class ChainReceipt extends SourceReceipt {

  public static final Parser PARSER = new Parser();

  private final String chainUrl;

  /**
   * @throws IllegalArgumentException if {@code rows} is empty, or not
   *         strictly ascending by row number with no duplicates
   * @throws io.crums.sldg.HashConflictException if {@code state} is present
   *         and its input hash at some row number in {@code rows} disagrees
   *         with that row's own {@link SourceRow#hash()}
   */
  public ChainReceipt(String chainUrl, List<SourceRow> rows, Optional<PathPack> state) {
    super(rows, state);
    this.chainUrl = Objects.requireNonNull(chainUrl, "chainUrl");
    if (rows().isEmpty())
      throw new IllegalArgumentException("rows must not be empty");
  }

  /** Full chain URL ({@code <server>/{user}/{chain}}). */
  public String chainUrl() {
    return chainUrl;
  }

  /** Returns a copy with {@code path} attached as this entry's {@link #state()}. */
  public ChainReceipt withState(Path path) {
    return new ChainReceipt(chainUrl, rows(), Optional.of(PathPack.forPath(path)));
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<ChainReceipt> {

    @Override
    public JSONObject injectEntity(ChainReceipt cr, JSONObject jObj) {
      jObj.put(CHAIN_URL_KEY, cr.chainUrl());
      return SourceReceipt.PARSER.injectEntity(cr, jObj);
    }

    @Override
    public ChainReceipt toEntity(JSONObject jObj) throws JsonParsingException {
      String chainUrl = JsonUtils.getString(jObj, CHAIN_URL_KEY, true);
      List<SourceRow> rows = SourceReceipt.Parser.parseRows(jObj);
      Optional<PathPack> state = SourceReceipt.Parser.parseState(jObj);
      return new ChainReceipt(chainUrl, rows, state);
    }
  }
}
