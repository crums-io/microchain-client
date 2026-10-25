/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class TemplateShapeGuardTest {

  @Test
  public void testOrdinaryTemplate_passes() {
    assertDoesNotThrow(() -> TemplateShapeGuard.validate(
        "{#for row in rows}{#for cell in row.cells}{cell.display}{/for}{/for}"));
  }

  @Test
  public void testLiteralRangeWithinLimit_passes() {
    assertDoesNotThrow(() -> TemplateShapeGuard.validate("{#for i in 1..12}{i}{/for}"));
  }

  @Test
  public void testOversizedLiteralRange_rejected() {
    assertThrows(IllegalArgumentException.class,
        () -> TemplateShapeGuard.validate("{#for i in 1..2000000000}{/for}"));
  }

  @Test
  public void testOversizedLiteralRange_withInternalWhitespace_stillRejected() {
    // Qute's own parser tokenizes this differently from the no-space form
    // (verified against qute-core 3.17.6) -- confirms the textual check
    // isn't fooled by the one spacing variant that defeats a naive,
    // single-parameter AST lookup.
    assertThrows(IllegalArgumentException.class,
        () -> TemplateShapeGuard.validate("{#for i in 1 .. 2000000000}{/for}"));
  }

  @Test
  public void testRangeEndpointTooLargeToParse_rejected() {
    assertThrows(IllegalArgumentException.class,
        () -> TemplateShapeGuard.validate("{#for i in 1..99999999999999999999}{/for}"));
  }

  @Test
  public void testExcessiveLoopNesting_rejected() {
    String content = "{#for a in rows}{#for b in rows}{#for c in rows}" +
        "{#for d in rows}{#for e in rows}{#for f in rows}{#for g in rows}" +
        "{/for}{/for}{/for}{/for}{/for}{/for}{/for}";
    assertThrows(IllegalArgumentException.class, () -> TemplateShapeGuard.validate(content));
  }

  @Test
  public void testTwoLevelNesting_withinDefaultLimit_passes() {
    assertDoesNotThrow(() -> TemplateShapeGuard.validate(
        "{#for row in rows}{#for cell in row.cells}{cell}{/for}{/for}"));
  }
}
