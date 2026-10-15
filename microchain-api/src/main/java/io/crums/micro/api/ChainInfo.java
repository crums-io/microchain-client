/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.util.Optional;

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * Public chain metadata from {@code GET /{user}/{chain}/info}.
 *
 * <p>Maps to {@code InfoResponse} in {@code ChainInfoResource}.
 *
 * @param ledgerType  1 = SQL ledger, 2 = log-file ledger
 * @param title       optional human-readable title
 * @param description optional description
 * @param salted      whether this chain's source rows are salted
 */
public record ChainInfo(
    int ledgerType,
    Optional<String> title,
    Optional<String> description,
    boolean salted) {

  public static final Parser PARSER = new Parser();

  public static class Parser implements JsonEntityParser<ChainInfo> {

    @Override
    public JSONObject injectEntity(ChainInfo info, JSONObject jObj) {
      jObj.put(LEDGER_TYPE_KEY, (long) info.ledgerType());
      info.title().ifPresent(t -> jObj.put(TITLE_KEY, t));
      info.description().ifPresent(d -> jObj.put(DESCRIPTION_KEY, d));
      jObj.put(SALTED_KEY, info.salted());
      return jObj;
    }

    @Override
    public ChainInfo toEntity(JSONObject jObj) throws JsonParsingException {
      int ledgerType  = JsonUtils.getNumber(jObj, LEDGER_TYPE_KEY, true).intValue();
      var title       = Optional.ofNullable(JsonUtils.getString(jObj, TITLE_KEY,       false));
      var description = Optional.ofNullable(JsonUtils.getString(jObj, DESCRIPTION_KEY, false));
      boolean salted  = JsonUtils.getBoolean(jObj, SALTED_KEY);
      return new ChainInfo(ledgerType, title, description, salted);
    }
  }
}
