/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

/**
 * A stored template's own content failed to parse as valid Qute syntax --
 * the template's fault, not the caller's (a server exposing this as a REST
 * endpoint should map it to HTTP 500: the publisher should have caught this
 * before publishing, since content is currently unvalidated at publish time).
 */
public class TemplateParseException extends RuntimeException {

  public TemplateParseException(String templateName, long version, Throwable cause) {
    super("failed to parse template '%s' v%d: %s"
        .formatted(templateName, version, cause.getMessage()), cause);
  }
}
