# microchain-api-render

Renders a `Receipt` (from `microchain-api`) through a Qute template, as
HTML or PDF -- the shared, portable presentation layer behind the server's
named-template `/render` and ad-hoc `/_render_receipt` endpoints, and
usable the same way by a plain-Java client later (`mchain`/`bindle`), not
just a CDI-managed server. `RenderingBuilder` is the one class a caller
actually needs: build a `ReceiptViewModel`, hand it and a template string
to the builder, call `buildHtml()`/`buildPdf()`. Everything else in this
module is either what the builder composes internally, or lower-level
pieces available to a caller that wants more control.

## Package

`io.crums.micro.api.render` + `io.crums.micro.api.render.engine` (both
exported) -- module `io.crums.micro.api.render`

## Maven coordinates

```xml
<dependency>
  <groupId>io.crums</groupId>
  <artifactId>microchain-api-render</artifactId>
</dependency>
```

Version omitted for now -- not yet published anywhere resolvable outside this reactor.

## Why this is its own module

Everything here is written against plain `io.quarkus.qute.*` types only --
no CDI, no Vert.x, no `@Inject`. `qute-core` itself has no such coupling
(just `jboss-logging` + `mutiny`), so this module stays usable from a
hand-built `Engine` in a standalone JVM process, not only from inside the
Quarkus-managed server application that embeds it today.

Qute is part of the rendering DSL itself, not an implementation detail
worth hiding, so both packages are exported. `.engine` (`Engine`,
`EngineBuilder`, `QutePreprocesser`, `CappedRenderer`) stays in its own
package anyway, because it's tooling `RenderingBuilder` itself reaches for
when actually wiring up an `Engine` -- an ordinary caller using the builder
never touches it directly at all; it's there for one that wants a
different `Engine` configuration than the builder gives.

## Entity / exception types (`io.crums.micro.api.render`)

| Class | Role |
|---|---|
| `RenderingBuilder` | The actual entry point: parses/renders `content` against a `ReceiptViewModel` (via `.engine` below), then embeds the result via `ReceiptHtmlCodec`/`ReceiptPdfCodec` below. `byte[]` in, `byte[]` out for both `buildHtml()`/`buildPdf()` -- no checked exceptions; a PDF-pipeline I/O failure surfaces as `RenderIOException` instead. The 5 fixed reference fields (the `RenderConstants` vocabulary) are individual, named `templateXxx(...)` setters -- `templateOwner`/`templateSrc` take a `URI` (both resolve to a real, fetchable page), the rest a `String`/`long`. `putMeta(key, value)` adds a caller-named reference beyond that fixed set (key/value shape-validated, length-capped). Every reference field, fixed or caller-named, is optional but write-once -- calling the same one twice throws. `embedTemplateContent(boolean)` (default `false`) decides, fresh on every `buildHtml()`/`buildPdf()` call, whether the literal Qute template source is also embedded alongside the receipt -- the codecs make no such decision themselves. `kernel()` exposes the accumulated `ReceiptRenderData` directly |
| `ReceiptViewModel` | A `Receipt` paired with `ReceiptRenderSettings` and an access-count budget -- this instance itself, not just `receipt()`, is the root data object Qute is actually fed (`template.data(viewModel)`); carries no Qute dependency itself |
| `ReceiptRenderSettings` | Mutable, shareable settings for `cell.display`'s safe/normalized rendering: redacted/null sentinels, `DATE` format, `HASH` hash encoding (`BYTES` is always standard Base64, not configurable -- `HashEncoding`/`Base64_32` requires exactly 32 bytes, which only a `HASH` cell is guaranteed to be) |
| `ReceiptRenderData` | The "chunky argument" both codecs below embed/extract in one pass: a `Receipt`, its `metaInfo` reference tags, and -- optionally -- the exact literal Qute template source (`templateContent`; present only on an instance actually meant to drive a render, see `isKernel()`). Immutable; `withXxx(...)`/`sansTemplateContent()` return a modified copy, never mutate in place |
| `ReceiptCodec` | Documentation interface both codecs below implement (`embed(byte[], ReceiptRenderData)` / `extract(byte[])`) -- in practice each codec is used directly, not through this interface |
| `ReceiptHtmlCodec` | Singleton (`CODEC`). `embed(String html, ReceiptRenderData data)` is the sole embedding entry point -- one pass over `html` that inserts the receipt as a percent-encoded `<script type="application/json">`, the template source (if present) as a second, similarly encoded `<script type="text/plain">`, and `metaInfo` as `<meta ... data-crums-kind="ref">` tags. `extract(String)` inverts it. Idempotent: re-embedding first strips any prior embedding of its own, rather than appending a stale duplicate alongside the new one |
| `ReceiptPdfCodec` | The PDF counterpart -- also a singleton (`CODEC`). `embedReceiptAttachment`/`embedTemplateAttachment` add named file attachments (`/Names/EmbeddedFiles`); `embedCustomInfo` adds `metaInfo` as custom Document Information Dictionary entries; all three mutate an already-built `PDDocument` in place (never round-tripping through serialized bytes to add a second annotation to the same document). Re-embedding under the same attachment name replaces it cleanly, `/AF` catalog entry included -- no orphaned duplicates left behind |
| `RenderConstants` | The naming constants `RenderingBuilder`'s `templateXxx(...)` setters and the two codecs above actually use -- receipt/template `<script>` ids and attachment names, the `data-crums-kind` marker, and the `crums:template-*` reference-tag names -- centralized so a reader of a rendered page (a verifier tool, a future `bindle`) has one place to get the exact strings from |
| `TemplateShapeGuard` | Publish-time check: rejects an oversized literal `{#for}`/`{#each}` range or excessive loop nesting -- shape alone, independent of any `Receipt` it's later rendered against |
| `RenderException` | Common base for every unchecked exception below -- lets a caller catch any rendering failure through one type when it has no need to tell them apart, without reaching for the much broader `RuntimeException`; always one of the concrete subtypes, never thrown bare |
| `TemplateParseException` | Stored template content doesn't parse as valid Qute -- the template's own fault (500 if exposed over REST) |
| `TemplateDataMismatchException` | Template referenced data the submitted `Receipt` doesn't have -- the caller's fault (422) |
| `RenderBudgetExceededException` | Rendered output exceeded the configured character budget (422) |
| `RenderAccessBudgetExceededException` | Render touched more cells than the configured access budget -- backstop for an empty-bodied loop that never trips the char budget (422) |
| `RenderInterruptedException` | Render aborted because its thread was interrupted (e.g. a caller gave up past a deadline) -- not a template/data fault |
| `RenderIOException` | Wraps a checked `IOException` from the PDF pipeline itself (layout, rasterization, writing the document) -- an operational failure, not a caller/template fault |

## Engine-wiring tooling (`io.crums.micro.api.render.engine`)

| Class | Role |
|---|---|
| `QutePreprocesser` | Builds an `Engine` wired for this exactly: `ReflectionValueResolver` for property/method access on real domain objects (`Receipt.chains()`, `ChainReceipt`/`SourceReceipt.rows()`, `SourceRow.cells()`, ...), plus one virtual property `cell.display`. `{#eval}` is deliberately dropped -- the one built-in construct that's genuinely self-referential and the one way untrusted cell data could be promoted to live template code. `strictRendering(true)`: a typo'd property/method reference fails fast rather than rendering blank. |
| `CappedRenderer` | Renders via `TemplateInstance#consume` (not `render()`/`renderAsync()`) so output can be aborted mid-stream the moment it crosses the char budget, rather than only after a huge buffer already exists |

## End-to-end usage

Binding is fully positional -- there's no column-name join here (that's a
separate, still-open concern; see the top-level README's `/columns`
TODO) -- so template syntax looks like `{chains.get(0).rows.get(0).cells.get(0).display}`,
`{chains.get(1).isAnchored}`, `{#for item in chains.get(1).rows}...{/for}`.

```java
// Publish-time only -- shape check before any receipt is ever rendered
// against this content:
TemplateShapeGuard.validate(content);

ReceiptViewModel viewModel = new ReceiptViewModel(receipt); // or with settings/maxAccesses
RenderingBuilder rb = new RenderingBuilder(viewModel, content)
    .contentId(contentHash)        // optional -- Engine#parse's own identity, not a cache key
    .maxChars(maxChars)            // optional -- defaults to RenderingBuilder.DEFAULT_MAX_CHARS
    .embedTemplateContent(true)    // optional -- also embed the literal template source; default false
    .templateSrc(srcUri);          // optional -- each templateXxx(...) reference field is its own setter,
                                    // write-once (throws if called twice), and skipped entirely if never set

byte[] html;
try {
  html = rb.buildHtml(); // receipt + reference tags (+ template source, if opted in) already embedded
} catch (RenderBudgetExceededException | RenderAccessBudgetExceededException e) {
  // caller/data fault -- 422 if exposed over REST
} catch (TemplateException e) {
  // template doesn't parse / strict-rendering miss -- 500 for an
  // already-published template, 422 for ad-hoc/draft content
}

// Optional PDF instead -- same data, embedded the PDF-native way (file
// attachment(s) + custom Info-dictionary entries) instead of inline:
byte[] pdf = rb.buildPdf(); // RenderIOException (unchecked) on a PDF-pipeline I/O failure
```

Recovering that data later (e.g. verifying an archived receipt page) is
the inverse call: `ReceiptHtmlCodec.CODEC.extract(html)` /
`ReceiptPdfCodec.CODEC.extract(pdfBytes)`, both returning
`Optional<ReceiptRenderData>` -- empty only if no receipt was ever
embedded in the first place.

A caller that wants finer control than the builder gives -- a different
`Engine` configuration, or embedding into an HTML/PDF it rendered some
other way -- can reach for the pieces `RenderingBuilder` itself composes
directly: `QutePreprocesser.newEngine(viewModel)` + `CappedRenderer.render`
for the Qute step, `ReceiptHtmlCodec.CODEC`/`ReceiptPdfCodec.CODEC` for the
embedding step.

A render thread should observe interruption (`Thread.interrupted()`) so a
timed-out render's thread is actually reclaimed rather than abandoned --
`SourceRowView`'s `AccessListener` hook in `microchain-api` is what makes
that possible without any Qute-specific code needed to detect it.

## Dependencies

- `microchain-api` (`requires transitive` -- `Receipt`/`ChainReceipt`/`SourceReceipt`, the types this module renders)
- `qute-core` (`io.quarkus.qute`, no CDI/Vert.x coupling)
- `openhtmltopdf-pdfbox` (LGPL; chosen over iText/Flying Saucer specifically because the server embedding this module is closed-source) + transitively, `pdfbox`

## Status

Server-side use (the server's own render service, behind
`/{user}/_receipt_templates/{name}/render` and `/_render_receipt`) is done
and in production. Client-side use (`mchain`/`bindle` rendering a receipt
entirely locally, against a hand-built `Engine` instead of a CDI-injected
one) is still TODO -- see the top-level README's
[Presentation](../README.md#presentation) section.
