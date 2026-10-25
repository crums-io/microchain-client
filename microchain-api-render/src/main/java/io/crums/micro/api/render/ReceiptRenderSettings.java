/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.text.DateFormat;
import java.util.Objects;

import io.crums.sldg.json.HashEncoding;

/**
 * Mutable, shared settings controlling how a {@code Cell}'s safe, printable
 * value ({@code cell.display}) is rendered: the redacted/null sentinels and
 * the DATE/BYTES/HASH normalization format.
 *
 * <p>One instance is normally shared for the lifetime of a Qute {@code
 * Engine} -- closed over by the {@code cell.display} resolver at
 * engine-configure time -- so changing a setting here takes effect on the
 * next render without rebuilding the engine. Fields are {@code volatile}:
 * this bean is read concurrently by every in-flight render and may be
 * mutated at any time, so plain fields would risk a stale read under the
 * Java Memory Model.</p>
 */
public final class ReceiptRenderSettings {

  public static final Object DEFAULT_REDACT_SYMBOL = "[redacted]";
  public static final Object DEFAULT_NULL_SYMBOL = "";
  public static final HashEncoding DEFAULT_HASH_ENCODING = HashEncoding.HEX;

  // why an object? hopefully it can also be an image. (Discuss)
  private volatile Object redactSymbol = DEFAULT_REDACT_SYMBOL;

  private volatile Object nullSymbol = DEFAULT_NULL_SYMBOL;

  private volatile HashEncoding hashEncoding = DEFAULT_HASH_ENCODING;

  private volatile DateFormat dateFormat;


  public Object redactSymbol() {
    return redactSymbol;
  }

  public ReceiptRenderSettings setRedactSymbol(Object redactSymbol) {
    this.redactSymbol = Objects.requireNonNull(redactSymbol);
    return this;
  }

  public Object nullSymbol() {
    return nullSymbol;
  }

  public ReceiptRenderSettings setNullSymbol(Object nullSymbol) {
    this.nullSymbol = nullSymbol;
    return this;
  }

  public HashEncoding hashEncoding() {
    return hashEncoding;
  }

  public ReceiptRenderSettings setHashEncoding(HashEncoding hashEncoding) {
    this.hashEncoding = Objects.requireNonNull(hashEncoding);
    return this;
  }

  /** Never returns {@code null} (defaults to {@link DateFormat#getDateInstance()}). */
  public DateFormat dateFormat() {
    DateFormat format = dateFormat;
    return format == null ? DateFormat.getDateInstance() : format;
  }

  public ReceiptRenderSettings setDateFormat(DateFormat dateFormat) {
    this.dateFormat = Objects.requireNonNull(dateFormat);
    return this;
  }

}
