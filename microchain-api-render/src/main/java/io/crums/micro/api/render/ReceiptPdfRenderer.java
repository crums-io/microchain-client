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

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

/**
 * Converts rendered HTML to PDF, embedding the receipt JSON that produced
 * it as an attached file -- satisfying CLAUDE.md's "embed backing data (the
 * receipt) as a comment (or std place reserved for metadata)" using the PDF
 * spec's actual reserved mechanism (the {@code /Names/EmbeddedFiles} name
 * tree) rather than an HTML-comment-style hack, which wouldn't apply to a
 * PDF anyway.
 *
 * <p>Imports only {@code com.openhtmltopdf.*} / {@code org.apache.pdfbox.*}
 * -- both plain-Java libraries, no CDI -- so this is reusable as-is from a
 * future plain-Java client.</p>
 */
public final class ReceiptPdfRenderer {

  public static final String ATTACHMENT_NAME = "receipt.json";

  private ReceiptPdfRenderer() { }

  /**
   * @param html        rendered HTML to lay out and rasterize
   * @param receiptJson the exact {@code Receipt} JSON used to render
   *                    {@code html}, embedded verbatim as an attachment
   */
  public static byte[] render(String html, String receiptJson) throws IOException {
    PdfRendererBuilder builder = new PdfRendererBuilder();
    builder.withHtmlContent(html, null);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PdfBoxRenderer renderer = builder.buildPdfRenderer()) {
      PDDocument doc = renderer.createPDFKeepOpen();
      embedReceipt(doc, receiptJson);
      doc.save(out);
      doc.close();
    }
    return out.toByteArray();
  }

  private static void embedReceipt(PDDocument doc, String receiptJson) throws IOException {
    byte[] bytes = receiptJson.getBytes(StandardCharsets.UTF_8);
    PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream(bytes));
    ef.setSubtype("application/json");
    ef.setSize(bytes.length);

    PDComplexFileSpecification fs = new PDComplexFileSpecification();
    fs.setFile(ATTACHMENT_NAME);
    fs.setFileUnicode(ATTACHMENT_NAME);
    fs.setFileDescription("Backing receipt data (JSON) used to render this PDF.");
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
    map.put(ATTACHMENT_NAME, fs);
    if (efTree == null)
      efTree = new PDEmbeddedFilesNameTreeNode();
    efTree.setNames(map);
    names.setEmbeddedFiles(efTree);

    // /AF (Associated Files, ISO 32000-2) -- marks this attachment as
    // belonging to the document as a whole, not just a loose file.
    // Harmless, forward-looking; not required for a PDF viewer to show
    // the attachment, which the Names tree above already does on its own.
    COSArray af = new COSArray();
    af.add(fs.getCOSObject());
    catalog.getCOSObject().setItem(COSName.getPDFName("AF"), af);
  }
}
