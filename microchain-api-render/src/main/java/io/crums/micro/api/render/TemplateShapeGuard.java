/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects template source whose shape alone -- independent of whatever
 * {@code Receipt} it's later rendered against -- can blow up rendering
 * cost: an oversized literal {@code {#for}}/{@code {#each}} numeric
 * range, or excessive loop nesting. Neither is a Qute syntax error (both
 * parse fine), and neither depends on submitted data, so a runtime
 * output-size budget (see {@code engine.CappedRenderer}) can't catch them
 * either -- an empty-bodied {@code {#for i in 1..2000000000}} emits
 * nothing to measure, however long it spins.
 *
 * <p>Deliberately textual, not AST-based. A literal range written with
 * internal whitespace (e.g. {@code 1 .. 1000000}) is tokenized by Qute's
 * own parser into several separate positional parameters rather than one
 * contiguous value (verified against qute-core 3.17.6) -- a check against
 * the parsed section's parameter map would silently miss that form. A
 * plain regex over the raw source handles every whitespace variant
 * uniformly, at the cost of also flagging a coincidental {@code 1..5} in
 * ordinary prose. That asymmetry is deliberate: a false positive here is
 * merely an annoying rejection of an unusual template; a false negative
 * is the one thing this class must never produce, so staying textual and
 * conservative beats a structurally "correct" check an attacker can
 * out-tokenize.</p>
 */
public final class TemplateShapeGuard {

  /** Generous for any real receipt template -- a receipt has rows and cells, not six-figure index ranges. */
  public static final long DEFAULT_MAX_RANGE_SPAN = 100_000L;

  /** A receipt template has at most two natural nesting levels (rows, then cells); this leaves ample headroom. */
  public static final int DEFAULT_MAX_LOOP_NESTING = 6;

  private static final Pattern LOOP_OPEN = Pattern.compile("\\{#(?:for|each)\\b");
  private static final Pattern LOOP_CLOSE = Pattern.compile("\\{/(?:for|each)\\}");
  private static final Pattern LITERAL_RANGE = Pattern.compile("(\\d+)\\s*\\.\\.\\s*(\\d+)");

  private TemplateShapeGuard() {
  }

  /** @throws IllegalArgumentException if {@code content}'s shape exceeds the default limits */
  public static void validate(String content) {
    validate(content, DEFAULT_MAX_RANGE_SPAN, DEFAULT_MAX_LOOP_NESTING);
  }

  /** @throws IllegalArgumentException if {@code content}'s shape exceeds the given limits */
  public static void validate(String content, long maxRangeSpan, int maxLoopNesting) {
    checkLiteralRanges(content, maxRangeSpan);
    checkLoopNesting(content, maxLoopNesting);
  }

  private static void checkLiteralRanges(String content, long maxRangeSpan) {
    Matcher m = LITERAL_RANGE.matcher(content);
    while (m.find()) {
      long a, b;
      try {
        a = Long.parseLong(m.group(1));
        b = Long.parseLong(m.group(2));
      } catch (NumberFormatException e) {
        // Doesn't even fit in a long -- certainly too large to allow.
        throw new IllegalArgumentException("literal range endpoint too large: '" + m.group() + "'");
      }
      long span = Math.abs(b - a);
      if (span > maxRangeSpan)
        throw new IllegalArgumentException(
            "literal range '%s' spans %,d, exceeding the %,d limit"
                .formatted(m.group(), span, maxRangeSpan));
    }
  }

  private static void checkLoopNesting(String content, int maxLoopNesting) {
    record Event(int pos, int delta) { }

    List<Event> events = new ArrayList<>();
    Matcher open = LOOP_OPEN.matcher(content);
    while (open.find())
      events.add(new Event(open.start(), 1));
    Matcher close = LOOP_CLOSE.matcher(content);
    while (close.find())
      events.add(new Event(close.start(), -1));
    events.sort(Comparator.comparingInt(Event::pos));

    int depth = 0, maxDepth = 0;
    for (Event e : events) {
      depth += e.delta();
      if (depth < 0)
        depth = 0; // an unmatched close tag -- ignore rather than go negative
      if (depth > maxDepth)
        maxDepth = depth;
    }
    if (maxDepth > maxLoopNesting)
      throw new IllegalArgumentException(
          "loop nesting depth %d exceeds the %d limit".formatted(maxDepth, maxLoopNesting));
  }
}
