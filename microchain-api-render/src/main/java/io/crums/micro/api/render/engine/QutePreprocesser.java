/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render.engine;

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;

import io.crums.micro.api.render.ReceiptRenderSettings;
import io.crums.sldg.src.Cell;
import io.crums.sldg.src.DataType;

import io.quarkus.qute.Engine;
import io.quarkus.qute.EngineBuilder;
import io.quarkus.qute.FragmentSectionHelper;
import io.quarkus.qute.IfSectionHelper;
import io.quarkus.qute.IncludeSectionHelper;
import io.quarkus.qute.InsertSectionHelper;
import io.quarkus.qute.LoopSectionHelper;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.SetSectionHelper;
import io.quarkus.qute.ValueResolver;
import io.quarkus.qute.WhenSectionHelper;
import io.quarkus.qute.WithSectionHelper;

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

  /**
   * Matches {@code EngineBuilder}'s own default (verified against
   * qute-core 3.17.6 bytecode) -- the prefix used for implicit loop
   * metadata variables ({@code count}, {@code index}, etc). Not reachable
   * via public API once {@code addDefaultSectionHelpers()} is bypassed
   * below, so it's reproduced here instead.
   */
  private static final String ITERATION_METADATA_PREFIX = "<alias_>";

  private QutePreprocesser() {
  }

  /** A fresh {@link Engine}, configured with default {@link ReceiptRenderSettings}. */
  public static Engine newEngine() {
    return newEngine(new ReceiptRenderSettings());
  }

  /** A fresh {@link Engine}, configured per {@link #configure(EngineBuilder, ReceiptRenderSettings)}. */
  public static Engine newEngine(ReceiptRenderSettings settings) {
    return configure(builderWithoutEval(), settings).build();
  }

  /**
   * The same section helpers {@code EngineBuilder.addDefaultSectionHelpers()}
   * registers, plus the default value resolvers -- i.e. everything {@code
   * addDefaults()} would give us -- except {@link io.quarkus.qute.EvalSectionHelper}
   * ({@code {#eval}}) is deliberately left out.
   *
   * <p>{@code {#eval}} parses a runtime-resolved <em>string value</em> as
   * further template source and renders it in place -- the one built-in
   * construct that's genuinely self-referential (a template can evaluate
   * itself, recursively, with no bound tied to submitted data). Dropping
   * it also closes a second, independent problem: without it, no cell
   * value drawn from untrusted ledger data can ever be promoted to live
   * template code, even by accident. Nothing in this codebase's template
   * DSL needs it -- {@code {#include}} already covers referencing another
   * named template by id.</p>
   */
  private static EngineBuilder builderWithoutEval() {
    return Engine.builder()
        // A template referencing a nonexistent property/method is a
        // publisher's typo, not legitimately-absent data: a missing
        // *value* (e.g. a NULL-typed cell) still resolves successfully, to
        // ReceiptRenderSettings#nullSymbol(), via cell.display -- it never
        // reaches Qute's own "property not found" path at all. So there's
        // no legitimate case this needs to tolerate; fail fast instead of
        // silently rendering blank.
        .strictRendering(true)
        .addSectionHelpers(
            new IfSectionHelper.Factory(),
            new LoopSectionHelper.Factory(ITERATION_METADATA_PREFIX),
            new WithSectionHelper.Factory(),
            new IncludeSectionHelper.Factory(),
            new InsertSectionHelper.Factory(),
            new SetSectionHelper.Factory(),
            new WhenSectionHelper.Factory(),
            new FragmentSectionHelper.Factory())
        .addDefaultValueResolvers();
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
      // HASH is always exactly 32 bytes -- the one case HashEncoding (hex or
      // base64_32) is actually meant for.
      case HASH        -> settings.hashEncoding().encode((ByteBuffer) raw);
      // BYTES is an arbitrary-length blob (e.g. an embedded image) -- NOT a
      // hash, so HashEncoding doesn't apply: Base64_32.encode throws
      // IllegalArgumentException on anything but exactly 32 bytes. Standard
      // Base64 has no such restriction, and doubles as exactly what a
      // template needs to build a `data:...;base64,` URI from the cell.
      case BYTES       -> Base64.getEncoder().encodeToString(toByteArray((ByteBuffer) raw));
      default          -> raw; // STRING, LONG, BIG_INT, BIG_DEC, BOOL
    };
  }

  private static byte[] toByteArray(ByteBuffer buf) {
    ByteBuffer dup = buf.duplicate();
    byte[] bytes = new byte[dup.remaining()];
    dup.get(bytes);
    return bytes;
  }

  private static String formatDate(long epochMillis, ReceiptRenderSettings settings) {
    // format cloned since we cannot assume it's thread-safe
    DateFormat format = (DateFormat) settings.dateFormat().clone();
    return format.format(new Date(epochMillis));
  }

}
