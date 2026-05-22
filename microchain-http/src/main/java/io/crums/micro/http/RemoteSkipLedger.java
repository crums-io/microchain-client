/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.http;

import java.net.URI;
import java.net.http.HttpClient;

import io.crums.sldg.SkipLedger;

/**
 * HTTP-backed {@link SkipLedger} over the microchain REST API.
 *
 * <p>Read path: {@code GET /{user}/{chain}/state} (public, no token required).
 * Write path: {@code POST /{user}/{chain}/commit} (requires Bearer access token).
 * </p>
 *
 * <p>To be implemented.</p>
 */
public class RemoteSkipLedger extends SkipLedger {

  private final HttpClient http;
  private final URI base;
  private final String username;
  private final String chain;

  /**
   * @param base      server base URI, e.g. {@code https://mc.example.com}
   * @param username  chain owner's username
   * @param chain     chain name
   * @param http      shared {@link HttpClient} instance
   */
  public RemoteSkipLedger(URI base, String username, String chain, HttpClient http) {
    this.base     = base;
    this.username = username;
    this.chain    = chain;
    this.http     = http;
  }

  @Override public String hashAlgo()  { return io.crums.sldg.SldgConstants.DIGEST.hashAlgo(); }
  @Override public int    hashWidth() { return io.crums.sldg.SldgConstants.DIGEST.hashWidth(); }

  // SkipLedger implementation — to be filled in

  @Override public long size() { throw new UnsupportedOperationException("TODO"); }
  @Override public io.crums.sldg.Row getRow(long rowNumber) { throw new UnsupportedOperationException("TODO"); }
  @Override public java.nio.ByteBuffer rowHash(long rowNumber) { throw new UnsupportedOperationException("TODO"); }
  @Override protected void writeRowsImpl(long startRn, java.nio.ByteBuffer inputHashes) { throw new UnsupportedOperationException("TODO"); }
  @Override public void close() { }

}
