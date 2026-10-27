/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

/**
 * A render touched more {@code Cell}s than the configured access budget --
 * the backstop for a loop whose body never emits any output (so {@code
 * engine.CappedRenderer}'s char budget never trips) but still iterates
 * without bound, e.g. an empty-bodied {@code {#for}} walking a huge
 * submitted {@code Receipt}. Not the server's fault (maps to HTTP 422 in a
 * server exposing this as a REST endpoint, not 500): the template and data
 * both parsed/validated fine, this input is just too large to allow.
 */
public class RenderAccessBudgetExceededException extends RuntimeException {

  public RenderAccessBudgetExceededException(long maxAccesses) {
    super("render touched more than %,d cells, exceeding the access budget".formatted(maxAccesses));
  }

  public RenderAccessBudgetExceededException(String templateName, long version, long maxAccesses) {
    super("template '%s' v%d touched more than %,d cells, exceeding the access budget"
        .formatted(templateName, version, maxAccesses));
  }
}
