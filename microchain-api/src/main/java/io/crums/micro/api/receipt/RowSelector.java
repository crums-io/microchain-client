/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.receipt;

import static io.crums.micro.api.ApiConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONArray;
import io.crums.util.json.simple.JSONObject;

/**
 * One chain's row-selection step within a {@link ReceiptTemplate}.
 *
 * <h2>Syntax</h2>
 * <p>{@code where} is ordinary, valid SQL — the same shape {@code mchain
 * rows --where} accepts, with zero or more {@code ?} placeholders. {@code
 * args} supplies exactly one value per {@code ?}, each either:</p>
 * <ul>
 *   <li>{@code ROW_NO(:alias)} — the row number of the single row already
 *       resolved for the earlier chain entry {@code alias}</li>
 *   <li>{@code :alias.column} — a named column's value from that same
 *       already-resolved row</li>
 * </ul>
 * <p>Both forms require {@code alias} to name a chain declared <em>earlier</em>
 * in the same template's {@link ReceiptTemplate#chains()} list, and both
 * resolve to "no value" (propagating to zero rows for this entry, never an
 * error) unless that earlier chain resolved to <em>exactly one</em> row.</p>
 *
 * <h2>The first chain</h2>
 * <p>Only the first entry in a template may omit {@code args}: when it does,
 * every {@code ?} in its {@code where} (or, if {@code where} itself is
 * absent, the single caller-supplied input directly as a row number) binds
 * straight to the caller-supplied generation-time inputs, in order. That
 * first entry's selector must resolve to exactly one row — zero or more
 * than one is an error, since every later entry's {@code ROW_NO(:first)} /
 * {@code :first.column} ultimately traces back to it. Every other entry
 * fully specifies {@code args} and may resolve to any number of rows.</p>
 *
 * <p>{@code chain} names a local alias — it must match a key already
 * present under the same local config file's {@code chains} object, from
 * which the JDBC connection, queries, and {@code chain_url} for this entry
 * are all drawn; there is no separate override here.</p>
 *
 * @param chain local alias, matching a key under the local config file's
 *              {@code chains} object
 * @param where SQL WHERE predicate (no leading {@code WHERE} keyword
 *              required); absent only for the first entry, meaning "look
 *              up by row number directly"
 * @param args  one derivation expression per {@code ?} in {@code where};
 *              empty when {@code where} is absent, or (first entry only)
 *              when every {@code ?} binds to a caller-supplied input
 */
public record RowSelector(
    String chain,
    Optional<String> where,
    List<String> args) {

  public static final Parser PARSER = new Parser();

  public RowSelector {
    Objects.requireNonNull(chain, "chain");
    args = List.copyOf(args);
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<RowSelector> {

    @Override
    public JSONObject injectEntity(RowSelector sel, JSONObject jObj) {
      jObj.put(CHAIN_KEY, sel.chain());
      sel.where().ifPresent(w -> jObj.put(WHERE_KEY, w));
      if (!sel.args().isEmpty()) {
        JSONArray arr = new JSONArray(sel.args().size());
        arr.addAll(sel.args());
        jObj.put(ARGS_KEY, arr);
      }
      return jObj;
    }

    @Override
    public RowSelector toEntity(JSONObject jObj) throws JsonParsingException {
      String chain = JsonUtils.getString(jObj, CHAIN_KEY, true);
      Optional<String> where = Optional.ofNullable(
          JsonUtils.getString(jObj, WHERE_KEY, false));

      List<String> args = List.of();
      JSONArray argsArr = JsonUtils.getJsonArray(jObj, ARGS_KEY, false);
      if (argsArr != null) {
        var list = new ArrayList<String>(argsArr.size());
        for (Object o : argsArr) list.add(String.valueOf(o));
        args = list;
      }

      return new RowSelector(chain, where, args);
    }
  }
}
