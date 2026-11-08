/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render.engine;

import java.util.concurrent.CompletionException;

import io.crums.micro.api.render.RenderBudgetExceededException;
import io.crums.micro.api.render.RenderException;
import io.quarkus.qute.TemplateException;
import io.quarkus.qute.TemplateInstance;

/**
 * Renders a {@link TemplateInstance} through its real primitive, {@link
 * TemplateInstance#consume}, instead of the more familiar {@code render()}
 * -- not for convenience, but because {@code consume} pushes each resolved
 * text fragment to its callback as it's produced, incrementally, while the
 * template is still being walked (verified against qute-core 3.40.1:
 * {@code render()}/{@code renderAsync()} are themselves both built on top
 * of {@code consume}, accumulating pushed chunks into a buffer). That
 * makes it possible to abort rendering mid-stream, the moment cumulative
 * output crosses {@code maxChars}, rather than only ever finding out after
 * a huge buffer already exists -- which is all a post-hoc length check on
 * {@code render()}'s return value could ever tell you.
 *
 * <p>This bounds output size (and, as a side effect, memory) -- not CPU in
 * general. A loop with an empty or non-emitting body never pushes a chunk
 * to check against the budget, so it can still spin indefinitely without
 * tripping this. That residual case is addressed separately, at
 * template-publish time, by capping literal loop ranges and nesting depth
 * (see {@code TemplateShapeGuard}), and at render time, by the trace-based
 * budget check {@code QutePreprocesser.newEngine(ReceiptViewModel)} wires
 * in -- not here.</p>
 */
public final class CappedRenderer {

  private CappedRenderer() {
  }

  /** Renders {@code instance}, aborting as soon as cumulative output exceeds {@code maxChars}. */
  public static String render(TemplateInstance instance, int maxChars) {
    StringBuilder out = new StringBuilder();
    try {
      instance.consume(chunk -> {
        out.append(chunk);
        if (out.length() > maxChars)
          throw new RenderBudgetExceededException(maxChars);
      }).toCompletableFuture().join();
    } catch (CompletionException e) {
      // Qute wraps every render-time failure (budget, interruption, a
      // TemplateException from a strict-rendering miss, a raw data-access
      // exception) the same way: as this CompletionException's cause.
      // Unwrap generically rather than special-casing types one by one --
      // whichever RuntimeException Qute actually threw is exactly what a
      // caller's own catch blocks are already written to distinguish.
      Throwable cause = e.getCause();
      // A failure that happens while resolving anything inside a
      // {#section} (e.g. {#for}), not a bare top-level {expr}, comes back
      // wrapped a SECOND time -- Qute's own SectionNode#resolve catches
      // any Throwable from its body and rewraps it as a TemplateException,
      // regardless of what actually threw it. Unwrap that one extra layer
      // too, but only for this module's own guard exceptions (budget,
      // interruption): a genuine TemplateException from Qute itself (a
      // strict-rendering miss, a real parse-time fault surfacing late)
      // must stay a TemplateException, not get stripped down to whatever
      // unrelated cause it happens to carry.
      if (cause instanceof TemplateException te && te.getCause() instanceof RenderException re)
        throw re;
      if (cause instanceof RuntimeException re)
        throw re;
      throw e;
    }
    return out.toString();
  }
}
