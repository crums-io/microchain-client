/*
 * Copyright 2026 Babak Farhang
 */

/**
 * A portable rendering DSL for a {@link io.crums.micro.api.receipt.Receipt},
 * built on the <a href="https://quarkus.io/guides/qute-reference/#core_features">Qute
 * templating language</a>, producing HTML or PDF.
 *
 * <h2>What a Qute script sees</h2>
 *
 * <p>A script runs against a {@link io.crums.micro.api.render.ReceiptViewModel},
 * the root object actually handed to Qute ({@code Template#data}). It
 * exposes:</p>
 * <ol>
 * <li>The {@code Receipt} itself (immutable), via {@link
 *     io.crums.micro.api.render.ReceiptViewModel#receipt()}.</li>
 * <li>A settings object, {@link io.crums.micro.api.render.ReceiptRenderSettings},
 *     configurable in-flight from the script itself -- how dates are
 *     formatted, how null/redacted values are represented, and so on.</li>
 * <li>A {@link io.crums.micro.api.render.Calculator}, since Qute itself has
 *     no arithmetic operators -- models a basic calculator's buttons, with
 *     10 memory slots instead of the usual one or two.</li>
 * </ol>
 *
 * <p>Binding is fully positional, so template syntax looks
 * like {@code {chains.get(0).rows.get(0).cells.get(0).display}}, {@code
 * {chains.get(1).isAnchored}}, {@code {#for item in
 * chains.get(1).rows}...{/for}}.</p>
 *
 * <h2>Origami packaging</h2>
 *
 * <p>A rendering (HTML or PDF) is annotated with metadata that can verify
 * it: at minimum, the {@code Receipt} data that produced it; optionally,
 * enough to reproduce the exact same rendering -- a reference to the Qute
 * script (a URL), or its literal source. This origami-like ability to
 * unfold a rendered receipt back into its elemental parts serves both
 * provenance verification and archival, since the metadata is more
 * compact than the final HTML/PDF representation.</p>
 *
 * <h2>End-to-end usage</h2>
 *
 * <pre>{@code
 * // Publish-time only -- shape check before any receipt is ever rendered
 * // against this content:
 * TemplateShapeGuard.validate(content);
 *
 * RenderingBuilder rb = new RenderingBuilder(receipt, content)
 *     .contentId(contentHash)        // optional -- Engine#parse's own identity, not a cache key
 *     .maxChars(maxChars)            // optional -- defaults to RenderingBuilder.DEFAULT_MAX_CHARS
 *     .embedTemplateContent(true)    // optional -- also embed the literal template source; default false
 *     .templateSrc(srcUri)           // optional -- each templateXxx(...) reference field is its own setter,
 *                                     // skipped entirely if never set
 *     .unguarded();                  // optional -- ONLY for already-published, already-test-rendered
 *                                     // content; omit this for anything ad-hoc or not-yet-published
 *
 * byte[] html;
 * try {
 *   html = rb.buildHtml(); // receipt + reference tags (+ template source, if opted in) already embedded
 * } catch (RenderBudgetExceededException | RenderAccessBudgetExceededException e) {
 *   // caller/data fault -- 422 if exposed over REST
 * } catch (TemplateException e) {
 *   // template doesn't parse / strict-rendering miss -- 500 for an
 *   // already-published template, 422 for ad-hoc/draft content
 * }
 *
 * // Optional PDF instead -- same data, embedded the PDF-native way (file
 * // attachment(s) + custom Info-dictionary entries) instead of inline:
 * byte[] pdf = rb.buildPdf(); // RenderIOException (unchecked) on a PDF-pipeline I/O failure
 * }</pre>
 *
 * <p>Recovering that data later (e.g. verifying an archived receipt page)
 * is the inverse call: {@code ReceiptHtmlCodec.CODEC.extract(html)} /
 * {@code ReceiptPdfCodec.CODEC.extract(pdfBytes)}, both returning {@code
 * Optional<ReceiptRenderData>} -- empty only if no receipt was ever
 * embedded in the first place.</p>
 *
 * <p>{@link io.crums.micro.api.render.RenderingBuilder} is the only way in
 * from outside this module -- the {@code .engine} subpackage it composes
 * internally is deliberately not exported; see that subpackage's own
 * package-info for why. A caller embedding into an HTML/PDF it rendered
 * some other way can still reach the embedding step directly: {@code
 * ReceiptHtmlCodec.CODEC}/{@code ReceiptPdfCodec.CODEC}.</p>
 */
package io.crums.micro.api.render;
