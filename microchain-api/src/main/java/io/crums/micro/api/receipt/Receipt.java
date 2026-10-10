/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.receipt;

import static io.crums.micro.api.ApiConstants.*;

import java.util.List;

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * One or more related ledger source-rows, grouped by chain — the data
 * backing a tamper-evident "receipt" (see the project README's "Tamper-
 * evident receipts" section).
 *
 * <p>A fresh receipt is a <em>draft</em>: every {@link ChainReceipt} entry's
 * {@link ChainReceipt#state()} is empty. Attaching a commitment path per
 * entry (fetched from each chain's {@code GET /state}, or in one round
 * trip via a future {@code /_verify} endpoint) upgrades it to
 * <em>anchored</em> — see {@link ChainReceipt}.</p>
 *
 * @param chains one entry per distinct chain contributing rows, in the
 *               order they were resolved
 */
public record Receipt(List<ChainReceipt> chains) {

  public static final Parser PARSER = new Parser();

  public Receipt {
    chains = List.copyOf(chains);
  }

  /** {@code true} iff every entry is anchored. */
  public boolean isFullyAnchored() {
    return chains.stream().allMatch(ChainReceipt::isAnchored);
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<Receipt> {

    @Override
    public JSONObject injectEntity(Receipt receipt, JSONObject jObj) {
      jObj.put(CHAINS_KEY, ChainReceipt.PARSER.toJsonArray(receipt.chains()));
      return jObj;
    }

    @Override
    public Receipt toEntity(JSONObject jObj) throws JsonParsingException {
      List<ChainReceipt> chains =
          ChainReceipt.PARSER.toEntityList(JsonUtils.getJsonArray(jObj, CHAINS_KEY, true));
      return new Receipt(chains);
    }
  }
}
