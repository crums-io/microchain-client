/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

import io.crums.micro.api.receipt.Receipt;

/**
 * Embeds data an already-built {@link PDDocument} didn't otherwise
 * carry -- the receipt JSON that produced it, as an attached file, and/or
 * a set of named reference values, as custom Document Information
 * Dictionary entries -- and, the other way round, recovers that same
 * data back out of an already-annotated document. Satisfies CLAUDE.md's
 * "embed backing data (the receipt) as a comment (or std place reserved
 * for metadata)" using the PDF spec's own reserved mechanisms ({@code
 * /Names/EmbeddedFiles}; {@code PDDocumentInformation}'s custom-entry
 * support) rather than an HTML-comment-style hack, which wouldn't even
 * apply to a PDF. This class never renders or builds a {@link PDDocument}
 * itself -- see {@link RenderingBuilder} for that; the embed methods take
 * one already built and mutate it in place, so a caller doing both never
 * has to round-trip through serialized bytes just to add a second
 * annotation to the same document.
 *
 * <p>Embedding and recovery live in one class because they share the
 * exact wire format, same rationale as {@link ReceiptHtmlCodec}.</p>
 *
 * <p>Imports only {@code org.apache.pdfbox.*} -- a plain-Java library, no
 * CDI -- so this is reusable as-is from a future plain-Java client.</p>
 *
 * <p>Stateless -- use the shared {@link #CODEC} instance, not a new one.</p>
 */
public final class ReceiptPdfCodec implements ReceiptCodec {

  public static final ReceiptPdfCodec CODEC = new ReceiptPdfCodec();

  /**
   * The Info dictionary's own standard entries -- never ours, must be
   * excluded from {@link PDDocumentInformation#getMetadataKeys()}, which
   * (confirmed by reading {@code pdfbox}'s own source) returns every key
   * in the dictionary, not just the custom ones {@link #embedCustomInfo}
   * actually wrote. Without this filter, a PDF library that stamps its
   * own {@code Producer}/{@code CreationDate} (as most do) would silently
   * leak into {@link #extract}'s {@code metaInfo}.
   */
  private static final Set<String> STANDARD_INFO_KEYS = Set.of(
      "Title", "Author", "Subject", "Keywords", "Creator", "Producer",
      "CreationDate", "ModDate", "Trapped");

  private ReceiptPdfCodec() { }

  /**
   * Embeds {@code receiptJson} on {@code doc} as a named file attachment
   * (mutates {@code doc} in place -- does not save/serialize it).
   *
   * @param receiptJson the exact {@code Receipt} JSON {@code doc} was
   *                     rendered from, embedded verbatim
   */
  public void embedReceiptAttachment(PDDocument doc, String receiptJson) throws IOException {
    embedAttachment(
        doc, RenderConstants.RECEIPT_ATTACHMENT_NAME,
        receiptJson.getBytes(StandardCharsets.UTF_8), "application/json",
        "Backing receipt data (JSON) used to render this PDF.");
  }

  /** @see #embedReceiptAttachment(PDDocument, String) */
  public void embedReceiptAttachment(PDDocument doc, Receipt receipt) throws IOException {
    embedReceiptAttachment(doc, toJson(receipt));
  }

  /**
   * Embeds {@code templateContent} on {@code doc} as a named file
   * attachment (mutates {@code doc} in place -- does not save/serialize
   * it) -- the PDF-native counterpart to the template-content portion of
   * {@link ReceiptHtmlCodec#embed(String, ReceiptRenderData)}. Always
   * optional -- a caller decides whether to call it at all; see {@link
   * RenderingBuilder#embedTemplateContent(boolean)}.
   *
   * @param templateContent the exact Qute template source {@code doc}
   *                         was rendered from, embedded verbatim
   */
  public void embedTemplateAttachment(PDDocument doc, String templateContent) throws IOException {
    embedAttachment(
        doc, RenderConstants.TEMPLATE_ATTACHMENT_NAME,
        templateContent.getBytes(StandardCharsets.UTF_8), "text/plain",
        "Backing Qute template source used to render this PDF.");
  }

  /**
   * Shared embedding logic for {@link #embedReceiptAttachment}/{@link
   * #embedTemplateAttachment}: adds {@code bytes} to {@code doc}'s
   * {@code /Names/EmbeddedFiles} tree under {@code name}, and appends
   * (rather than overwrites) the catalog's {@code /AF} array -- a second
   * call embedding a second, differently-named attachment must not drop
   * the first one's {@code /AF} entry. Idempotent per {@code name}: a
   * second call under the same {@code name} (re-embedding) replaces both
   * the Names-tree entry and its {@code /AF} entry, rather than leaving
   * the old attachment's now-unreachable entries behind in either place.
   */
  private void embedAttachment(
      PDDocument doc, String name, byte[] bytes, String subtype, String description)
      throws IOException {

    PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream(bytes));
    ef.setSubtype(subtype);
    ef.setSize(bytes.length);

    PDComplexFileSpecification fs = new PDComplexFileSpecification();
    fs.setFile(name);
    fs.setFileUnicode(name);
    fs.setFileDescription(description);
    fs.setEmbeddedFile(ef);

    PDDocumentCatalog catalog = doc.getDocumentCatalog();
    PDDocumentNameDictionary names = catalog.getNames();
    if (names == null) {
      names = new PDDocumentNameDictionary(catalog);
      catalog.setNames(names);
    }
    PDEmbeddedFilesNameTreeNode efTree = names.getEmbeddedFiles();
    Map<String, PDComplexFileSpecification> map =
        efTree != null && efTree.getNames() != null
            ? new LinkedHashMap<>(efTree.getNames())
            : new LinkedHashMap<>();
    PDComplexFileSpecification old = map.put(name, fs);
    if (efTree == null)
      efTree = new PDEmbeddedFilesNameTreeNode();
    efTree.setNames(map);
    names.setEmbeddedFiles(efTree);

    // /AF (Associated Files, ISO 32000-2) -- marks this attachment as
    // belonging to the document as a whole, not just a loose file.
    // Harmless, forward-looking; not required for a PDF viewer to show
    // the attachment, which the Names tree above already does on its own.
    // Re-embedding under the same `name` just replaced `old` in the Names
    // tree above -- drop its COS object from /AF too, so a repeated
    // embed doesn't leave it behind as an orphaned entry nothing else
    // points to.
    COSBase existingAf = catalog.getCOSObject().getDictionaryObject(COSName.getPDFName("AF"));
    COSArray af = existingAf instanceof COSArray a ? a : new COSArray();
    if (old != null)
      af.remove(old.getCOSObject());
    af.add(fs.getCOSObject());
    catalog.getCOSObject().setItem(COSName.getPDFName("AF"), af);
  }

  private String toJson(Receipt receipt) {
    return Receipt.PARSER.toJsonObject(receipt).toJSONString();
  }

  /**
   * Embeds each entry of {@code refs} on {@code doc} as a custom entry of
   * its Document Information Dictionary (mutates {@code doc} in place --
   * does not save/serialize it) -- the PDF-native counterpart to the
   * reference-tag portion of {@link ReceiptHtmlCodec#embed(String,
   * ReceiptRenderData)}'s {@code <meta>} tags. A no-op if {@code refs}
   * is empty.
   */
  public void embedCustomInfo(PDDocument doc, Map<String, String> refs) {
    if (refs.isEmpty())
      return;
    PDDocumentInformation info = doc.getDocumentInformation();
    for (var e : refs.entrySet())
      info.setCustomMetadataValue(e.getKey(), e.getValue());
  }

  /**
   * Inverts {@link #embedReceiptAttachment}/{@link #embedCustomInfo}/
   * {@link #embedTemplateAttachment}: recovers whatever {@link
   * ReceiptRenderData} {@code doc} still carries. Empty if no embedded
   * receipt attachment is found (named {@value
   * RenderConstants#RECEIPT_ATTACHMENT_NAME}) -- a {@link
   * ReceiptRenderData} without a {@link Receipt} isn't meaningful (every
   * constructor requires one); any custom Info-dictionary entries found
   * are still collected into {@code metaInfo} either way.
   * {@code templateContent} is recovered -- and the returned instance is
   * {@link ReceiptRenderData#isKernel() a kernel} -- only if {@link
   * #embedTemplateAttachment} was actually called on this document;
   * otherwise it's {@code null}, same as before.
   */
  public Optional<ReceiptRenderData> extract(PDDocument doc) throws IOException {
    PDComplexFileSpecification fs = attachmentSpec(doc, RenderConstants.RECEIPT_ATTACHMENT_NAME);
    if (fs == null)
      return Optional.empty();
    PDEmbeddedFile ef = fs.getEmbeddedFile();
    if (ef == null)
      return Optional.empty();

    String receiptJson = new String(ef.toByteArray(), StandardCharsets.UTF_8);
    Receipt receipt = Receipt.PARSER.toEntity(receiptJson);

    Map<String, String> metaInfo = new LinkedHashMap<>();
    PDDocumentInformation info = doc.getDocumentInformation();
    for (String key : info.getMetadataKeys())
      if (!STANDARD_INFO_KEYS.contains(key))
        metaInfo.put(key, info.getCustomMetadataValue(key));

    String templateContent = null;
    PDComplexFileSpecification templateFs = attachmentSpec(doc, RenderConstants.TEMPLATE_ATTACHMENT_NAME);
    if (templateFs != null) {
      PDEmbeddedFile templateEf = templateFs.getEmbeddedFile();
      if (templateEf != null)
        templateContent = new String(templateEf.toByteArray(), StandardCharsets.UTF_8);
    }

    return Optional.of(new ReceiptRenderData(receipt, templateContent, metaInfo));
  }

  /**
   * @see #extract(PDDocument)
   */
  @Override 
  public Optional<ReceiptRenderData> extract(byte[] pdfBytes) throws RenderIOException {
    try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
      return extract(doc);
    } catch (IOException iox) {
      throw new RenderIOException(iox);
    }
  }


  /**
   * As {@link #embedReceiptAttachment(PDDocument, Receipt)}/{@link
   * #embedCustomInfo}, plus -- if {@code data.templateContent()} is
   * non-null -- {@link #embedTemplateAttachment}. The codec itself makes
   * no decision here: whatever {@code data} carries gets embedded; it's
   * the caller's job (see {@link RenderingBuilder#embedTemplateContent(
   * boolean)}) to decide what {@code data} carries in the first place.
   */
  @Override
  public byte[] embed(byte[] pdfBytes, ReceiptRenderData data)
      throws RenderIOException {
    try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
      embedReceiptAttachment(doc, data.receipt());
      embedCustomInfo(doc, data.metaInfo());
      if (data.templateContent() != null)
        embedTemplateAttachment(doc, data.templateContent());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    } catch (IOException iox) {
      throw new RenderIOException(iox);
    }
  }

  private PDComplexFileSpecification attachmentSpec(PDDocument doc, String name) throws IOException {
    PDDocumentCatalog catalog = doc.getDocumentCatalog();
    PDDocumentNameDictionary names = catalog.getNames();
    if (names == null)
      return null;
    PDEmbeddedFilesNameTreeNode efTree = names.getEmbeddedFiles();
    if (efTree == null)
      return null;
    return efTree.getValue(name);
  }
}
