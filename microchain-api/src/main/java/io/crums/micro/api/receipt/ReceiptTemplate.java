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
 * A named, reusable recipe for generating a {@link Receipt}: an ordered
 * list of {@link RowSelector}s, the first of which is the "driver" fed
 * directly by the caller's generation-time inputs, with every later entry
 * deriving its own selector's bound values from rows already resolved for
 * an earlier entry. See {@link RowSelector} for the full binding rules.
 *
 * <p>Declared under a local config file's {@code receipt_templates} object,
 * keyed by template name:</p>
 * <pre>{@code
 * {
 *   "receipt_templates": {
 *     "order-receipt": {
 *       "chains": [
 *         { "chain": "orders" },
 *         { "chain": "order_items", "where": "order_id = ?", "args": ["ROW_NO(:orders)"] },
 *         { "chain": "customers",   "where": "customer_id = ?", "args": [":orders.customer_id"] }
 *       ]
 *     }
 *   }
 * }
 * }</pre>
 *
 * @param chains the template's row selectors, in resolution order
 */
public record ReceiptTemplate(List<RowSelector> chains) {

  public static final Parser PARSER = new Parser();

  public ReceiptTemplate {
    chains = List.copyOf(chains);
    if (chains.isEmpty())
      throw new IllegalArgumentException("a receipt template must declare at least one chain");
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<ReceiptTemplate> {

    @Override
    public JSONObject injectEntity(ReceiptTemplate tmpl, JSONObject jObj) {
      jObj.put(CHAINS_KEY, RowSelector.PARSER.toJsonArray(tmpl.chains()));
      return jObj;
    }

    @Override
    public ReceiptTemplate toEntity(JSONObject jObj) throws JsonParsingException {
      List<RowSelector> chains =
          RowSelector.PARSER.toEntityList(JsonUtils.getJsonArray(jObj, CHAINS_KEY, true));
      return new ReceiptTemplate(chains);
    }
  }
}
