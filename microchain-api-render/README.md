# microchain-api-render

Renders a `Receipt` (from `microchain-api`) through a Qute template, as
HTML or PDF -- the shared, portable presentation layer behind the server's
named-template `/render` and ad-hoc `/_render_receipt` endpoints, and
usable the same way by a plain-Java client later (`mchain`/`bindle`), not
just a CDI-managed server.

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
package anyway, because it's tooling a caller reaches for only when
actually wiring up an `Engine` -- not something touched on the common
per-render path, which just feeds a `ReceiptViewModel` to an already-built
one.

## Entity / exception types (`io.crums.micro.api.render`)

| Class | Role |
|---|---|
| `ReceiptViewModel` | A `Receipt` paired with `ReceiptRenderSettings` and an access-count budget -- this instance itself, not just `receipt()`, is the root data object Qute is actually fed (`template.data(viewModel)`); carries no Qute dependency itself |
| `ReceiptRenderSettings` | Mutable, shareable settings for `cell.display`'s safe/normalized rendering: redacted/null sentinels, `DATE` format, `HASH` hash encoding (`BYTES` is always standard Base64, not configurable -- `HashEncoding`/`Base64_32` requires exactly 32 bytes, which only a `HASH` cell is guaranteed to be) |
| `ReceiptPdfRenderer` | HTML -> PDF, embedding the receipt JSON that produced it as an attached file (`/Names/EmbeddedFiles`), not an HTML-comment hack |
| `TemplateShapeGuard` | Publish-time check: rejects an oversized literal `{#for}`/`{#each}` range or excessive loop nesting -- shape alone, independent of any `Receipt` it's later rendered against |
| `TemplateParseException` | Stored template content doesn't parse as valid Qute -- the template's own fault (500 if exposed over REST) |
| `TemplateDataMismatchException` | Template referenced data the submitted `Receipt` doesn't have -- the caller's fault (422) |
| `RenderBudgetExceededException` | Rendered output exceeded the configured character budget (422) |
| `RenderAccessBudgetExceededException` | Render touched more cells than the configured access budget -- backstop for an empty-bodied loop that never trips the char budget (422) |
| `RenderInterruptedException` | Render aborted because its thread was interrupted (e.g. a caller gave up past a deadline) -- not a template/data fault |

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

// The engine is built FROM the view model, not alongside it -- that's the
// one public QutePreprocesser.newEngine overload, and it guarantees
// cell.display's resolver closes over the exact same ReceiptRenderSettings
// instance viewModel.settings() returns, so a template-authored
// settings.setXxx(...) call is guaranteed to take effect.
ReceiptViewModel viewModel = new ReceiptViewModel(receipt); // or with settings/maxAccesses
Engine engine = QutePreprocesser.newEngine(viewModel);
Template template = engine.parse(content, null, contentHash); // id is identity only, not a cache key

TemplateInstance instance = template.data(viewModel); // the view model itself is the root

String html;
try {
  html = CappedRenderer.render(instance, maxChars);
} catch (RenderBudgetExceededException | RenderAccessBudgetExceededException e) {
  // caller/data fault -- 422 if exposed over REST
} catch (TemplateException e) {
  // template doesn't parse / strict-rendering miss -- 500 for an
  // already-published template, 422 for ad-hoc/draft content
}

// Optional PDF, embedding the exact submitted receipt JSON as an attachment:
byte[] pdf = ReceiptPdfRenderer.render(html, receiptJson);
```

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
