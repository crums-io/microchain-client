/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.receipt;

import static io.crums.micro.api.ApiConstants.*;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.crums.sldg.Path;
import io.crums.sldg.PathPack;
import io.crums.sldg.src.SourceRow;
import io.crums.util.json.JsonEntityParser;
import io.crums.util.json.JsonParsingException;
import io.crums.util.json.JsonUtils;
import io.crums.util.json.simple.JSONObject;

/**
 * One chain's contribution to a {@link Receipt}: a {@link SourceReceipt}
 * (revealed rows, optionally anchored) tied to a specific chain's
 * identifying {@link #chainUri()}.
 *
 * <p>{@link #chainUri()} is <em>any</em> URI, not necessarily one that can
 * be dereferenced over HTTP: a chain registered at a microchain web
 * service has an {@code https://} identifier, but a ledger maintained
 * informally (outside any such service) can use any other URI scheme as a
 * stable identifier. {@link #chainUrl()} is the narrower, derived view --
 * present only when {@link #chainUri()} is actually an {@code http}/
 * {@code https} URL.</p>
 *
 * <p>{@link #rows()} is always non-empty.</p>
 *
 * @see SourceReceipt for the draft/anchored distinction and the
 *      hash-verification rules enforced at construction
 */
public class ChainReceipt extends SourceReceipt {

  public static final Parser PARSER = new Parser();

  private final URI chainUri;

  /**
   * @throws IllegalArgumentException if {@code rows} is empty, or not
   *         strictly ascending by row number with no duplicates
   * @throws io.crums.sldg.HashConflictException if {@code state} is present
   *         and its input hash at some row number in {@code rows} disagrees
   *         with that row's own {@link SourceRow#hash()}
   */
  public ChainReceipt(URI chainUri, List<SourceRow> rows, Optional<PathPack> state) {
    super(rows, state);
    this.chainUri = Objects.requireNonNull(chainUri, "chainUri");
  }

  /**
   * @throws IllegalArgumentException if {@code chainUri} is not a valid URI,
   *         or any of {@link #ChainReceipt(URI, List, Optional)}'s own
   *         conditions
   */
  public ChainReceipt(String chainUri, List<SourceRow> rows, Optional<PathPack> state) {
    this(URI.create(chainUri), rows, state);
  }

  /** This chain's identifier. Need not be dereferenceable -- see {@link #chainUrl()}. */
  public URI chainUri() {
    return chainUri;
  }

  /**
   * This chain's identifier as a {@code URL}, iff {@link #chainUri()} is an
   * {@code http}/{@code https} URI (the scheme a microchain web service
   * actually speaks). Empty for any other scheme -- including schemes
   * {@link URI#toURL()} would otherwise happily convert (e.g. {@code
   * file:}), since nothing in this codebase can dereference those either.
   */
  public Optional<URL> chainUrl() {
    String scheme = chainUri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
      return Optional.empty();
    try {
      return Optional.of(chainUri.toURL());
    } catch (MalformedURLException e) {
      return Optional.empty();
    }
  }

  /** Returns a copy with {@code path} attached as this entry's {@link #state()}. */
  public ChainReceipt withState(Path path) {
    return new ChainReceipt(chainUri, rows(), Optional.of(PathPack.forPath(path)));
  }


  // ── JSON parser ───────────────────────────────────────────────────────────

  public static class Parser implements JsonEntityParser<ChainReceipt> {

    @Override
    public JSONObject injectEntity(ChainReceipt cr, JSONObject jObj) {
      jObj.put(CHAIN_URI_KEY, cr.chainUri().toString());
      return SourceReceipt.PARSER.injectEntity(cr, jObj);
    }

    @Override
    public ChainReceipt toEntity(JSONObject jObj) throws JsonParsingException {
      String chainUriStr = JsonUtils.getString(jObj, CHAIN_URI_KEY, true);
      URI chainUri;
      try {
        chainUri = URI.create(chainUriStr);
      } catch (IllegalArgumentException e) {
        throw new JsonParsingException(
            "malformed " + CHAIN_URI_KEY + ": " + chainUriStr, e);
      }
      List<SourceRow> rows = SourceReceipt.Parser.parseRows(jObj);
      Optional<PathPack> state = SourceReceipt.Parser.parseState(jObj);
      return new ChainReceipt(chainUri, rows, state);
    }
  }
}
