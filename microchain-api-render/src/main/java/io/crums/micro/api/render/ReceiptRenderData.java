/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.crums.micro.api.receipt.Receipt;
import io.crums.util.collections.CollectionUtils;

/**
 * Everything [re]discoverable about a rendering: the {@link Receipt}
 * itself, whatever reference fields ({@link RenderingBuilder}'s {@code
 * templateXxx(...)}/{@code putMeta} entries) were set at render time, and
 * -- if known -- the exact Qute template source it was rendered against.
 *
 * <p>{@code templateContent} is the one optional member: see {@link
 * #isKernel()}. An instance extracted back out of a rendered artifact
 * (HTML/PDF) typically won't have it -- the artifact never embeds the
 * literal template source, only reference-field pointers to it (e.g. a
 * {@code crums:template-src} entry in {@code metaInfo}) -- and resolving
 * such a reference into literal content is someone else's job, not this
 * class's. An instance built to actually drive a render (see {@link
 * RenderingBuilder}) always has it.</p>
 *
 * <p>Instances (and their internals) are immutable.</p>
 */
public final class ReceiptRenderData {

  private final Receipt receipt;
  private final String templateContent;
  private final Map<String, String> metaInfo;

  /**
   * Constructs an instance with no meta info.
   *
   * @param receipt           not null
   * @param templateContent   nullable -- see {@link #isKernel()}
   */
  public ReceiptRenderData(Receipt receipt, String templateContent) {
    this(receipt, templateContent, Map.of());
  }

  /**
   * Constructs an instance with meta info.
   *
   * @param receipt           not null
   * @param templateContent   nullable -- see {@link #isKernel()}
   * @param metaInfo          (copied)
   */
  public ReceiptRenderData(Receipt receipt, String templateContent, Map<String, String> metaInfo) {
    this.receipt = Objects.requireNonNull(receipt, "receipt");
    this.templateContent = templateContent;
    this.metaInfo = switch (metaInfo.size()) {
      case 0  -> Map.of();
      case 1  -> Map.copyOf(metaInfo);
      default -> new LinkedHashMap<>(metaInfo);
    };
  }

  /**
   * Constructs an instance with meta info given as a collection of key/value pairs.
   *
   * @param receipt           not null
   * @param templateContent   nullable -- see {@link #isKernel()}
   * @param metaEntries       no duplicate keys
   */
  public ReceiptRenderData(
      Receipt receipt, String templateContent,
      Collection<Map.Entry<String,String>> metaEntries) {

    this.receipt = Objects.requireNonNull(receipt, "receipt");
    this.templateContent = templateContent;

    switch (metaEntries.size()) {
    case 0:
      this.metaInfo = Map.of(); break;
    case 1:
    {
      var e = metaEntries.iterator().next();
      this.metaInfo = Map.of(e.getKey(), e.getValue());
      break;
    }
    default:
      
      Map<String, String> built = new LinkedHashMap<>();
      for (Map.Entry<String, String> e : metaEntries)
        if (built.put(e.getKey(), e.getValue()) != null)
          throw new IllegalArgumentException(
              "metaEntries contains duplicate key (%s): %s"
              .formatted(e.getKey(), metaEntries));
      this.metaInfo = built;
    }
  }



  public Receipt receipt() {
    return receipt;
  }

  /** Nullable -- see {@link #isKernel()}. */
  public String templateContent() {
    return templateContent;
  }

  public Map<String, String> metaInfo() {
    return Collections.unmodifiableMap(metaInfo);
  }

  /**
   * Returns {@code true} if {@link #templateContent()} is set. I.e. this
   * instance carries everything needed to actually drive a render (see
   * {@link RenderingBuilder}), not just describe one after the fact.
   */
  public boolean isKernel() {
    return templateContent != null;
  }

  public ReceiptRenderData withReceipt(Receipt receipt) {
    return new ReceiptRenderData(receipt, templateContent, metaInfo);
  }

  public ReceiptRenderData withTemplateContent(String templateContent) {
    return Objects.equals(this.templateContent, templateContent) ?
        this : new ReceiptRenderData(receipt, templateContent, metaInfo);
  }

  public ReceiptRenderData sansTemplateContent() {
    return withTemplateContent(null);
  }

  public ReceiptRenderData withAddedMeta(Map<String,String> meta) {
    return new ReceiptRenderData(
        receipt, templateContent,
        CollectionUtils.concat(metaInfo.entrySet(), meta.entrySet()));
  }


  public ReceiptRenderData withMeta(String key, String value) {
    return withAddedMeta(Map.of(key, value));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (!(o instanceof ReceiptRenderData that))
      return false;
    return receipt.equals(that.receipt)
        && Objects.equals(templateContent, that.templateContent)
        && metaInfo.equals(that.metaInfo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(receipt, templateContent, metaInfo);
  }

  @Override
  public String toString() {
    return "ReceiptRenderData[isKernel=" + isKernel() +
        ", templateContent.length=" + (templateContent == null ? -1 : templateContent.length()) +
        ", metaInfo=" + metaInfo + "]";
  }
}
