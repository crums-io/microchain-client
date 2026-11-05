/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

/**
 * Common base for every unchecked exception this module's rendering
 * pipeline throws. A caller that wants to handle a specific failure can
 * still catch that exact subtype, same as always; this just adds the
 * option to catch every rendering failure through one type, for a caller
 * that has no need to tell them apart, without reaching for the much
 * broader {@code RuntimeException}. Always one of the concrete subtypes;
 * never thrown bare.
 */
public abstract class RenderException extends RuntimeException {

  protected RenderException(String message) {
    super(message);
  }

  protected RenderException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Factory method support for idioms like
   * {@code throw makeException().fillInStackTrace()}
   */
  @Override
  public synchronized RenderException fillInStackTrace() {
    super.fillInStackTrace();
    return this;
  }

}
