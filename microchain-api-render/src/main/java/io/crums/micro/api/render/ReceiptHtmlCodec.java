/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.crums.micro.api.receipt.Receipt;

/**
 * Embeds data a rendered HTML page didn't otherwise carry -- the exact
 * receipt JSON that produced it, and/or a set of named reference tags
 * (e.g. where to re-fetch the template that rendered it) -- and, the
 * other way round, recovers that same data back out of an already
 * embedded page. The HTML counterpart to {@link ReceiptPdfCodec} (an
 * embedded file attachment and custom Info-dictionary entries, instead of
 * a {@code <script>} and {@code <meta>} tags), so an HTML render can
 * carry enough of its own backing data to be archived or re-verified
 * later without the original request. This class never renders anything
 * itself -- see {@link RenderingBuilder} for the actual Qute parse/render
 * step embedding is meant to follow.
 *
 * <p>Embedding and recovery live in one class because they share the
 * exact wire format: the matching regex a change to the embedded shape
 * would also have to change sits right next to the code that emits that
 * shape, rather than drifting out of sync in a separate file.</p>
 *
 * <p>Stateless -- use the shared {@link #CODEC} instance, not a new one.</p>
 */
public final class ReceiptHtmlCodec implements ReceiptCodec {

  public static final ReceiptHtmlCodec CODEC = new ReceiptHtmlCodec();

  private static final Pattern HEAD_CLOSE = Pattern.compile("</head>", Pattern.CASE_INSENSITIVE);
  private static final Pattern BODY_CLOSE = Pattern.compile("</body>", Pattern.CASE_INSENSITIVE);

  private static final Pattern RECEIPT_SCRIPT = Pattern.compile(
      "<script\\s+id=\"" + Pattern.quote(RenderConstants.RECEIPT_SCRIPT_ID) + "\"" +
      "\\s+type=\"application/json\"\\s+" + Pattern.quote(RenderConstants.KIND_ATTR) +
      "=\"" + Pattern.quote(RenderConstants.KIND_RECEIPT) + "\">(.*?)</script>",
      Pattern.DOTALL);

  private static final Pattern META_REF = Pattern.compile(
      "<meta\\s+name=\"([^\"]*)\"\\s+content=\"([^\"]*)\"\\s+" +
      Pattern.quote(RenderConstants.KIND_ATTR) + "=\"" + Pattern.quote(RenderConstants.KIND_REF) +
      "\"\\s*/>");

  private static final Pattern TEMPLATE_SCRIPT = Pattern.compile(
      "<script\\s+id=\"" + Pattern.quote(RenderConstants.TEMPLATE_SCRIPT_ID) + "\"" +
      "\\s+type=\"text/plain\"\\s+" + Pattern.quote(RenderConstants.KIND_ATTR) +
      "=\"" + Pattern.quote(RenderConstants.KIND_TEMPLATE) + "\">(.*?)</script>",
      Pattern.DOTALL);

  private ReceiptHtmlCodec() { }

  /**
   * Returns {@code html} with {@code data}'s receipt, reference tags,
   * and (if present) template source all embedded, in one pass over
   * {@code html} -- the sole embedding entry point this class exposes.
   * Takes the "chunky argument" {@link ReceiptRenderData} rather than
   * separate parameters so the shape can evolve (a new field on {@code
   * ReceiptRenderData}) without a new overload here, and so a caller
   * round-tripping through {@link #extract(String)} never has to pick
   * pieces back apart to re-embed them. The codec makes no decision of
   * its own about what to embed: whatever {@code data} carries gets
   * embedded; it's the caller's job (see {@link
   * RenderingBuilder#embedTemplateContent(boolean)}) to decide what
   * {@code data} carries in the first place.
   *
   * <p>The receipt is embedded as an inert, non-executing {@code
   * <script>} element (id {@value RenderConstants#RECEIPT_SCRIPT_ID},
   * {@code data-crums-kind="receipt"}); the template source, if present,
   * as a second one (id {@value RenderConstants#TEMPLATE_SCRIPT_ID},
   * {@code data-crums-kind="template"}) -- both land immediately before
   * {@code </body>} if present, or at the very end otherwise (a template
   * is free-form Qute content, not guaranteed to be a full {@code
   * <html>} document). Both payloads are percent-encoded ({@link
   * URLEncoder}, the {@code application/x-www-form-urlencoded}
   * convention -- space becomes {@code +}, not {@code %20}; {@link
   * #extract(String)} decodes with the matching {@link URLDecoder}, not
   * a URI-component decoder that leaves {@code +} alone) before being
   * placed as each element's text content: both are arbitrary strings
   * this module doesn't control the shape of, and either could contain
   * a literal {@code </script>} substring, which would otherwise end the
   * element early (HTML's raw-text tokenizer inside {@code <script>} has
   * no concept of JSON's or Qute's own quoting -- it just scans for that
   * one literal sequence) and hand control back to ordinary HTML parsing
   * for whatever follows. Percent-encoding removes every character HTML
   * treats as meaningful ({@code < > & " ' /}) from the payload entirely,
   * not just that one sequence.</p>
   *
   * <p>Each entry of {@code data.metaInfo()} becomes one self-closing
   * {@code <meta name="..." content="..." data-crums-kind="ref" />} tag
   * (insertion order), placed immediately before {@code </head>} if
   * present; failing that, sharing the receipt/template scripts' {@code
   * </body>} anchor; failing that, at the very end. A template's own
   * content isn't guaranteed to have a {@code <head>} at all -- confirmed
   * to actually happen in practice, not just a hypothetical -- so falling
   * through to {@code </body>} keeps these tags inside the document
   * rather than landing after a {@code </html>} that may not even be
   * there. Reference-tag values are HTML-attribute-escaped ({@code & "
   * < >}) -- short values meant to stay human-readable in the page
   * source, not an arbitrary blob like the receipt/template payloads
   * above. The {@value RenderConstants#KIND_ATTR} marker (value {@value
   * RenderConstants#KIND_REF}) is what lets {@link #extract(String)}
   * tell these tags apart from any unrelated {@code <meta>} a template's
   * own content might render into a real {@code <head>} (e.g. {@code
   * <meta name="viewport" ...>}) -- without it, a naive "collect every
   * {@code <meta name=... content=...>}" extraction would silently pick
   * up tags that were never ours to begin with. Self-closed, not left as
   * bare {@code <meta ...>}: confirmed by hitting it directly -- {@code
   * openhtmltopdf}'s HTML-to-PDF pipeline parses content through a
   * strict XML parser, which rejects an unclosed void element outside
   * the position (inside a real {@code <head>}) its HTML-cleaning front
   * end expects to auto-close one. {@code <meta ... />} is well-formed
   * XML regardless of position, and ordinary HTML parsing has always
   * tolerated the self-closing form identically to the bare one, so this
   * is safe for the HTML path too.</p>
   *
   * <p>When there's no real {@code <head>} (so the {@code <meta>} tags
   * share the {@code </body>} anchor), the relative order is: receipt
   * script, {@code <meta>} tags, template script -- confirmed case by
   * case, not just by inspection, against what calling discrete
   * embed-receipt/embed-meta/embed-template steps in that order would
   * produce (each such step re-anchoring to whatever {@code </body>}
   * match is last in the string at that point).</p>
   *
   * <p>Idempotent with respect to this codec's own prior embeddings:
   * {@code html} is first stripped of any receipt/template script or
   * reference {@code <meta>} tag this class itself could have produced
   * (matched by the exact {@value RenderConstants#KIND_ATTR}-marked shape
   * {@link #extract(String)} reads back), so re-embedding into an
   * already-embedded page replaces the old data rather than appending a
   * second, stale copy alongside it. Without this, a second call would
   * leave two {@code <script id="crums-receipt">} elements in the page,
   * and which one {@link #extract(String)} reads back would depend on
   * the element kind (first-match for scripts, last-match for meta tags)
   * -- a confusing, silent inconsistency rather than a real guarantee.</p>
   */
  public String embed(String html, ReceiptRenderData data) {
    html = stripExisting(html);
    String script = receiptScript(toJson(data.receipt()));
    String template = data.templateContent() == null ? null : templateScript(data.templateContent());
    Map<String, String> refs = data.metaInfo();

    if (refs.isEmpty())
      return insertBeforeLastMatchOrAppend(html, template == null ? script : script + template, BODY_CLOSE);

    String meta = metaFragment(refs);
    int receiptAt = lastIndexOf(html, BODY_CLOSE);
    if (receiptAt < 0)
      receiptAt = html.length();
    int headAt = lastIndexOf(html, HEAD_CLOSE);
    int metaAt = headAt >= 0 ? headAt : receiptAt;

    if (metaAt < receiptAt) {
      String bodyFragment = template == null ? script : script + template;
      return insertTwo(html, metaAt, meta, receiptAt, bodyFragment);
    }
    return template == null
        ? insertTwo(html, receiptAt, script, metaAt, meta)
        : insertThree(html, receiptAt, script, metaAt, meta, template);
  }

  /**
   * Inverts {@link #embed(String, ReceiptRenderData)}: recovers whatever
   * {@link ReceiptRenderData} {@code html} still carries. Empty if no
   * embedded receipt is found -- a {@link ReceiptRenderData} without a
   * {@link Receipt} isn't meaningful (every constructor requires one);
   * any reference {@code <meta>} tags found are still collected into
   * {@code metaInfo} either way. {@code templateContent} is recovered --
   * and the returned instance is {@link ReceiptRenderData#isKernel() a
   * kernel} -- only if a template script was actually embedded;
   * otherwise it's {@code null} (the page only ever carries reference
   * tags pointing at the template, e.g. {@link
   * RenderConstants#TEMPLATE_SRC_REF}, not the literal source itself).
   *
   * <p>Matched by the exact, literal shape {@link #embed(String,
   * ReceiptRenderData)} always emits, {@value RenderConstants#KIND_ATTR}
   * marker included, rather than through a general HTML parser -- this
   * module stays as dependency-light on the read side as it is on the
   * write side.</p>
   */
  public Optional<ReceiptRenderData> extract(String html) {
    Matcher rm = RECEIPT_SCRIPT.matcher(html);
    if (!rm.find())
      return Optional.empty();
    String receiptJson = URLDecoder.decode(rm.group(1), StandardCharsets.UTF_8);
    Receipt receipt = Receipt.PARSER.toEntity(receiptJson);

    Map<String, String> metaInfo = new LinkedHashMap<>();
    Matcher mm = META_REF.matcher(html);
    while (mm.find())
      metaInfo.put(unescapeAttr(mm.group(1)), unescapeAttr(mm.group(2)));

    Matcher tm = TEMPLATE_SCRIPT.matcher(html);
    String templateContent = tm.find() ? URLDecoder.decode(tm.group(1), StandardCharsets.UTF_8) : null;

    return Optional.of(new ReceiptRenderData(receipt, templateContent, metaInfo));
  }

  /**
   * @see #extract(String)
   */
  @Override
  public Optional<ReceiptRenderData> extract(byte[] html) {
    return extract(new String(html, StandardCharsets.UTF_8));
  }

  /** @see #embed(String, ReceiptRenderData) */
  @Override
  public byte[] embed(byte[] doc, ReceiptRenderData data) {
    String html = new String(doc, StandardCharsets.UTF_8);
    return embed(html, data).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Removes any receipt/template script or reference {@code <meta>} tag
   * already present in {@code html} -- i.e. anything {@link
   * #embed(String, ReceiptRenderData)} could itself have produced, matched
   * by the same patterns {@link #extract(String)} reads back. Makes {@link
   * #embed(String, ReceiptRenderData)} idempotent: re-embedding first
   * discards the old copies rather than leaving them alongside the new
   * ones. {@code replaceAll("")} is safe here (no backreference/{@code $}
   * hazard in the replacement) since the replacement is always empty.
   */
  private String stripExisting(String html) {
    html = RECEIPT_SCRIPT.matcher(html).replaceAll("");
    html = TEMPLATE_SCRIPT.matcher(html).replaceAll("");
    html = META_REF.matcher(html).replaceAll("");
    return html;
  }

  private String toJson(Receipt receipt) {
    return Receipt.PARSER.toJsonObject(receipt).toJSONString();
  }

  private String receiptScript(String receiptJson) {
    String encoded = URLEncoder.encode(receiptJson, StandardCharsets.UTF_8);
    return "<script id=\"" + RenderConstants.RECEIPT_SCRIPT_ID + "\" type=\"application/json\" " +
        RenderConstants.KIND_ATTR + "=\"" + RenderConstants.KIND_RECEIPT + "\">" + encoded + "</script>";
  }

  private String templateScript(String templateContent) {
    String encoded = URLEncoder.encode(templateContent, StandardCharsets.UTF_8);
    return "<script id=\"" + RenderConstants.TEMPLATE_SCRIPT_ID + "\" type=\"text/plain\" " +
        RenderConstants.KIND_ATTR + "=\"" + RenderConstants.KIND_TEMPLATE + "\">" + encoded + "</script>";
  }

  private String metaFragment(Map<String, String> refs) {
    StringBuilder meta = new StringBuilder();
    for (var e : refs.entrySet())
      meta.append("<meta name=\"").append(escapeAttr(e.getKey()))
          .append("\" content=\"").append(escapeAttr(e.getValue()))
          .append("\" ").append(RenderConstants.KIND_ATTR).append("=\"")
          .append(RenderConstants.KIND_REF).append("\" />");
    return meta.toString();
  }

  private String escapeAttr(String s) {
    return s.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Reverses {@link #escapeAttr} -- decode {@code &amp;} last. */
  private String unescapeAttr(String s) {
    return s.replace("&gt;", ">").replace("&lt;", "<")
        .replace("&quot;", "\"").replace("&amp;", "&");
  }

  /**
   * Tries each of {@code closingTags} in order, inserting {@code
   * fragment} right before the LAST match of the first one found;
   * appends at the very end only if none match at all. "Last" (not
   * "first") matters if a template's own content happens to contain
   * earlier text that looks like one of these closing tags -- inserting
   * before the last real occurrence keeps this appended after anything
   * the template produced, never mixed into it. Matched via {@link
   * Pattern}/{@link Matcher}, not {@code String#toLowerCase()}, since
   * case-folding can change a string's length for a handful of Unicode
   * characters (e.g. U+0130) and silently misalign a {@code
   * toLowerCase()}-derived index against the original string.
   */
  private String insertBeforeLastMatchOrAppend(String html, String fragment, Pattern... closingTags) {
    for (Pattern closingTag : closingTags) {
      int idx = lastIndexOf(html, closingTag);
      if (idx >= 0)
        return html.substring(0, idx) + fragment + html.substring(idx);
    }
    return html + fragment;
  }

  /** Index of the LAST match of {@code pattern} in {@code html}, or -1. */
  private int lastIndexOf(String html, Pattern pattern) {
    Matcher m = pattern.matcher(html);
    int idx = -1;
    while (m.find())
      idx = m.start();
    return idx;
  }

  /**
   * Inserts {@code frag1} at {@code idx1} and {@code frag2} at {@code
   * idx2} (into the ORIGINAL {@code html}'s coordinates -- {@code idx1 <=
   * idx2} is required) in one pass over {@code html} -- the single-scan
   * building block {@link #embed} uses to avoid building two separate
   * full-length copies of a potentially large string.
   */
  private String insertTwo(String html, int idx1, String frag1, int idx2, String frag2) {
    StringBuilder sb = new StringBuilder(html.length() + frag1.length() + frag2.length());
    sb.append(html, 0, idx1).append(frag1)
      .append(html, idx1, idx2).append(frag2)
      .append(html, idx2, html.length());
    return sb.toString();
  }

  /**
   * As {@link #insertTwo}, plus a third fragment ({@code frag3}) that
   * always immediately follows {@code frag2} at {@code idx2} -- the
   * {@code idx1 == idx2} case (the common one: no real {@code <head>})
   * puts all three fragments adjacent, in {@code frag1, frag2, frag3}
   * order, at that one point.
   */
  private String insertThree(
      String html, int idx1, String frag1, int idx2, String frag2, String frag3) {
    StringBuilder sb = new StringBuilder(
        html.length() + frag1.length() + frag2.length() + frag3.length());
    sb.append(html, 0, idx1).append(frag1)
      .append(html, idx1, idx2).append(frag2).append(frag3)
      .append(html, idx2, html.length());
    return sb.toString();
  }
}
