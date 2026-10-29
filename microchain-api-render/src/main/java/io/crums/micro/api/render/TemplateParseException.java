/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

/**
 * A stored template's own content failed to parse as valid Qute syntax --
 * the template's fault, not the caller's (a server exposing this as a REST
 * endpoint should map it to HTTP 500). A published template's content is
 * checked for exactly this as part of its mandatory publish-time test
 * render, so this should be rare in practice; its appearance now means
 * something about the stored content changed independent of what passed
 * at publish time, not that publishing skipped validation.
 */
public class TemplateParseException extends RuntimeException {

  public TemplateParseException(String templateName, long version, Throwable cause) {
    super("failed to parse template '%s' v%d: %s"
        .formatted(templateName, version, cause.getMessage()), cause);
  }
}
