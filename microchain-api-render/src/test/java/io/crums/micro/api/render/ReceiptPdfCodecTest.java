/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import io.crums.micro.api.receipt.ChainReceipt;
import io.crums.micro.api.receipt.Receipt;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.SourceRowBuilder;

/**
 * Round-trips {@link ReceiptPdfCodec}'s embed methods back through its
 * own {@code extract}.
 */
public class ReceiptPdfCodecTest {

  private static final ReceiptPdfCodec CODEC = ReceiptPdfCodec.CODEC;

  private static final String CHAIN_URL = "https://micro.crums.io/bikeshop/orders";

  private Receipt newReceipt() {
    SourceRowBuilder builder = new SourceRowBuilder();
    SourceRow row = builder.buildRow(1L, "Acme Corp", 42L);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    return new Receipt(List.of(cr));
  }

  private Receipt newOtherReceipt() {
    SourceRowBuilder builder = new SourceRowBuilder();
    SourceRow row = builder.buildRow(2L, "Other Corp", 99L);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    return new Receipt(List.of(cr));
  }

  private COSArray afArray(PDDocument doc) {
    COSBase af = doc.getDocumentCatalog().getCOSObject()
        .getDictionaryObject(COSName.getPDFName("AF"));
    assertTrue(af instanceof COSArray, "/AF missing or not an array");
    return (COSArray) af;
  }

  @Test
  public void testNoReceiptEmbedded_returnsEmpty() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      assertTrue(CODEC.extract(doc).isEmpty());
    }
  }

  @Test
  public void testReceiptAndCustomInfo_roundtrips() throws IOException {
    Receipt receipt = newReceipt();
    String receiptJson = Receipt.PARSER.toJsonObject(receipt).toJSONString();
    Map<String, String> refs = Map.of(
        RenderConstants.TEMPLATE_NAME_REF, "order-receipt",
        RenderConstants.TEMPLATE_VERSION_REF, "3");

    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      CODEC.embedReceiptAttachment(doc, receiptJson);
      CODEC.embedCustomInfo(doc, refs);

      ReceiptRenderData data = CODEC.extract(doc).orElseThrow();
      assertEquals(receipt, data.receipt());
      assertEquals(refs, data.metaInfo());
      assertFalse(data.isKernel());
      assertNull(data.templateContent());
    }
  }

  @Test
  public void testStandardInfoDictionaryFields_notPickedUpAsMeta() throws IOException {
    // The PDF-side counterpart of the HTML <meta> disambiguation gap: a
    // PDF library commonly stamps its own Producer/CreationDate/etc. on
    // the same Info dictionary custom entries live in -- those must never
    // leak into metaInfo.
    Receipt receipt = newReceipt();
    String receiptJson = Receipt.PARSER.toJsonObject(receipt).toJSONString();

    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.getDocumentInformation().setProducer("some-pdf-lib 1.0");
      doc.getDocumentInformation().setTitle("Receipt");
      CODEC.embedReceiptAttachment(doc, receiptJson);
      CODEC.embedCustomInfo(doc, Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt"));

      ReceiptRenderData data = CODEC.extract(doc).orElseThrow();
      assertEquals(Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt"), data.metaInfo());
    }
  }

  @Test
  public void testExtractFromBytes_roundtrips() throws IOException {
    Receipt receipt = newReceipt();
    String receiptJson = Receipt.PARSER.toJsonObject(receipt).toJSONString();

    byte[] pdfBytes;
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      CODEC.embedReceiptAttachment(doc, receiptJson);
      CODEC.embedCustomInfo(doc, Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt"));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      pdfBytes = out.toByteArray();
    }

    ReceiptRenderData data = CODEC.extract(pdfBytes).orElseThrow();
    assertEquals(receipt, data.receipt());
    assertEquals(Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt"), data.metaInfo());
  }

  @Test
  public void testEmbedViaInterface_roundtrips() throws IOException {
    Receipt receipt = newReceipt();
    Map<String, String> refs = Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt");
    ReceiptRenderData data = new ReceiptRenderData(receipt, null, refs);

    byte[] pdfBytes;
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      pdfBytes = out.toByteArray();
    }

    ReceiptCodec codec = CODEC;
    byte[] embedded = codec.embed(pdfBytes, data);
    ReceiptRenderData extracted = codec.extract(embedded).orElseThrow();
    assertEquals(receipt, extracted.receipt());
    assertEquals(refs, extracted.metaInfo());
  }

  @Test
  public void testEmbedTemplateAttachment_roundtrips() throws IOException {
    Receipt receipt = newReceipt();
    String templateContent = "{receipt.chains.get(0).chainUri}";

    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      CODEC.embedReceiptAttachment(doc, receipt);
      CODEC.embedTemplateAttachment(doc, templateContent);

      ReceiptRenderData data = CODEC.extract(doc).orElseThrow();
      assertEquals(receipt, data.receipt());
      assertEquals(templateContent, data.templateContent());
      assertTrue(data.isKernel());
    }
  }

  @Test
  public void testNoTemplateAttachment_templateContentStaysNull() throws IOException {
    Receipt receipt = newReceipt();
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      CODEC.embedReceiptAttachment(doc, receipt);

      ReceiptRenderData data = CODEC.extract(doc).orElseThrow();
      assertNull(data.templateContent());
      assertFalse(data.isKernel());
    }
  }

  @Test
  public void testEmbedViaInterfaceWithTemplateContent_roundtrips() throws IOException {
    Receipt receipt = newReceipt();
    String templateContent = "{receipt.chains.get(0).chainUri}";
    Map<String, String> refs = Map.of(RenderConstants.TEMPLATE_NAME_REF, "order-receipt");
    ReceiptRenderData data = new ReceiptRenderData(receipt, templateContent, refs);

    byte[] pdfBytes;
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      pdfBytes = out.toByteArray();
    }

    ReceiptCodec codec = CODEC;
    byte[] embedded = codec.embed(pdfBytes, data);
    ReceiptRenderData extracted = codec.extract(embedded).orElseThrow();
    assertEquals(receipt, extracted.receipt());
    assertEquals(refs, extracted.metaInfo());
    assertEquals(templateContent, extracted.templateContent());
    assertTrue(extracted.isKernel());
  }

  @Test
  public void testReembedReceiptAttachment_replacesContentAndAfArrayStaysDeduped() throws IOException {
    // Re-embedding under the same attachment name must replace both the
    // Names-tree entry and its /AF entry, not leave the old one orphaned.
    Receipt receiptV1 = newReceipt();
    Receipt receiptV2 = newOtherReceipt();

    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      CODEC.embedReceiptAttachment(doc, receiptV1);
      CODEC.embedReceiptAttachment(doc, receiptV2);

      ReceiptRenderData data = CODEC.extract(doc).orElseThrow();
      assertEquals(receiptV2, data.receipt());
      assertEquals(1, afArray(doc).size());
    }
  }

  @Test
  public void testReembedTemplateAttachment_doesNotGrowAfArray() throws IOException {
    Receipt receipt = newReceipt();
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      CODEC.embedReceiptAttachment(doc, receipt);
      CODEC.embedTemplateAttachment(doc, "template v1");
      CODEC.embedTemplateAttachment(doc, "template v2");

      ReceiptRenderData data = CODEC.extract(doc).orElseThrow();
      assertEquals("template v2", data.templateContent());
      // one receipt attachment + one template attachment, no stale duplicates
      assertEquals(2, afArray(doc).size());
    }
  }

  @Test
  public void testEmbedReceiptAttachmentWithReceiptOverload_matchesJsonOverload() throws IOException {
    Receipt receipt = newReceipt();
    String receiptJson = Receipt.PARSER.toJsonObject(receipt).toJSONString();

    try (PDDocument docViaReceipt = new PDDocument();
         PDDocument docViaJson = new PDDocument()) {
      docViaReceipt.addPage(new PDPage());
      docViaJson.addPage(new PDPage());
      CODEC.embedReceiptAttachment(docViaReceipt, receipt);
      CODEC.embedReceiptAttachment(docViaJson, receiptJson);

      assertEquals(
          CODEC.extract(docViaJson).orElseThrow().receipt(),
          CODEC.extract(docViaReceipt).orElseThrow().receipt());
    }
  }
}
