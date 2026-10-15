/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import static io.crums.micro.api.ApiConstants.*;

import java.util.Objects;
import java.util.Optional;

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * Decorative metadata for a single SQL ledger column: a human-readable name
 * and optional description, keyed by the column's 0-based position in
 * {@code SourceRow.cells()}.
 *
 * <p>Public, non-sensitive information — set by the chain owner via
 * {@code PUT /{user}/{chain}/columns} and readable by anyone via
 * {@code GET /{user}/{chain}/columns}, since it's needed to render receipts
 * (e.g. {@code /verify}, Qute templates) for recipients who hold a source
 * row but no bearer token.</p>
 *
 * <p>Absent a configured name for a given index, consumers fall back to
 * whatever the JDBC driver reports via {@code ResultSetMetaData}, and
 * finally to the bare positional index.</p>
 *
 * @param index       0-based column position
 * @param name        human-readable column name; never blank
 * @param description optional longer explanation of the column's meaning
 */
public record ColumnInfo(
    int index,
    String name,
    Optional<String> description) {

  public ColumnInfo {
    if (index < 0)
      throw new IllegalArgumentException("index < 0: " + index);
    Objects.requireNonNull(description, "description");
    name = name.strip();
    if (name.isBlank())
      throw new IllegalArgumentException("blank name at index " + index);
  }

  public static final Parser PARSER = new Parser();

  public static class Parser implements JsonEntityParser<ColumnInfo> {

    @Override
    public JSONObject injectEntity(ColumnInfo col, JSONObject jObj) {
      jObj.put(COLUMN_INDEX_KEY, (long) col.index());
      jObj.put(COLUMN_NAME_KEY, col.name());
      col.description().ifPresent(d -> jObj.put(COLUMN_DESCRIPTION_KEY, d));
      return jObj;
    }

    @Override
    public ColumnInfo toEntity(JSONObject jObj) throws JsonParsingException {
      int index = JsonUtils.getNumber(jObj, COLUMN_INDEX_KEY, true).intValue();
      String name = JsonUtils.getString(jObj, COLUMN_NAME_KEY, true);
      var description = Optional.ofNullable(
          JsonUtils.getString(jObj, COLUMN_DESCRIPTION_KEY, false));
      return new ColumnInfo(index, name, description);
    }
  }
}
