# microchain-api-render

This module defines and implements a portable rendering DSL for data in a `Receipt`
(as defined in `microchain-api`). It leverages the
[Qute templating DSL](https://quarkus.io/guides/qute-reference/#core_features),
together with an object-model (`ReceiptViewModel`) that the Qute rendering script
runs against.

`ReceiptViewModel` (what a Qute script sees) exposes the following data and
capabilities:

1. The `Receipt` data object itself, of course. (Immutable.)
2. A settings object (`ReceiptRenderSettings`) that supports in-flight template-output
configuration from the script itself. It comes with sensible defaults. Examples of overrides
include how to format dates, how to represent (output) null values, and redacted values.
3. A `Calculator` object. The Qute scripting language itself has no support for
arithmetic. This object supports common use cases where a script needs to do arithmetic
(item-count x item-price, or for calculating totals, for example). It models the buttons on a
basic calculator, but with 10 memory slots instead of the usual one or two.

Two output formats are supported out-of-the-box: HTML and PDF.

## Origami packaging

Receipt renderings (whether in HTML or PDF) are annotated with meta data that
can be used to verify a rendering. At minimum, the meta data includes the
`Receipt` data that generated it. The end-goal, however, is for the meta data
to have sufficient information in order to reproduce the exact same rendering.
In turn, recreating a rendering requires access to the Qute script that rendered
the `Receipt`. Thus meta data may include a URL to the Qute script,
or alternatively, the actual Qute script source.

This origami-like ability to unfold a rendered receipt into its elemental
parts figures not just in verifying the provenance of a rendering; it also
facilitates receipt archival since the meta data is more compact than
the final representation, HTML or PDF.

## Package

`io.crums.micro.api.render` (exported) -- module `io.crums.micro.api.render`

## Maven coordinates

```xml
<dependency>
  <groupId>io.crums</groupId>
  <artifactId>microchain-api-render</artifactId>
</dependency>
```

Version omitted for now -- not yet published anywhere resolvable outside this reactor.

## Why this is its own module

This module is designed to work both in a Quarkus-managed server application,
and in a standalone client. Its only direct depedencies are `io.quarkus.qute.*`
types only -- no CDI, no Vert.x, no `@Inject`. `qute-core` itself has no such
couplings (just `jboss-logging` + `mutiny`), so this module stays usable in
most any application environment, not just Quarkus.

## Trusted vs. untrusted execution

A Qute template is executable logic, not passive markup, so this module
treats a template's provenance as a first-class design concern. By
default, every render is *guarded*: a bounded output size, a cell-access
budget, and a publish-time shape check all stand between an untrusted or
not-yet-published template and a runaway render, and `{#eval}` (the one
construct that could promote untrusted data to live template code) is
dropped entirely. `RenderingBuilder.unguarded()` opts out of those runtime
checks for a performance path -- but only for a template that's already
published and already passed its mandatory test-render; anything ad-hoc
belongs on the guarded default.

## Dependencies

- `microchain-api` (`requires transitive` -- `Receipt`/`ChainReceipt`/`SourceReceipt`, the types this module renders)
- `qute-core` (`io.quarkus.qute`, no CDI/Vert.x coupling)
- `openhtmltopdf-pdfbox` (LGPL; chosen over iText/Flying Saucer specifically because the server embedding this module is closed-source) + transitively, `pdfbox`

## Status

Server-side use (the server's own render service, behind
`/{user}/_receipt_templates/{name}/render` and `/_render_receipt`) is done
and in production, driven entirely through a CDI-injected
`RenderingBuilder`/`ReceiptViewModel`. Client-side use (`mchain`/`bindle`
rendering a receipt entirely locally) is still TODO -- see the top-level
README's [Presentation](../README.md#presentation) section. It'll go
through `RenderingBuilder` the same way the server does, not a
hand-built `Engine`: the `.engine` package isn't exported, by design (see
`QutePreprocesser`'s own class javadoc) -- Qute is this module's
rendering-DSL implementation, not part of its public surface.
