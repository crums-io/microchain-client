/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;


import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import io.crums.micro.api.receipt.ChainReceipt;
import io.crums.micro.api.receipt.Receipt;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.SourceRowView;
import io.crums.util.collections.Lists;

/**
 * A {@link Receipt} to be rendered, paired with the {@link
 * ReceiptRenderSettings} controlling how its cells are displayed. A pure
 * argument type: carries no knowledge of Qute (or any other templating
 * engine) -- that plumbing lives entirely in the unexported {@code
 * io.crums.micro.api.render.engine} package.
 */
public final class ReceiptViewModel {

  /**
   * Generous default cap on cells touched over this instance's lifetime --
   * the backstop for a data-driven loop with an empty or non-emitting body,
   * which never trips {@code engine.CappedRenderer}'s char budget no matter
   * how long it spins.
   */
  public static final long DEFAULT_MAX_ACCESSES = 2_000_000L; // see checkBudget()

  private final Receipt receipt;
  private final ReceiptRenderSettings settings;
  private final long maxAccesses;
  private final AtomicLong accessCount = new AtomicLong();

  public ReceiptViewModel(Receipt receipt) {
    this(receipt, new ReceiptRenderSettings());
  }

  public ReceiptViewModel(Receipt receipt, ReceiptRenderSettings settings) {
    this(receipt, settings, DEFAULT_MAX_ACCESSES);
  }

  public ReceiptViewModel(Receipt receipt, ReceiptRenderSettings settings, long maxAccesses) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.maxAccesses = maxAccesses;
    this.receipt = decorate(receipt);
  }

  public Receipt receipt() {
    return receipt;
  }

  public ReceiptRenderSettings settings() {
    return settings;
  }


  

  /**
   * Checks the current thread's interrupt status, plus the per-instance access-count budget:
   * every call counts as one cell touched, and the {@code maxAccesses}-th
   * one throws.
   *
   * <p>Wired into {@link #decorate(ChainReceipt)} two ways, with different
   * reach: the outer row-mapper call below only ever fires once per row,
   * during this constructor's own call into {@code ChainReceipt}'s
   * row-ordering/hash-vs-state validation ({@code SourceReceipt}'s
   * constructor eagerly snapshots the rows list via {@code List.copyOf},
   * which forces every row through this lambda immediately -- there's no
   * later, lazier access to re-check). The {@link SourceRowView.AccessListener}
   * passed into {@link SourceRowView#map(SourceRow, SourceRowView.AccessListener)}
   * is the one that actually matters for rendering: {@code cells()} is
   * never cached on that returned view, so every subsequent {@code
   * cells()} call -- including the ones Qute itself makes while walking a
   * template's {@code {#for}} loop -- builds a fresh lazy list and
   * re-checks on every element access, for the life of the row.</p>
   */
  private void checkBudget() {
    // support interrupt from managed thread pools
    if (Thread.currentThread().isInterrupted())
      throw new RenderInterruptedException();
    if (accessCount.incrementAndGet() > maxAccesses)
      throw new RenderAccessBudgetExceededException(maxAccesses);
  }

  private ChainReceipt decorate(ChainReceipt cr) {
    List<SourceRow> srcRows = cr.rows();
    List<SourceRow> view = Lists.map(srcRows, row -> {
      checkBudget();
      return SourceRowView.map(row, this::checkBudget);
    });
    return new ChainReceipt(cr.chainUri(), view, cr.state());
  }

  private Receipt decorate(Receipt receipt) {
    List<ChainReceipt> crs =
        receipt.chains().stream().map(this::decorate).toList();
    return new Receipt(crs);
  }

}
