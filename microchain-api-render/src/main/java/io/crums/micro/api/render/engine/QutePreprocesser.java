/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render.engine;

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.util.Date;
import java.util.Objects;

import io.crums.micro.api.render.ReceiptRenderSettings;
import io.crums.sldg.src.Cell;
import io.crums.sldg.src.DataType;

import io.quarkus.qute.Engine;
import io.quarkus.qute.EngineBuilder;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.ValueResolver;

/**
 * Registers everything a Qute {@link Engine} needs to render a {@code
 * Receipt} directly: {@link ReflectionValueResolver} (property/method
 * access on the real domain objects -- not included in {@code
 * Engine.builder().addDefaults()} by default), plus one virtual property,
 * {@code cell.display}, for the one thing {@code Cell} itself has no safe
 * accessor for -- a redacted cell throws from {@code Cell#value()}, and a
 * {@code DATE}/{@code BYTES}/{@code HASH} cell's raw value (epoch millis /
 * a bare {@link ByteBuffer}) isn't printable as-is.
 *
 * <p>This class -- and the {@code .engine} package it lives in -- is not
 * exported by this module: {@link Engine}/{@link EngineBuilder} are
 * backend plumbing, never part of this module's public API. The only
 * public, Qute-agnostic argument type is {@code
 * io.crums.micro.api.render.ReceiptViewModel}.</p>
 */
public final class QutePreprocesser {

  private QutePreprocesser() {
  }

  /** A fresh {@link Engine}, configured with default {@link ReceiptRenderSettings}. */
  public static Engine newEngine() {
    return newEngine(new ReceiptRenderSettings());
  }

  /** A fresh {@link Engine}, configured per {@link #configure(EngineBuilder, ReceiptRenderSettings)}. */
  public static Engine newEngine(ReceiptRenderSettings settings) {
    return configure(Engine.builder().addDefaults(), settings).build();
  }

  public static EngineBuilder configure(EngineBuilder builder, ReceiptRenderSettings settings) {
    Objects.requireNonNull(settings, "settings");
    ValueResolver cellDisplayResolver =
        ValueResolver.builder()
            .applyToBaseClass(Cell.class)
            .applyToName("display")
            .applyToNoParameters()
            .resolveSync(ctx -> displayValue((Cell) ctx.getBase(), settings))
            .build();
    return builder
        .addValueResolver(new ReflectionValueResolver())
        .addValueResolver(cellDisplayResolver);
  }

  /** A cell's safe, printable value: a sentinel, or a DATE/BYTES/HASH-normalized value. */
  private static Object displayValue(Cell cell, ReceiptRenderSettings settings) {
    if (cell.isRedacted())
      return settings.redactSymbol();

    DataType type = cell.dataType();
    Object raw = type.toValue(cell.data());

    return switch (type) {
      case NULL        -> settings.nullSymbol();
      // toValue() returns raw epoch millis for DATE, not an Instant/Date.
      case DATE        -> formatDate((Long) raw, settings);
      case BYTES, HASH -> settings.hashEncoding().encode((ByteBuffer) raw);
      default          -> raw; // STRING, LONG, BIG_INT, BIG_DEC, BOOL
    };
  }

  private static String formatDate(long epochMillis, ReceiptRenderSettings settings) {
    // DateFormat.format() is not safe for concurrent use on one instance,
    // and the settings bean is shared across every in-flight render --
    // clone (DateFormat supports Cloneable for exactly this) before use.
    DateFormat format = (DateFormat) settings.dateFormat().clone();
    return format.format(new Date(epochMillis));
  }

}
