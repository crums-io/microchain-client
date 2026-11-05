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
 * One or more related ledger source-rows, grouped by chain -- the data
 * backing a tamper-evident "receipt" (see the project README's "Tamper-
 * evident receipts" section).
 *
 * <p>A fresh receipt is a <em>draft</em>: every {@link ChainReceipt} entry's
 * {@link ChainReceipt#state()} is empty. Attaching a commitment path per
 * entry (fetched from each chain's {@code GET /state}, or in one round
 * trip (via the {@code /_verify} endpoint) upgrades it to
 * <em>anchored</em> -- see {@link ChainReceipt}.</p>
 */
public class Receipt {

  public static final Parser PARSER = new Parser();

  private final List<ChainReceipt> chains;

  public Receipt(List<ChainReceipt> chains) {
    if (chains.isEmpty())
      throw new IllegalArgumentException("empty chains");
    // TODO: verify no duplicate chain-URLs
    this.chains = List.copyOf(chains);
  }

  /** Returns {@code true} iff every chain is anchored. */
  public boolean isFullyAnchored() {
    return chains.stream().allMatch(ChainReceipt::isAnchored);
  }

  /**
   * Returns the list of chain-receipts (source-rows, possibly anchored
   * (annotated) with chain-proofs).
   * 
   * @return not empty
   */
  public final List<ChainReceipt> chains() {
    return chains;
  }

  /** Per {@link List#equals(Object)}: order-sensitive -- two receipts holding the same
   *  {@link ChainReceipt} entries in a different order are not equal. */
  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (!(o instanceof Receipt other))
      return false;
    return chains.equals(other.chains);
  }

  /** Consistent with {@link #equals(Object)} -- delegates to {@link List#hashCode()}. */
  @Override
  public int hashCode() {
    return chains.hashCode();
  }


  // -- JSON parser -----------------------------------------------------------

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
