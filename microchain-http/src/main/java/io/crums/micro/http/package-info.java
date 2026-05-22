/**
 * HTTP client library for microchain-webservices.
 *
 * <p>The central class is {@link io.crums.micro.http.RemoteSkipLedger}, which
 * implements {@link io.crums.sldg.SkipLedger} over the microchain REST API
 * using {@link java.net.http.HttpClient}.  A read-only view is available via
 * the {@code GET /{user}/{chain}/state} endpoint; combining it with
 * {@code POST /{user}/{chain}/commit} yields the full read-write implementation.
 * </p>
 */
package io.crums.micro.http;
