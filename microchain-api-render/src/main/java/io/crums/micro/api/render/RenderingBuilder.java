/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.crums.micro.api.render.engine.CappedRenderer;
import io.crums.micro.api.render.engine.QutePreprocesser;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;

import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Builds a complete HTML or PDF rendering of a {@link ReceiptViewModel}
 * against a Qute template, entirely within this module -- the one thing a
 * caller (server or, eventually, a plain-Java client with no server at
 * all) actually needs to drive end to end. Composes the pieces the rest of
 * this package provides: {@link QutePreprocesser}/{@link CappedRenderer}
 * for the actual Qute parse and render, then {@link ReceiptHtmlCodec}/
 * {@link ReceiptPdfCodec} to annotate the result with the receipt's own
 * backing data and any reference tags the caller supplies.
 *
 * <p>Not thread-safe, and not meant to be reused -- build one per render,
 * same as the {@code Receipt}/template pair it's wired to.</p>
 *
 * <p>Reference fields (the {@code templateXxx} setters) are the fixed,
 * known {@code crums:template-*} vocabulary {@link RenderConstants}
 * defines. {@link #putMeta} adds a caller-named one beyond that fixed
 * set (e.g. a template owner's own real name) -- validated, not an
 * open-ended {@code Map<String,String>} a caller could populate with
 * anything at all. Every reference field, fixed or caller-named, is
 * optional (an ad-hoc render with no published-template identity to
 * report sets none of them) but write-once: calling the same one twice
 * on one instance throws {@link IllegalStateException}, rather than
 * silently overwriting a value that was likely set by mistake. {@link
 * #metaInfo()} exposes whatever's been set so far as a read-only view --
 * there's no way to remove an entry once set, only to add ones that
 * aren't there yet.</p>
 */
public final class RenderingBuilder {

  /**
   * Generous for any real receipt page (even an elaborate multi-row HTML
   * render is a few hundred KB at most) -- small enough to bound a
   * runaway template's worst-case memory/output to a few MiB rather than
   * however large an unbounded loop could otherwise grow it.
   * <p>
   * REVIEW: the char-count budget, ought to be this value plus the size of
   * the template. Rationale: images in the template are base-64 encoded and
   * usage patterns are hard to predict. 
   * </p>
   */
  public static final int DEFAULT_MAX_CHARS = 1_000_000;

  /** @see #putMeta */
  public static final String META_KEY_PATTERN_STR = "[A-Za-z0-9][A-Za-z0-9:_.-]*";
  /** @see #putMeta */
  public static final int MAX_META_KEY_LENGTH = 128;
  /** @see #putMeta */
  public static final int MAX_META_VALUE_LENGTH = 4_000;

  private static final Pattern META_KEY_PATTERN = Pattern.compile(META_KEY_PATTERN_STR);

  private static final Set<String> RESERVED_REF_KEYS = Set.of(
      RenderConstants.TEMPLATE_OWNER_REF,
      RenderConstants.TEMPLATE_NAME_REF,
      RenderConstants.TEMPLATE_VERSION_REF,
      RenderConstants.TEMPLATE_SRC_REF);

  private final ReceiptViewModel viewModel;
  private ReceiptRenderData kernel;

  private String contentId;
  private int maxChars = DEFAULT_MAX_CHARS;
  private boolean embedTemplateContent;

  public RenderingBuilder(ReceiptViewModel viewModel, String templateContent) {
    this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
    Objects.requireNonNull(templateContent, "templateContent");
    this.kernel = new ReceiptRenderData(viewModel.unmeteredReceipt(), templateContent);
  }

  /** Overrides {@link #DEFAULT_MAX_CHARS}. */
  public RenderingBuilder maxChars(int maxChars) {
    this.maxChars = maxChars;
    return this;
  }

  /**
   * Whether {@link #buildHtml}/{@link #buildPdf} should also embed the
   * exact Qute template source ({@link #kernel}'s {@code
   * templateContent}) alongside the receipt -- default {@code false}.
   * The codecs ({@link ReceiptHtmlCodec#embed(String, ReceiptRenderData)}/
   * {@link ReceiptPdfCodec#embedTemplateAttachment}) make no decision of
   * their own here; this is the one place that does, decided fresh on
   * every call to {@link #buildHtml}/{@link #buildPdf} rather than
   * locked in at construction time.
   */
  public RenderingBuilder embedTemplateContent(boolean embed) {
    this.embedTemplateContent = embed;
    return this;
  }

  /**
   * Stable identity passed to Qute's own {@code Engine#parse} -- used only
   * for the template's own identity/error messages, not a cache key (Qute
   * doesn't cache against it). Defaults to {@code null} (Qute generates
   * its own); a caller resolving a named, published template may want to
   * pass its real content hash here instead, purely for more useful error
   * messages.
   */
  public RenderingBuilder contentId(String contentId) {
    this.contentId = contentId;
    return this;
  }

  /**
   * The {@code crums:template-owner} reference field -- see the class
   * javadoc. A {@link URI}, same treatment as {@link #templateSrc}: the
   * template owner's own page is a real, fetchable resource (e.g. {@code
   * https://crums.io/{user}}), not just a bare name.
   */
  public RenderingBuilder templateOwner(URI uri) {
    return setRefOnce(RenderConstants.TEMPLATE_OWNER_REF, uri.toString());
  }

  /** The {@code crums:template-name} reference field -- see the class javadoc. */
  public RenderingBuilder templateName(String name) {
    return setRefOnce(RenderConstants.TEMPLATE_NAME_REF, name);
  }

  /** The {@code crums:template-version} reference field -- see the class javadoc. */
  public RenderingBuilder templateVersion(long version) {
    return setRefOnce(RenderConstants.TEMPLATE_VERSION_REF, String.valueOf(version));
  }

  /** The {@code crums:template-src} reference field -- see the class javadoc. */
  public RenderingBuilder templateSrc(URI uri) {
    return setRefOnce(RenderConstants.TEMPLATE_SRC_REF, uri.toString());
  }

  /**
   * Adds one caller-named reference field beyond the fixed {@code
   * templateXxx} ones above -- e.g. a receipt-maker/template-owner
   * leaving their own real name on the rendering. Same guarantees as
   * every other reference field (write-once; embedded through the exact
   * same {@code <meta>}/custom-Info-dictionary mechanism, with the same
   * HTML-attribute escaping -- a free-form value never needs the
   * receipt-JSON's percent-encoding treatment, since that was specifically
   * about a {@code <script>} raw-text element's own parsing hazard, which
   * a {@code <meta>} attribute value was never exposed to), but {@code
   * key} is validated rather than trusted:
   * <ul>
   * <li>must not be one of the 4 reserved {@code crums:template-*} keys
   *     above -- those stay reachable only through their own dedicated
   *     setter, so its write-once guarantee can't be bypassed by routing
   *     the same key through here instead;</li>
   * <li>must match {@value #META_KEY_PATTERN_STR}, capped at {@value
   *     #MAX_META_KEY_LENGTH} characters -- a sane "name" shape, not
   *     arbitrary text;</li>
   * <li>{@code value} is capped at {@value #MAX_META_VALUE_LENGTH}
   *     characters -- generous for a short note, bounded against abuse
   *     (unlike the 4 fixed fields, a free-form value has no shape of its
   *     own to bound it naturally).</li>
   * </ul>
   */
  public RenderingBuilder putMeta(String key, String value) {
    Objects.requireNonNull(key, "key");
    if (RESERVED_REF_KEYS.contains(key))
      throw new IllegalArgumentException(
          "'" + key + "' is reserved -- use its own templateXxx(...) setter instead");
    if (key.length() > MAX_META_KEY_LENGTH || !META_KEY_PATTERN.matcher(key).matches())
      throw new IllegalArgumentException("invalid meta key: '" + key + "'");
    if (value != null && value.length() > MAX_META_VALUE_LENGTH)
      throw new IllegalArgumentException(
          "meta value for '" + key + "' exceeds the " + MAX_META_VALUE_LENGTH + "-character cap");
    return setRefOnce(key, value);
  }

  /**
   * Whatever reference fields have been set so far (insertion order), as
   * a read-only view of this builder's current {@link #kernel}: the
   * {@code templateXxx} setters and {@link #putMeta} are the only way to
   * add to it.
   */
  public Map<String, String> metaInfo() {
    return kernel.metaInfo();
  }

  /**
   * The receipt, template content, and reference fields set so far,
   * as a standalone, immutable {@link ReceiptRenderData}. Always {@link
   * ReceiptRenderData#isKernel() a kernel}, since this builder's own
   * constructor requires {@code templateContent}.
   */
  public ReceiptRenderData kernel() {
    return kernel;
  }

  private RenderingBuilder setRefOnce(String key, String value) {
    Objects.requireNonNull(value, key);
    // if (kernel.metaInfo().containsKey(key))
    //   throw new IllegalStateException(key + " already set");
    kernel = kernel.withMeta(key, value);
    return this;
  }

  /**
   * Renders and returns the final HTML, UTF-8 encoded, with the receipt
   * and any reference fields set so far already embedded. Qute's own
   * exceptions ({@code TemplateException}; {@link
   * RenderBudgetExceededException}; {@link RenderAccessBudgetExceededException};
   * {@link RenderInterruptedException}) propagate unwrapped -- this class
   * adds no context of its own, since it doesn't know (and shouldn't need
   * to know) whether it's backing a named, published template or ad-hoc
   * content; that's for whichever caller does know to add when it catches
   * one.
   */
  public byte[] buildHtml() {
    ReceiptRenderData toEmbed = embedTemplateContent ? kernel : kernel.withTemplateContent(null);
    String html = ReceiptHtmlCodec.CODEC.embed(renderHtmlString(), toEmbed);
    return html.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Renders to PDF: lays out/rasterizes the template's own output,
   * un-annotated (the HTML {@code <script>}/{@code <meta>} embedding
   * {@link #buildHtml} does would be redundant here -- the PDF gets its
   * own, PDF-native annotations below instead, and skipping the HTML ones
   * also means one less thing that has to parse as well-formed XML for
   * this step), then embeds the receipt as a file attachment and any
   * reference fields set so far as custom Info-dictionary entries, mutating
   * one {@link PDDocument} in place throughout -- never round-tripping
   * through serialized bytes just to add a second annotation to the same
   * document. Any checked {@code IOException} from the PDF pipeline
   * itself (layout, rasterization, or writing the document) is wrapped as
   * an unchecked {@link RenderIOException}; Qute's own exceptions from the
   * render step still propagate unwrapped, same as {@link #buildHtml}.
   */
  public byte[] buildPdf() {
    String html = renderHtmlString();

    PdfRendererBuilder builder = new PdfRendererBuilder();
    builder.withHtmlContent(html, null);
    try (PdfBoxRenderer renderer = builder.buildPdfRenderer()) {
      PDDocument doc = renderer.createPDFKeepOpen();
      ReceiptPdfCodec.CODEC.embedReceiptAttachment(doc, kernel.receipt());
      ReceiptPdfCodec.CODEC.embedCustomInfo(doc, kernel.metaInfo());
      if (embedTemplateContent)
        ReceiptPdfCodec.CODEC.embedTemplateAttachment(doc, kernel.templateContent());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      doc.close();
      return out.toByteArray();
    } catch (IOException e) {
      throw new RenderIOException(e);
    }
  }

  private String renderHtmlString() {
    Engine engine = QutePreprocesser.newEngine(viewModel);
    Template t = engine.parse(kernel.templateContent(), null, contentId);
    return CappedRenderer.render(t.data(viewModel), maxChars);
  }
}
