/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.util.List;

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONArray;
import io.crums.util.json.simple.JSONObject;

/**
 * Request body for {@code POST /{user}/{chain}/commit}.
 *
 * <p>Maps to {@code CommitRequest} in {@code CommitResource}.
 * The commit endpoint is idempotent and does not permit gaps: the client
 * always starts from the last committed row number as a sanity check.
 *
 * @param startRow     1-based row number of the first hash in {@code inputHashes}
 * @param inputHashes  non-empty list of 43-character Base64_32-encoded SHA-256 hashes
 */
public record CommitRequest(long startRow, List<String> inputHashes) {

  public static final Parser PARSER = new Parser();

  public CommitRequest {
    if (startRow < 1)
      throw new IllegalArgumentException("startRow must be >= 1, got: " + startRow);
    if (inputHashes == null || inputHashes.isEmpty())
      throw new IllegalArgumentException("inputHashes must be non-empty");
    inputHashes = List.copyOf(inputHashes);
  }

  public static class Parser implements JsonEntityParser<CommitRequest> {

    @SuppressWarnings("unchecked")
    @Override
    public JSONObject injectEntity(CommitRequest req, JSONObject jObj) {
      jObj.put(START_ROW_KEY, req.startRow());
      var arr = new JSONArray();
      arr.addAll(req.inputHashes());
      jObj.put(INPUT_HASHES_KEY, arr);
      return jObj;
    }

    @Override
    public CommitRequest toEntity(JSONObject jObj) throws JsonParsingException {
      long startRow = JsonUtils.getNumber(jObj, START_ROW_KEY, true).longValue();
      JSONArray arr = (JSONArray) jObj.get(INPUT_HASHES_KEY);
      if (arr == null || arr.isEmpty())
        throw new JsonParsingException("'" + INPUT_HASHES_KEY + "' must be a non-empty array");
      List<String> hashes = arr.stream()
          .map(Object::toString)
          .toList();
      try {
        return new CommitRequest(startRow, hashes);
      } catch (Exception x) {
        throw new JsonParsingException(x);
      }
    }
  }
}
