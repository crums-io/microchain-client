/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.receipt;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.crums.sldg.HashConflictException;
import io.crums.sldg.Path;
import io.crums.sldg.PathPack;
import io.crums.sldg.SkipLedger;
import io.crums.sldg.SkipLedgers;
import io.crums.sldg.salt.TableSalt;
import io.crums.sldg.src.SaltScheme;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.SourceRowBuilder;

/**
 * Tests {@link ChainReceipt}'s constructor-time validation (sorted/no-dup
 * rows, and — when {@code state} is present — that each row's hash agrees
 * with the attached commitment path) against a <em>real</em> in-memory
 * skipledger, rather than mocks.
 *
 * <h2>Fixture</h2>
 * <p>For each mock chain: 10 source rows numbered 100-109 are built via
 * {@link SourceRowBuilder} (not from a DB — that layer isn't under test
 * here); 99 filler rows (1-99, random input hashes) are committed to an
 * in-memory {@link SkipLedger} first, then the 10 real rows' hashes, so the
 * receipt's rows sit well past row 1 — the realistic case, since a receipt
 * is almost never anchored at the very start of a chain. A path covering
 * exactly rows 100-109 is then fetched and packed, matching exactly what
 * {@code mchain receipt anchor} itself requests (target rows + "last" —
 * never "row 1" unless it happens to be a target).</p>
 *
 * <h2>Salt coverage</h2>
 * <p>Only {@link SaltScheme#NO_SALT} and {@link SaltScheme#SALT_ALL} are
 * exercised here, per direction — mixed salt schemes have known issues
 * elsewhere that are to be fixed or simplified away separately, so are
 * out of scope for this test.</p>
 */
public class ChainReceiptTest {

  private static final String CHAIN_URL = "https://micro.crums.io/bikeshop/orders";

  private static byte[] seed(int fill) {
    byte[] b = new byte[32];
    Arrays.fill(b, (byte) fill);
    return b;
  }


  // ── fixture builders ─────────────────────────────────────────────────────

  /** Builds 10 source rows numbered 100-109 using the given salt scheme. */
  private static List<SourceRow> buildRealRows(SaltScheme scheme, TableSalt salt) {
    SourceRowBuilder builder = new SourceRowBuilder(scheme, salt);
    List<SourceRow> rows = new ArrayList<>(10);
    for (long no = 100; no <= 109; no++)
      rows.add(builder.buildRow(no, "item-" + no, no * 7L));
    return rows;
  }

  /**
   * Commits 99 filler rows (random input hashes, rows 1-99), then the given
   * real rows' hashes (rows 100-109), to a fresh in-memory skipledger.
   */
  private static SkipLedger buildLedger(List<SourceRow> realRows) {
    SkipLedger ledger = SkipLedgers.inMemoryInstance();
    Random rnd = new Random(42);
    byte[] mock = new byte[32];
    for (int i = 0; i < 99; i++) {
      rnd.nextBytes(mock);
      ledger.appendRows(ByteBuffer.wrap(mock));
    }
    for (SourceRow row : realRows)
      ledger.appendRows(row.hash());
    return ledger;
  }

  /**
   * Fetches a path covering exactly {@code targetRows}' row numbers from
   * {@code ledger} (not "from row 1"), and packs it — the same shape
   * {@code mchain receipt anchor} itself produces via {@code GET /state}.
   */
  private static PathPack anchorPack(SkipLedger ledger, List<SourceRow> targetRows) {
    List<Long> rowNos = targetRows.stream().map(SourceRow::no).toList();
    Path path = ledger.getPath(rowNos);
    return PathPack.forPath(path);
  }

  /** Convenience: builds a fresh ledger for exactly {@code realRows}, then packs all of them. */
  private static PathPack anchorPack(List<SourceRow> realRows) {
    return anchorPack(buildLedger(realRows), realRows);
  }


  // ── single-chain, anchored ───────────────────────────────────────────────

  @Test
  public void testAnchored_noSalt_multiRow_succeeds() {
    List<SourceRow> rows = buildRealRows(SaltScheme.NO_SALT, null);
    PathPack pack = anchorPack(rows);

    ChainReceipt cr = new ChainReceipt(CHAIN_URL, rows, Optional.of(pack));
    assertTrue(cr.isAnchored());
    assertEquals(10, cr.rows().size());
  }

  @Test
  public void testAnchored_saltAll_multiRow_succeeds() {
    TableSalt salt = new TableSalt(seed(0xAB));
    List<SourceRow> rows = buildRealRows(SaltScheme.SALT_ALL, salt);
    PathPack pack = anchorPack(rows);

    ChainReceipt cr = new ChainReceipt(CHAIN_URL, rows, Optional.of(pack));
    assertTrue(cr.isAnchored());
  }

  @Test
  public void testAnchored_noSalt_singleRow_succeeds() {
    // Exactly the case the earlier `getPath` (min-2-targets) bug broke.
    // The ledger carries the full 100-109 batch (so row 103 really is the
    // ledger's row 103, not just the 1st row appended); only row 103 is
    // revealed in the receipt.
    List<SourceRow> allRows = buildRealRows(SaltScheme.NO_SALT, null);
    SkipLedger ledger = buildLedger(allRows);
    List<SourceRow> oneRow = List.of(allRows.get(3)); // row 103, alone
    PathPack pack = anchorPack(ledger, oneRow);

    ChainReceipt cr = new ChainReceipt(CHAIN_URL, oneRow, Optional.of(pack));
    assertTrue(cr.isAnchored());
    assertEquals(1, cr.rows().size());
  }

  @Test
  public void testAnchored_saltAll_singleRow_succeeds() {
    TableSalt salt = new TableSalt(seed(0xCD));
    List<SourceRow> allRows = buildRealRows(SaltScheme.SALT_ALL, salt);
    SkipLedger ledger = buildLedger(allRows);
    List<SourceRow> oneRow = List.of(allRows.get(7)); // row 107, alone
    PathPack pack = anchorPack(ledger, oneRow);

    ChainReceipt cr = new ChainReceipt(CHAIN_URL, oneRow, Optional.of(pack));
    assertTrue(cr.isAnchored());
  }


  // ── tamper detection ──────────────────────────────────────────────────────

  @Test
  public void testAnchored_tamperedMiddleRow_throwsHashConflict() {
    List<SourceRow> rows = buildRealRows(SaltScheme.NO_SALT, null);
    PathPack pack = anchorPack(rows); // path committed against the ORIGINAL rows

    // Swap in a row with different cell content (and thus a different hash)
    // at the same row number — simulates a tampered/mismatched receipt.
    SourceRowBuilder builder = new SourceRowBuilder(SaltScheme.NO_SALT, null);
    SourceRow tampered = builder.buildRow(104L, "TAMPERED", -1L);
    List<SourceRow> badRows = new ArrayList<>(rows);
    badRows.set(4, tampered); // rows[4].no() == 104

    assertThrows(HashConflictException.class,
        () -> new ChainReceipt(CHAIN_URL, badRows, Optional.of(pack)));
  }

  @Test
  public void testAnchored_tamperedLastRow_throwsHashConflict() {
    List<SourceRow> rows = buildRealRows(SaltScheme.NO_SALT, null);
    PathPack pack = anchorPack(rows);

    SourceRowBuilder builder = new SourceRowBuilder(SaltScheme.NO_SALT, null);
    SourceRow tampered = builder.buildRow(109L, "TAMPERED", -1L);
    List<SourceRow> badRows = new ArrayList<>(rows);
    badRows.set(9, tampered); // rows[9].no() == 109, the last row

    assertThrows(HashConflictException.class,
        () -> new ChainReceipt(CHAIN_URL, badRows, Optional.of(pack)));
  }

  @Test
  public void testAnchored_tamperedFirstRow_throwsHashConflict() {
    List<SourceRow> rows = buildRealRows(SaltScheme.NO_SALT, null);
    PathPack pack = anchorPack(rows);

    SourceRowBuilder builder = new SourceRowBuilder(SaltScheme.NO_SALT, null);
    SourceRow tampered = builder.buildRow(100L, "TAMPERED", -1L);
    List<SourceRow> badRows = new ArrayList<>(rows);
    badRows.set(0, tampered); // rows[0].no() == 100, the first row

    assertThrows(HashConflictException.class,
        () -> new ChainReceipt(CHAIN_URL, badRows, Optional.of(pack)));
  }


  // ── structural validation (no state needed) ──────────────────────────────

  @Test
  public void testEmptyRowsThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> new ChainReceipt(CHAIN_URL, List.of(), Optional.empty()));
  }

  @Test
  public void testOutOfOrderRowsThrows() {
    List<SourceRow> rows = buildRealRows(SaltScheme.NO_SALT, null);
    List<SourceRow> shuffled = new ArrayList<>(rows);
    // swap two rows out of ascending order
    SourceRow tmp = shuffled.get(0);
    shuffled.set(0, shuffled.get(1));
    shuffled.set(1, tmp);

    assertThrows(IllegalArgumentException.class,
        () -> new ChainReceipt(CHAIN_URL, shuffled, Optional.empty()));
  }

  @Test
  public void testDuplicateRowNumbersThrows() {
    List<SourceRow> rows = buildRealRows(SaltScheme.NO_SALT, null);
    List<SourceRow> withDup = new ArrayList<>(rows);
    withDup.set(1, withDup.get(0)); // duplicate row no. at index 1

    assertThrows(IllegalArgumentException.class,
        () -> new ChainReceipt(CHAIN_URL, withDup, Optional.empty()));
  }


  // ── draft (no state) ──────────────────────────────────────────────────────

  @Test
  public void testDraft_noStateRequiredForConstruction() {
    List<SourceRow> rows = buildRealRows(SaltScheme.NO_SALT, null);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, rows, Optional.empty());
    assertFalse(cr.isAnchored());
  }


  // ── multi-chain receipt ────────────────────────────────────────────────────

  @Test
  public void testMultiChainReceipt_bothAnchored() {
    List<SourceRow> ordersRows = buildRealRows(SaltScheme.NO_SALT, null);
    ChainReceipt orders = new ChainReceipt(
        "https://micro.crums.io/bikeshop/orders", ordersRows,
        Optional.of(anchorPack(ordersRows)));

    TableSalt salt = new TableSalt(seed(0x42));
    List<SourceRow> customersRows = buildRealRows(SaltScheme.SALT_ALL, salt);
    ChainReceipt customers = new ChainReceipt(
        "https://micro.crums.io/bikeshop/customers", customersRows,
        Optional.of(anchorPack(customersRows)));

    Receipt receipt = new Receipt(List.of(orders, customers));
    assertTrue(receipt.isFullyAnchored());
    assertEquals(2, receipt.chains().size());
  }
}
