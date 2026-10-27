/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render.engine;

import java.util.concurrent.CompletionException;

import io.crums.micro.api.render.RenderBudgetExceededException;
import io.quarkus.qute.TemplateInstance;

/**
 * Renders a {@link TemplateInstance} through its real primitive, {@link
 * TemplateInstance#consume}, instead of the more familiar {@code render()}
 * -- not for convenience, but because {@code consume} pushes each resolved
 * text fragment to its callback as it's produced, incrementally, while the
 * template is still being walked (verified against qute-core 3.17.6:
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
 * (see {@code TemplateShapeGuard}) -- not here.</p>
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
      if (e.getCause() instanceof RuntimeException re)
        throw re;
      throw e;
    }
    return out.toString();
  }
}
