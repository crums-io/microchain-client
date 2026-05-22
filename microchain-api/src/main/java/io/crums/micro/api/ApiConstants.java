/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

import io.crums.sldg.src.json.EpochSeedParser;
import io.crums.sldg.src.json.SaltSchemeParser;

/** JSON field name constants shared across all microchain REST API entities. */
public class ApiConstants {

  // /{user}/{chain}/sql-config
  public static final String BY_NO_QUERY_KEY       = "by_no_query";
  public static final String ROW_COUNT_QUERY_KEY   = "row_count_query";
  public static final String JDBC_URL_KEY          = "jdbc_url";
  public static final String JDBC_DRIVER_CLASS_KEY = "jdbc_driver_class";

  // /{user}/{chain}/info
  public static final String LEDGER_TYPE_KEY  = "ledger_type";
  public static final String TITLE_KEY        = "title";
  public static final String DESCRIPTION_KEY  = "description";
  public static final String SALT_SCHEME_KEY  = "salt_scheme";

  // /{user}/{chain}/info → salt_scheme object  (owned by SaltSchemeParser)
  public static final String SALT_CODE_KEY    = SaltSchemeParser.SALT_CODE_KEY;
  public static final String CELL_INDICES_KEY = SaltSchemeParser.CELL_INDICES_KEY;

  // /{user}/{chain}/salts  (owned by EpochSeedParser)
  public static final String SEED_START_KEY = EpochSeedParser.SEED_START_KEY;
  public static final String SEED_KEY       = EpochSeedParser.SEED_KEY;

  // /{user}/{chain}/commit
  public static final String START_ROW_KEY    = "start_row";
  public static final String INPUT_HASHES_KEY = "input_hashes";
  public static final String ROW_COUNT_KEY    = "row_count";

  private ApiConstants() { }
}
