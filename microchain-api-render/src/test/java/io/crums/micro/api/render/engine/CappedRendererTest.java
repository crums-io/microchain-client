/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.crums.micro.api.render.RenderBudgetExceededException;
import io.crums.micro.api.render.ReceiptRenderSettings;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;

public class CappedRendererTest {

  // No Receipt/ChainReceipt graph is relevant to anything tested here
  // (char-budget capping, strict-rendering exceptions, {#eval} exclusion)
  // -- this is the module's own package-private QutePreprocesser.newEngine
  // (ReceiptRenderSettings) overload, wrapped locally since it's not part
  // of the public API.
  private static Engine newEngine() {
    return QutePreprocesser.newEngine(new ReceiptRenderSettings());
  }

  @Test
  public void testUnderBudget_rendersNormally() {
    Engine engine = newEngine();
    Template t = engine.parse("hello {name}");
    String rendered = CappedRenderer.render(t.data("name", "world"), 100);
    assertEquals("hello world", rendered);
  }

  @Test
  public void testOverBudget_abortsMidRender() {
    Engine engine = newEngine();
    // A data-driven loop whose body emits real output, well beyond any
    // plausible receipt-sized budget -- confirms the cap trips during
    // rendering, not only after a huge buffer has already been built.
    java.util.List<String> items = new java.util.ArrayList<>();
    for (int i = 0; i < 100_000; i++) items.add("xxxxxxxxxx");
    Template t = engine.parse("{#for i in items}{i}{/for}");
    assertThrows(RenderBudgetExceededException.class,
        () -> CappedRenderer.render(t.data("items", items), 1000));
  }

  @Test
  public void testStrictRenderingMiss_unwrapsTemplateException_notCompletionException() {
    // strictRendering(true) raises a TemplateException mid-render (inside
    // TemplateInstance#consume), not at parse time -- confirms it comes
    // back as that real type, not wrapped in a CompletionException.
    Engine engine = newEngine();
    Template t = engine.parse("{name.nonExistentProperty}");
    assertThrows(io.quarkus.qute.TemplateException.class,
        () -> CappedRenderer.render(t.data("name", "world"), 100));
  }

  @Test
  public void testEvalSectionIsNotRegistered() {
    // {#eval} is deliberately omitted from QutePreprocesser's engine --
    // parsing a template that uses it should fail rather than silently
    // evaluate a runtime string as further template source.
    Engine engine = newEngine();
    assertThrows(RuntimeException.class, () -> engine.parse("{#eval someExpr/}"));
  }
}
