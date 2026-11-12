/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;


import java.util.List;
import java.util.Objects;

import io.crums.micro.api.receipt.ChainReceipt;
import io.crums.micro.api.receipt.Receipt;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.SourceRowView;
import io.crums.util.collections.Lists;

/**
 * A {@link Receipt} to be rendered, paired with the {@link
 * ReceiptRenderSettings} controlling how its cells are displayed -- and
 * the root data object actually handed to Qute (via {@code
 * Template#data}), not {@link #receipt()} on its own.
 * <p>
 * Excepting its {@linkplain ReceiptRenderSettings settings} member,
 * which is configurable directly from the Qute script (!),
 * all objects returned by this API (and their transitive subcomponents)
 * are read-only (immutable).
 * </p>
 */
public final class ReceiptViewModel {

  /**
   * Generous default cap on cells touched over this instance's lifetime --
   * the backstop for a data-driven loop with an empty or non-emitting body,
   * which never trips {@code engine.CappedRenderer}'s char budget no matter
   * how long it spins.
   */
  public static final long DEFAULT_MAX_ACCESSES = 2_000_000L; // see checkBudget()

  // Plain field, not Atomic/volatile: checkBudget() is only ever called from
  // the one thread doing a given render (see Calculator's javadoc for why
  // Qute never hops threads mid-render for a synchronous, reflective
  // resolver like this one) -- confirmed against qute-core 3.40.1 sources.
  private long accessCount;
  private final ReceiptRenderSettings settings;
  private final long maxAccesses;
  private final Receipt receipt;
  private final Calculator calc = new Calculator();

  // Package-private: nobody outside io.crums.micro.api.render needs to
  // construct one of these directly -- RenderingBuilder is the only real
  // caller, Qute scripts only ever see an already-built instance (and
  // couldn't call a constructor through reflection-based resolution
  // anyway). The class itself stays public only because QutePreprocesser
  // (a different package) names it in a public method signature
  // (newEngine(ReceiptViewModel)).

  ReceiptViewModel(Receipt receipt) {
    this(receipt, new ReceiptRenderSettings());
  }

  ReceiptViewModel(Receipt receipt, ReceiptRenderSettings settings) {
    this(receipt, settings, DEFAULT_MAX_ACCESSES);
  }

  ReceiptViewModel(Receipt receipt, ReceiptRenderSettings settings, long maxAccesses) {
    this(receipt, settings, maxAccesses, true);
  }

  /**
   * @param guarded if {@code false}, {@link #decorate(ChainReceipt, boolean)} skips
   *        every {@link #checkBudget()} call (and the listener-carrying
   *        {@link SourceRowView} wrapping that drives it) entirely --
   *        {@code maxAccesses} is then irrelevant, and nothing here stops
   *        a runaway render on its own. Only meant for content already
   *        known to be safe (an already-published, already-test-rendered
   *        template) -- see {@code QutePreprocesser.unguardedEngine}.
   */
  ReceiptViewModel(Receipt receipt, ReceiptRenderSettings settings, long maxAccesses, boolean guarded) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.maxAccesses = maxAccesses;
    this.receipt = decorate(receipt, guarded);
  }


  /**
   * Returns the receipt.
   */
  public Receipt receipt() {
    return receipt;
  }

  /**
   * Returns the receipt's chains. This is a <em>convenience</em> method
   * for invoking {@code receipt().chains()}
   * 
   * @return {@code receipt().chains()}
   */
  public List<ChainReceipt> chains() {
    return receipt.chains();
  }


  /**
   * Returns the render settings. The returned "settings" object is configurable
   * from the Qute script.
   */
  public ReceiptRenderSettings settings() {
    return settings;
  }

  /**
   * A scratch {@link Calculator}, for a template that needs arithmetic
   * Qute itself has no operators for -- one fresh instance per view
   * model, same sharing lifetime as {@link #settings()}.
   */
  public Calculator calc() {
    return calc;
  }




  /**
   * Checks the current thread's interrupt status, plus the per-instance access-count budget:
   * every call counts as one access, and the {@code maxAccesses}-th
   * one throws.
   *
   * <p>Wired into {@link #decorate(ChainReceipt, boolean)} two ways, with different
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
   *
   * <p>Also called once per rendered template node, from the trace
   * listener {@code QutePreprocesser.newEngine(ReceiptViewModel)} wires
   * into the Qute {@code Engine} it builds -- that hook reaches every
   * loop iteration, including one over a literal range whose body never
   * touches a cell, which the {@code SourceRowView} hook above cannot see.
   * Public (not just package-visible) because that caller lives in a
   * different package ({@code .engine}); harmless to call from anywhere
   * else too, since it has no effect beyond this instance's own budget.</p>
   */
  public void checkBudget() {
    // support interrupt from managed thread pools
    if (Thread.currentThread().isInterrupted())
      throw new RenderInterruptedException();
    if (++accessCount > maxAccesses)
      throw new RenderAccessBudgetExceededException(maxAccesses);
  }

  private ChainReceipt decorate(ChainReceipt cr, boolean guarded) {
    List<SourceRow> srcRows = cr.rows();
    List<SourceRow> view = Lists.map(srcRows, row -> {
      if (guarded) {
        checkBudget();
        return SourceRowView.map(row, this::checkBudget);
      }
      // Still wrapped (Qute's reflection resolver needs a concrete class,
      // not the abstract SourceRow/Cell), just with no listener attached.
      return SourceRowView.map(row);
    });
    return new ChainReceipt(cr.chainUri(), view, cr.state());
  }

  private Receipt decorate(Receipt receipt, boolean guarded) {
    List<ChainReceipt> crs =
        receipt.chains().stream().map(cr -> decorate(cr, guarded)).toList();
    return new Receipt(crs);
  }

}
