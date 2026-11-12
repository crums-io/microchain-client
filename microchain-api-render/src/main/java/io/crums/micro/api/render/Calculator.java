/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Arrays;

/**
 * A simple, stateful 4-function calculator with memory, reachable from a
 * Qute script as {@code calc} -- works around Qute's complete lack of
 * arithmetic operators (confirmed against qute-core 3.40.1 sources: the
 * only built-in infix operators are {@code eq}/{@code ne}/{@code gt}/
 * {@code lt}/{@code and}/{@code or}, usable only inside {@code {#if}}/
 * {@code {#when}}, never in a bare {@code {expr}} output position, and
 * nothing arithmetic at all). Mimics a real pocket calculator's memory
 * buttons (M+, M-, MC, MR) closely on purpose, so a template author
 * already familiar with one needs no new mental model.
 *
 * <p>Every mutator returns {@code String} (always {@code ""}), same
 * reasoning and the same convention as {@link ReceiptRenderSettings}'s
 * setters: {@code void} isn't callable from a template at all ({@code
 * io.quarkus.qute.ReflectionValueResolver} excludes void-returning
 * methods from candidacy entirely), and {@code this} would print this
 * object's {@code toString()} when called as a bare statement. {@code
 * ""} renders as nothing, with no extra syntax needed to suppress it.</p>
 *
 * <p>All arithmetic is {@link BigDecimal}, with incoming values coerced
 * uniformly regardless of their actual type -- the one thing a template
 * cannot safely do itself: a cell's {@code display} value for a numeric
 * column is sometimes a {@code Long}, sometimes a {@code BigDecimal},
 * depending on whether that particular row's stored value happens to be
 * a whole number (confirmed against real data: the same SQL column can
 * type a cell as LONG for one row and BIG_DEC for another), and {@code
 * Long} has no arithmetic instance methods a template could fall back on
 * by reflection either way.</p>
 *
 * <p>Memory is {@link #MEMORY_SLOTS} independent registers (0-based), not
 * just one -- a real calculator's single M+/M-/MC/MR buttons generalize to
 * {@link #mPlus(int)}/{@link #mMinus(int)}/{@link #mClear(int)}/{@link
 * #mRecall(int)} taking a slot index; the original zero-arg forms ({@link
 * #mPlus()} etc.) still exist and are just {@code index == 0}, so a
 * template written against the single-memory API keeps working unchanged.
 * An out-of-range index throws the array's own bounds exception -- no
 * bespoke check, same spirit as letting {@link #toBigDecimal} throw
 * plainly on a bad value.</p>
 *
 * <p>Fields are plain, not {@code volatile}/{@code Atomic} -- one instance
 * is used by exactly one render, entirely on one thread: Qute resolves a
 * synchronous, reflective method (every method here) without ever hopping
 * threads mid-render (see {@link ReceiptRenderSettings}'s javadoc for the
 * qute-core evidence). Not a safe choice to share across two renders
 * running <em>concurrently</em> regardless -- nothing in this codebase
 * does that today.</p>
 */
public final class Calculator {

  /** Number of independent memory registers -- see the class javadoc. */
  public static final int MEMORY_SLOTS = 10;

  private BigDecimal display = BigDecimal.ZERO;
  private final BigDecimal[] memory = new BigDecimal[MEMORY_SLOTS];

  public Calculator() {
    Arrays.fill(memory, BigDecimal.ZERO);
  }

  /**
   * The current display value, as a plain (non-scientific-notation)
   * decimal string. In a template, call this explicitly ({@code
   * {calc.display()}}) anywhere you need the value <em>at that point</em>
   * -- see {@link #toString()} for why a bare {@code {calc}} does not
   * reliably give you that.
   */
  public String display() {
    return display.toPlainString();
  }

  /**
   * Same as {@link #display()}. <strong>Only safe for a single, final
   * read with nothing left to mutate this object afterward</strong> --
   * Qute fully resolves a template (running every side-effecting call,
   * in the order written) before it ever converts the result tree to
   * text; a bare {@code {calc}} resolves to a live reference to this
   * mutable object, and {@code toString()} isn't actually invoked until
   * that later text-conversion pass, so it reflects {@code calc}'s
   * <em>final</em> state, not its state at that point in the template --
   * confirmed against qute-core 3.40.1 ({@code SingleResultNode#process}
   * calls {@code toString()} only after {@code TemplateImpl} finishes
   * resolving the whole tree). A per-row running value printed mid-loop
   * needs {@link #display()} called explicitly instead, since that
   * method's return value (a plain {@code String}) is captured during
   * resolution, before any later mutation -- see {@code
   * ReceiptViewModelTest#testCalc_reachableFromTemplate_...} for a
   * worked example of exactly this trap.
   */
  @Override
  public String toString() {
    return display();
  }

  /** Like a calculator's "C" button: resets the display to zero. Memory is untouched. */
  public String clear() {
    display = BigDecimal.ZERO;
    return "";
  }

  /** Like a calculator's "CA" button: resets the display and all memory slots to zero. */
  public String clearAll() {
    for (int slot = 0; slot < memory.length; ++slot)
      mClear(slot);
    return clear();
  }

  /** Like typing a number: sets the display to {@code value}, overwriting whatever was there. */
  public String enter(Object value) {
    display = toBigDecimal(value);
    return "";
  }

  /** Adds {@code value} to the display. */
  public String plus(Object value) {
    display = display.add(toBigDecimal(value));
    return "";
  }

  /** Subtracts {@code value} from the display. */
  public String minus(Object value) {
    display = display.subtract(toBigDecimal(value));
    return "";
  }

  /** Multiplies the display by {@code value}. */
  public String times(Object value) {
    display = display.multiply(toBigDecimal(value));
    return "";
  }

  /**
   * Divides the display by {@code value} -- {@link MathContext#DECIMAL64}
   * precision, to tolerate a non-terminating quotient rather than throw.
   */
  public String dividedBy(Object value) {
    display = display.divide(toBigDecimal(value), MathContext.DECIMAL64);
    return "";
  }

  /**
   * Like a calculator's M+ button: adds the current display to memory
   * slot 0. No argument -- same as the real button, which acts on
   * whatever's currently displayed, not on a value you hand it.
   */
  public String mPlus() {
    return mPlus(0);
  }

  /** Like {@link #mPlus()}, against memory slot {@code index} (0..{@link #MEMORY_SLOTS}-1). */
  public String mPlus(int index) {
    memory[index] = memory[index].add(display);
    return "";
  }

  /** Like a calculator's M- button: subtracts the current display from memory slot 0. */
  public String mMinus() {
    return mMinus(0);
  }

  /** Like {@link #mMinus()}, against memory slot {@code index} (0..{@link #MEMORY_SLOTS}-1). */
  public String mMinus(int index) {
    memory[index] = memory[index].subtract(display);
    return "";
  }

  /** Like a calculator's MC button: resets memory slot 0 to zero. The display is untouched. */
  public String mClear() {
    return mClear(0);
  }

  /** Like {@link #mClear()}, against memory slot {@code index} (0..{@link #MEMORY_SLOTS}-1). */
  public String mClear(int index) {
    memory[index] = BigDecimal.ZERO;
    return "";
  }

  /**
   * Like a calculator's MR button: recalls memory slot 0 onto the display.
   * Doesn't itself print anything -- same as the real button, which
   * puts the value on the display for you to then look at, e.g. {@code
   * {calc.display()}} right after (see {@link #toString()} before
   * reaching for a bare {@code {calc}} instead).
   */
  public String mRecall() {
    return mRecall(0);
  }

  /** Like {@link #mRecall()}, against memory slot {@code index} (0..{@link #MEMORY_SLOTS}-1). */
  public String mRecall(int index) {
    display = memory[index];
    return "";
  }

  private BigDecimal toBigDecimal(Object value) {
    if (value instanceof BigDecimal bd)
      return bd;
    if (value instanceof BigInteger bi)
      return new BigDecimal(bi);
    if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
      return BigDecimal.valueOf(((Number) value).longValue());
    if (value instanceof Number n)
      return new BigDecimal(n.toString());
    if (value instanceof String s)
      return new BigDecimal(s);
    throw new IllegalArgumentException("not a number: " + value);
  }
}
