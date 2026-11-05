/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.crums.micro.api.receipt.ChainReceipt;
import io.crums.micro.api.receipt.Receipt;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.SourceRowBuilder;

/**
 * Round-trips {@link ReceiptHtmlCodec#embed(String, ReceiptRenderData)}
 * back through its own {@code extract} -- the only embedding entry point
 * this codec exposes, so every test here goes through it.
 */
public class ReceiptHtmlCodecTest {

  private static final ReceiptHtmlCodec CODEC = ReceiptHtmlCodec.CODEC;

  private static final String CHAIN_URL = "https://micro.crums.io/bikeshop/orders";

  private Receipt newReceipt() {
    SourceRowBuilder builder = new SourceRowBuilder();
    SourceRow row = builder.buildRow(1L, "Acme Corp", 42L);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    return new Receipt(List.of(cr));
  }

  @Test
  public void testNoReceiptEmbedded_returnsEmpty() {
    assertTrue(CODEC.extract("<html><body>plain</body></html>").isEmpty());
  }

  @Test
  public void testReceiptOnly_roundtrips() {
    Receipt receipt = newReceipt();
    ReceiptRenderData data = new ReceiptRenderData(receipt, null, Map.of());
    String html = CODEC.embed("<html><body></body></html>", data);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(receipt, extracted.receipt());
    assertTrue(extracted.metaInfo().isEmpty());
    assertFalse(extracted.isKernel());
    assertNull(extracted.templateContent());
  }

  @Test
  public void testReceiptAndMeta_roundtrips() {
    Receipt receipt = newReceipt();
    Map<String, String> refs = Map.of(
        RenderConstants.TEMPLATE_NAME_REF, "order-receipt",
        RenderConstants.TEMPLATE_SRC_REF, "https://micro.crums.io/bikeshop/_receipt_templates/order-receipt");
    ReceiptRenderData data = new ReceiptRenderData(receipt, null, refs);

    String html = CODEC.embed("<html><head></head><body></body></html>", data);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(receipt, extracted.receipt());
    assertEquals(refs, extracted.metaInfo());
    assertFalse(extracted.isKernel());
  }

  @Test
  public void testMetaValue_withSpecialChars_roundtrips() {
    Receipt receipt = newReceipt();
    Map<String, String> refs = Map.of("owner-name", "Ben & Jerry's \"Scoop\" <shop>");
    ReceiptRenderData data = new ReceiptRenderData(receipt, null, refs);

    String html = CODEC.embed("<html><body></body></html>", data);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(refs, extracted.metaInfo());
  }

  @Test
  public void testTemplatesOwnUnrelatedMetaTag_notPickedUp() {
    // The exact gap the data-crums-kind="ref" marker closes: a template's
    // own <head> may legitimately render its own <meta> tags (e.g.
    // viewport) -- those must never be mistaken for our reference fields.
    Receipt receipt = newReceipt();
    Map<String, String> refs = Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt");
    ReceiptRenderData data = new ReceiptRenderData(receipt, null, refs);
    String html =
        "<html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>";

    html = CODEC.embed(html, data);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(refs, extracted.metaInfo());
    assertFalse(extracted.metaInfo().containsKey("viewport"));
  }

  @Test
  public void testEmbedViaInterface_bytesRoundtrip() {
    Receipt receipt = newReceipt();
    Map<String, String> refs = Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt");
    ReceiptRenderData data = new ReceiptRenderData(receipt, null, refs);

    ReceiptCodec codec = CODEC;
    byte[] embedded = codec.embed(
        "<html><head></head><body>content</body></html>".getBytes(StandardCharsets.UTF_8), data);
    ReceiptRenderData extracted = codec.extract(embedded).orElseThrow();
    assertEquals(receipt, extracted.receipt());
    assertEquals(refs, extracted.metaInfo());
  }

  @Test
  public void testTemplateContent_absent_stillNullAfterRoundtrip() {
    Receipt receipt = newReceipt();
    ReceiptRenderData data = new ReceiptRenderData(receipt, null, Map.of());
    String html = CODEC.embed("<html><body></body></html>", data);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertNull(extracted.templateContent());
    assertFalse(extracted.isKernel());
  }

  @Test
  public void testTemplateContent_present_roundtrips() {
    Receipt receipt = newReceipt();
    String templateContent = "{receipt.chains.get(0).chainUri}";
    ReceiptRenderData data = new ReceiptRenderData(receipt, templateContent, Map.of());
    String html = CODEC.embed("<html><body></body></html>", data);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(templateContent, extracted.templateContent());
    assertTrue(extracted.isKernel());
  }

  @Test
  public void testTemplateContent_withScriptClosingTag_roundtrips() {
    // same raw-text tokenizer hazard the receipt payload's own
    // percent-encoding treatment closes -- confirms the template script
    // gets the same treatment, not a naive raw insertion.
    Receipt receipt = newReceipt();
    String templateContent = "before </script> after";
    ReceiptRenderData data = new ReceiptRenderData(receipt, templateContent, Map.of());
    String html = CODEC.embed("<html><body></body></html>", data);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(templateContent, extracted.templateContent());
  }

  @Test
  public void testTemplateContentAndMeta_headAndBodyPresent_roundtripsAndOrdered() {
    Receipt receipt = newReceipt();
    String templateContent = "{receipt.chains.get(0).chainUri}";
    Map<String, String> refs = Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt");
    ReceiptRenderData data = new ReceiptRenderData(receipt, templateContent, refs);
    String html = "<html><head><title>x</title></head><body>content</body></html>";

    html = CODEC.embed(html, data);

    // meta tag lands in <head>, well before either script in <body>
    int metaIdx = html.indexOf(RenderConstants.TEMPLATE_NAME_REF);
    int headCloseIdx = html.indexOf("</head>");
    int receiptScriptIdx = html.indexOf(RenderConstants.RECEIPT_SCRIPT_ID);
    int templateScriptIdx = html.indexOf(RenderConstants.TEMPLATE_SCRIPT_ID);
    assertTrue(metaIdx < headCloseIdx);
    assertTrue(headCloseIdx < receiptScriptIdx);
    assertTrue(receiptScriptIdx < templateScriptIdx);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(receipt, extracted.receipt());
    assertEquals(refs, extracted.metaInfo());
    assertEquals(templateContent, extracted.templateContent());
  }

  @Test
  public void testTemplateContentAndMeta_noHead_orderedReceiptThenMetaThenTemplate() {
    // The exact case that caught a real ordering bug during development:
    // with no real <head>, the <meta> tags must land BETWEEN the receipt
    // and template scripts (matching what sequential embed-receipt,
    // embed-meta, embed-template steps would produce), not before or
    // after both.
    Receipt receipt = newReceipt();
    String templateContent = "{receipt.chains.get(0).chainUri}";
    Map<String, String> refs = Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt");
    ReceiptRenderData data = new ReceiptRenderData(receipt, templateContent, refs);
    String html = "<html><body>content</body></html>";

    html = CODEC.embed(html, data);

    int receiptScriptIdx = html.indexOf(RenderConstants.RECEIPT_SCRIPT_ID);
    int metaIdx = html.indexOf(RenderConstants.TEMPLATE_NAME_REF);
    int templateScriptIdx = html.indexOf(RenderConstants.TEMPLATE_SCRIPT_ID);
    assertTrue(receiptScriptIdx < metaIdx);
    assertTrue(metaIdx < templateScriptIdx);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(receipt, extracted.receipt());
    assertEquals(refs, extracted.metaInfo());
    assertEquals(templateContent, extracted.templateContent());
  }

  @Test
  public void testTemplateContent_noRefs_noHeadOrBody_roundtrips() {
    Receipt receipt = newReceipt();
    String templateContent = "{receipt.chains.get(0).chainUri}";
    ReceiptRenderData data = new ReceiptRenderData(receipt, templateContent, Map.of());
    String html = "just some free-form fragment, no <head> or <body> at all";

    html = CODEC.embed(html, data);

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals(receipt, extracted.receipt());
    assertEquals(templateContent, extracted.templateContent());
  }

  @Test
  public void testReembed_replacesPriorEmbedding_noDuplicates() {
    // embed() must be idempotent: re-embedding replaces the prior
    // receipt/template script and <meta> tags rather than appending a
    // second, stale copy of each alongside them.
    Receipt receipt = newReceipt();
    ReceiptRenderData v1 = new ReceiptRenderData(
        receipt, "template v1", Map.of(RenderConstants.TEMPLATE_NAME_REF, "v1-name"));
    String html = CODEC.embed("<html><head></head><body></body></html>", v1);

    ReceiptRenderData v2 = new ReceiptRenderData(
        receipt, "template v2", Map.of(RenderConstants.TEMPLATE_NAME_REF, "v2-name"));
    html = CODEC.embed(html, v2);

    assertEquals(1, countOccurrences(html, RenderConstants.RECEIPT_SCRIPT_ID));
    assertEquals(1, countOccurrences(html, RenderConstants.TEMPLATE_SCRIPT_ID));
    assertEquals(1, countOccurrences(html, RenderConstants.TEMPLATE_NAME_REF));

    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertEquals("template v2", extracted.templateContent());
    assertEquals(Map.of(RenderConstants.TEMPLATE_NAME_REF, "v2-name"), extracted.metaInfo());
  }

  @Test
  public void testReembedWithoutTemplateContent_removesPriorTemplateScript() {
    Receipt receipt = newReceipt();
    ReceiptRenderData withTemplate = new ReceiptRenderData(receipt, "template v1", Map.of());
    String html = CODEC.embed("<html><body></body></html>", withTemplate);
    assertTrue(html.contains(RenderConstants.TEMPLATE_SCRIPT_ID));

    ReceiptRenderData withoutTemplate = new ReceiptRenderData(receipt, null, Map.of());
    html = CODEC.embed(html, withoutTemplate);

    assertFalse(html.contains(RenderConstants.TEMPLATE_SCRIPT_ID));
    ReceiptRenderData extracted = CODEC.extract(html).orElseThrow();
    assertNull(extracted.templateContent());
  }

  private int countOccurrences(String haystack, String needle) {
    int count = 0, idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) != -1) {
      count++;
      idx += needle.length();
    }
    return count;
  }

  @Test
  public void testFullRenderingBuilderOutput_roundtrips() {
    SourceRowBuilder builder = new SourceRowBuilder();
    SourceRow row = builder.buildRow(1L, "Acme Corp", 42L);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    Receipt receipt = new Receipt(List.of(cr));
    ReceiptViewModel vm = new ReceiptViewModel(receipt);

    RenderingBuilder rb = new RenderingBuilder(vm, "{receipt.chains.get(0).chainUri}")
        .templateName("order-receipt")
        .templateVersion(3);
    byte[] htmlBytes = rb.buildHtml();
    String html = new String(htmlBytes, StandardCharsets.UTF_8);

    ReceiptRenderData data = CODEC.extract(html).orElseThrow();
    assertEquals(receipt, data.receipt());
    assertEquals(rb.metaInfo(), data.metaInfo());
    assertFalse(data.isKernel());
  }

  @Test
  public void testFullRenderingBuilderOutput_embedTemplateContentOptedIn_roundtrips() {
    SourceRowBuilder builder = new SourceRowBuilder();
    SourceRow row = builder.buildRow(1L, "Acme Corp", 42L);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    Receipt receipt = new Receipt(List.of(cr));
    ReceiptViewModel vm = new ReceiptViewModel(receipt);
    String templateContent = "{receipt.chains.get(0).chainUri}";

    RenderingBuilder rb = new RenderingBuilder(vm, templateContent)
        .templateName("order-receipt")
        .embedTemplateContent(true);
    byte[] htmlBytes = rb.buildHtml();
    String html = new String(htmlBytes, StandardCharsets.UTF_8);

    ReceiptRenderData data = CODEC.extract(html).orElseThrow();
    assertEquals(receipt, data.receipt());
    assertEquals(templateContent, data.templateContent());
    assertTrue(data.isKernel());
  }
}
