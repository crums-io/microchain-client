/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.io.IOException;

/**
 * Wraps a checked {@link IOException} from the PDF-generation pipeline
 * (HTML layout/rasterization, or writing the final document) as an
 * unchecked exception -- not a caller or template fault; an operational
 * failure in the rendering machinery itself (e.g. a malformed embedded
 * image, or a failure writing the output).
 */
public class RenderIOException extends RenderException {

  public RenderIOException(IOException cause) {
    super("PDF generation failed: " + cause.getMessage(), cause);
  }
}
