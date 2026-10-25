/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

/**
 * A template rendered against a submitted {@code Receipt} referenced data
 * the receipt doesn't have -- the caller's fault (a server exposing this as
 * a REST endpoint should map it to HTTP 422: either the wrong receipt was
 * submitted for this template, or the receipt is missing a chain/row the
 * template expects).
 */
public class TemplateDataMismatchException extends RuntimeException {

  public TemplateDataMismatchException(String templateName, long version, Throwable cause) {
    super("template '%s' v%d could not be rendered against the submitted receipt: %s"
        .formatted(templateName, version, cause.getMessage()), cause);
  }
}
