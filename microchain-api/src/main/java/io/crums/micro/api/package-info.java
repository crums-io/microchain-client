/**
 * REST API contract: entity types and JSON parsers for microchain endpoints.
 * Shared between microchain-cli (client) and microchain-webservices (server).
 *
 * <h2>Endpoint summary</h2>
 * <pre>
 *   GET  /{user}/{chain}/info       → {@link io.crums.micro.api.ChainInfo}
 *   GET  /{user}/{chain}/salts      → {@code List<}{@link io.crums.sldg.salt.EpochedTableSalt.EpochSeed}{@code >} (via {@link io.crums.sldg.src.json.EpochSeedParser})
 *   GET  /{user}/{chain}/sql-config → {@link io.crums.micro.api.SqlConfig}
 *   GET  /{user}/{chain}/state      → {@link io.crums.sldg.Path} (PathPack JSON)
 *   POST /{user}/{chain}/commit     → {@link io.crums.micro.api.CommitRequest} /
 *                                     {@link io.crums.micro.api.CommitResponse}
 * </pre>
 *
 * <h2>Library type mapping</h2>
 * <p>The {@code /state} endpoint returns a commitment path serialised as a
 * {@linkplain io.crums.sldg.json.PathPackParser PathPack} JSON object, whose
 * schema is defined in <em>skipledger-base</em> ({@code io.crums.sldg.base}).
 * Consumers should parse the response directly with
 * {@link io.crums.sldg.json.PathPackParser}; this module re-exports
 * {@code skipledger-base} transitively so no extra dependency is needed.</p>
 *
 * <p>The {@code /salts} response is a JSON array of epoch-seed objects. Parse it
 * with {@link io.crums.sldg.src.json.EpochSeedParser#PARSER} (returns
 * {@code List<EpochSeed>} via the inherited
 * {@link io.crums.util.json.JsonEntityReader#toEntityList toEntityList} method),
 * or use {@link io.crums.sldg.src.json.TableSaltReader#READER} to go directly
 * to a {@link io.crums.sldg.salt.TableSalt} in one step.</p>
 *
 * <p>Future: the {@code /verify} endpoint will return a
 * {@link io.crums.sldg.src.SourceRow} proof, whose parser lives in
 * <em>source-ledger</em> ({@code io.crums.sldg.src}).</p>
 */
package io.crums.micro.api;
