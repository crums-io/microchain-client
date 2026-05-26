/**
 * HTTP client library for microchain-webservices.
 *
 * <p>{@link io.crums.micro.http.ChainClient} is the central class: it
 * centralises all {@code /{user}/{chain}/} REST calls so that the CLI and
 * future rich clients share the same networking layer.</p>
 *
 * <p>Currently implemented endpoints:</p>
 * <ul>
 *   <li>{@code GET /{user}/{chain}/state} —
 *       {@link io.crums.micro.http.ChainClient#getState()} /
 *       {@link io.crums.micro.http.ChainClient#getState(java.util.List, boolean, boolean)}
 *       → {@link io.crums.sldg.Path}</li>
 * </ul>
 *
 * <p>Planned: {@code /info}, {@code /salts}, {@code /sql-config}, {@code /commit}.
 * {@link io.crums.micro.http.RemoteSkipLedger} will wrap {@code ChainClient}
 * once the write path is ready.</p>
 */
package io.crums.micro.http;
