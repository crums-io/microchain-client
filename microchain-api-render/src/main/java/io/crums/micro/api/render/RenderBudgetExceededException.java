/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

/**
 * A template's rendered output exceeded the configured size budget --
 * almost certainly a large/nested loop (possibly driven by an oversized
 * submitted {@code Receipt}), rather than anything wrong with the
 * template's syntax. Not the server's fault (maps to HTTP 422 in a server
 * exposing this as a REST endpoint, not 500): the template parsed fine,
 * rendering it against this particular input is just too expensive to
 * allow.
 */
public class RenderBudgetExceededException extends RuntimeException {

  public RenderBudgetExceededException(int maxChars) {
    super("render output exceeded the %,d-character budget".formatted(maxChars));
  }

  public RenderBudgetExceededException(String templateName, long version, int maxChars) {
    super("template '%s' v%d exceeded the %,d-character render output budget"
        .formatted(templateName, version, maxChars));
  }
}
