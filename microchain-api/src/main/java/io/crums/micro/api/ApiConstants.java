/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import io.crums.sldg.src.json.EpochSeedParser;

/** JSON field name constants shared across all microchain REST API entities. */
public class ApiConstants {

  // /{user}/{chain}/sql-config
  public static final String BY_NO_QUERY_KEY       = "by_no_query";
  public static final String ROW_COUNT_QUERY_KEY   = "row_count_query";
  public static final String JDBC_URL_KEY          = "jdbc_url";
  public static final String JDBC_DRIVER_CLASS_KEY = "jdbc_driver_class";
  public static final String JDBC_DRIVER_JAR_KEY   = "jdbc_driver_jar";
  public static final String JDBC_PROPS_KEY        = "jdbc_props";
  public static final String TIMEZONE_KEY          = "timezone";
  public static final String DEFAULT_LAG_KEY       = "default_lag";

  // /{user}/{chain}/info
  public static final String LEDGER_TYPE_KEY  = "ledger_type";
  public static final String TITLE_KEY        = "title";
  public static final String DESCRIPTION_KEY  = "description";
  public static final String SALTED_KEY       = "salted";

  // /{user}/{chain}/columns
  public static final String COLUMNS_KEY             = "columns";
  public static final String COLUMN_INDEX_KEY        = "index";
  public static final String COLUMN_NAME_KEY         = "name";
  public static final String COLUMN_DESCRIPTION_KEY  = "description";

  // /{user}/{chain}/salts  (owned by EpochSeedParser)
  public static final String SEED_START_KEY = EpochSeedParser.SEED_START_KEY;
  public static final String SEED_KEY       = EpochSeedParser.SEED_KEY;

  // /{user}/{chain}/commit
  public static final String START_ROW_KEY    = "start_row";
  public static final String INPUT_HASHES_KEY = "input_hashes";
  public static final String ROW_COUNT_KEY    = "row_count";

  // local config file
  public static final String CHAIN_URL_KEY    = "chain_url";
  public static final String ACCESS_TOKEN_KEY = "access_token";
  public static final String SALTS_KEY        = "salts";

  // local config file — 'chains' object and shared-default URL prefix
  public static final String CHAINS_KEY         = "chains";
  public static final String BASE_CHAIN_URL_KEY = "base_chain_url";

  // local config file — 'receipt_templates' object
  public static final String RECEIPT_TEMPLATES_KEY = "receipt_templates";
  public static final String CHAIN_KEY             = "chain";
  public static final String WHERE_KEY             = "where";
  public static final String ARGS_KEY              = "args";

  // receipt JSON (draft and anchored)
  public static final String ROWS_KEY  = "rows";
  public static final String STATE_KEY = "state";

  // ChainReceipt JSON -- distinct from CHAIN_URL_KEY (local config file):
  // a ChainReceipt's identifier need not be an http(s) URL.
  public static final String CHAIN_URI_KEY = "chain_uri";

  // /{user}/_receipt_templates
  public static final String TEMPLATE_NAME_KEY         = "name";
  public static final String TEMPLATE_CONTENT_KEY      = "content";
  public static final String TEMPLATE_CONTENT_HASH_KEY = "content_hash";
  public static final String TEMPLATE_VERSION_KEY      = "version";
  public static final String TEMPLATE_CHAIN_ROW_KEY    = "chain_row_number";
  public static final String TEMPLATE_PUBLISHED_AT_KEY = "published_at";
  // publish request only, never returned: a Receipt the server must
  // successfully render against the submitted content before accepting it
  public static final String TEMPLATE_TEST_RECEIPT_KEY = "test_receipt";

  private ApiConstants() { }
}
