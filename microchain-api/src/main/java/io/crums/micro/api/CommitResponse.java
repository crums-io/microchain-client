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
 * Response from {@code POST /{user}/{chain}/commit}.
 *
 * <p>Maps to {@code CommitResponse} in {@code CommitResource}.
 *
 * @param rowCount total number of rows committed to the chain after this operation
 */
public record CommitResponse(long rowCount) {

  public static final Parser PARSER = new Parser();

  public static class Parser implements JsonEntityParser<CommitResponse> {

    @Override
    public JSONObject injectEntity(CommitResponse resp, JSONObject jObj) {
      jObj.put(ROW_COUNT_KEY, resp.rowCount());
      return jObj;
    }

    @Override
    public CommitResponse toEntity(JSONObject jObj) throws JsonParsingException {
      long rowCount = JsonUtils.getNumber(jObj, ROW_COUNT_KEY, true).longValue();
      return new CommitResponse(rowCount);
    }
  }
}
