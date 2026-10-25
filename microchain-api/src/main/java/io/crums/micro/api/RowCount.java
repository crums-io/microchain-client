/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * A plain row count ({@code {"row_count": N}}). Used both as the response
 * from {@code POST /{user}/{chain}/commit} (rows committed to the chain
 * after that write) and as the response from a plain read of a {@code
 * SourceLedger}'s current size -- same shape either way, since both are,
 * at bottom, just "how many rows."
 *
 * @param rowCount &ge; 0
 */
public record RowCount(long rowCount) {

  public static final Parser PARSER = new Parser();

  public static class Parser implements JsonEntityParser<RowCount> {

    @Override
    public JSONObject injectEntity(RowCount resp, JSONObject jObj) {
      jObj.put(ROW_COUNT_KEY, resp.rowCount());
      return jObj;
    }

    @Override
    public RowCount toEntity(JSONObject jObj) throws JsonParsingException {
      long rowCount = JsonUtils.getNumber(jObj, ROW_COUNT_KEY, true).longValue();
      return new RowCount(rowCount);
    }
  }
}
