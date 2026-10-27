/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

/**
 * Rendering was aborted because the current thread was interrupted -- e.g.
 * a caller cancelled a {@code Future} running this render past its
 * deadline. Not a template or data fault; whoever interrupted the thread
 * decided to give up on this render for their own reasons.
 */
public class RenderInterruptedException extends RuntimeException {

  public RenderInterruptedException() {
    super("render aborted: thread interrupted");
  }
}
