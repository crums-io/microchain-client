/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Objects;

import io.crums.sldg.json.HashEncoding;

/**
 * Mutable, shared settings controlling how a {@code Cell}'s safe, printable
 * value ({@code cell.display}) is rendered: the redacted/null sentinels and
 * the DATE/BYTES/HASH normalization format.
 *
 * <p>Closed over by the {@code cell.display} resolver at engine-configure
 * time, so a setting change takes effect on the next render without
 * rebuilding the engine. Fields are plain, not {@code volatile} -- no
 * synchronization is needed, since every setter/getter here is a
 * synchronous, reflective method, and Qute resolves those entirely on the
 * one thread doing a given render, never hopping threads mid-resolution
 * (confirmed against qute-core 3.40.1: its internal already-completed
 * {@code CompletionStage} throws {@code UnsupportedOperationException}
 * from every {@code *Async} method, meaning Qute's own machinery never
 * calls them for a resolver like this one). This class still isn't a safe
 * choice to share across two renders running <em>concurrently</em> on
 * different threads -- nothing in this codebase does that today.</p>
 *
 * <p>Every setter returns {@code String} (always {@code ""}), not {@code
 * void} and not {@code this}. {@code void} is not an option: a Qute
 * template invokes these setters directly (settings are reachable from a
 * template as {@code settings.*}), and {@code
 * io.quarkus.qute.ReflectionValueResolver} excludes void-returning methods
 * from candidacy entirely -- a {@code void} setter would simply not be
 * callable from a template at all. {@code this} is not a good choice
 * either: a setter called as a bare template statement, for its side
 * effect, would otherwise print its return value's {@code toString()}
 * straight into the rendered output. The return value carries no meaning
 * of its own (a setter either succeeds or throws) -- it exists purely so
 * the method stays callable and so calling it, e.g. {@code
 * settings.setHashEncodingToHex()}, as an ordinary bare template statement
 * renders nothing, with no extra syntax (no {@code {#if}}/{@code {#let}}
 * wrapper) needed to suppress it.</p>
 */
public final class ReceiptRenderSettings {

  public static final String DEFAULT_REDACT_SYMBOL = "[redacted]";
  public static final String DEFAULT_NULL_SYMBOL = "";
  public static final HashEncoding DEFAULT_HASH_ENCODING = HashEncoding.HEX;
  public static final int DATE_FORMAT_CAP = 1000;

  private String redactSymbol = DEFAULT_REDACT_SYMBOL;

  private String nullSymbol = DEFAULT_NULL_SYMBOL;

  private HashEncoding hashEncoding = DEFAULT_HASH_ENCODING;

  private DateFormat dateFormat;


  public String redactSymbol() {
    return redactSymbol;
  }

  public String setRedactSymbol(String redactSymbol) {
    this.redactSymbol = Objects.requireNonNull(redactSymbol);
    return "";
  }

  public String nullSymbol() {
    return nullSymbol;
  }

  public String setNullSymbol(String nullSymbol) {
    this.nullSymbol = Objects.requireNonNull(nullSymbol);
    return "";
  }

  public HashEncoding hashEncoding() {
    return hashEncoding;
  }


  public String setHashEncodingToHex() {
    this.hashEncoding = HashEncoding.HEX;
    return "";
  }

  public String setHashEncodingToB64() {
    this.hashEncoding = HashEncoding.BASE64_32;
    return "";
  }

  /**
   * Never returns {@code null} (defaults to {@link DateFormat#getDateInstance()}).
   * Since {@link DateFormat}s are not guaranteed to be thread-safe, each invocation
   * of this method returns a new (possibly cloned) instance.
   */
  public DateFormat dateFormat() {
    DateFormat format = dateFormat;
    return format == null ? DateFormat.getDateInstance() : (DateFormat) format.clone();
  }

  /**
   * Sets the date format using the "simple-date-format" convention.
   *
   * @param format simple-date-format.
   *
   * @see SimpleDateFormat
   * @throws IllegalArgumentException if {@code format} is not a valid string
   */
  public String setDateFormat(String format) {
    Objects.requireNonNull(format);
    if (format.length() > DATE_FORMAT_CAP)
      throw new IllegalArgumentException(
          "date format string length (%d) exceeds cap (%d)"
          .formatted(format.length(), DATE_FORMAT_CAP));

    this.dateFormat = new SimpleDateFormat(format);
    return "";
  }

}
