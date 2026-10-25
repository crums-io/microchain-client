/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.crums.micro.api.receipt.ChainReceipt;
import io.crums.micro.api.receipt.Receipt;
import io.crums.micro.api.render.engine.QutePreprocesser;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.SourceRowBuilder;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;

/**
 * Exercises {@link QutePreprocesser#newEngine()} against a real {@link
 * Receipt}, confirming templates can navigate the actual domain object
 * graph via reflection (no intermediate Map/List tree), and that {@code
 * cell.display} correctly normalizes redacted, DATE, and plain cells.
 */
public class ReceiptViewModelTest {

  private static final String CHAIN_URL = "https://micro.crums.io/bikeshop/orders";

  @Test
  public void testDirectReflectionAccess_noResolverConfigured_fails() {
    // Sanity check on the finding that motivated this whole design:
    // a bare Engine, without QutePreprocesser.configure, can't navigate
    // plain domain objects at all.
    Engine bare = Engine.builder().addDefaults().build();
    SourceRowBuilder builder = new SourceRowBuilder();
    SourceRow row = builder.buildRow(1L, "Acme Corp", 42L);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    Receipt receipt = new Receipt(List.of(cr));

    Template t = bare.parse("{receipt.chains.get(0).rows.get(0).cells.get(0)}");
    assertThrows(RuntimeException.class, () -> t.data("receipt", receipt).render());
  }

  @Test
  public void testConfiguredEngine_navigatesRealReceiptDirectly() {
    Engine engine = QutePreprocesser.newEngine();

    SourceRowBuilder builder = new SourceRowBuilder();
    Date orderDate = new Date(1_700_000_000_000L);
    SourceRow row = builder.buildRow(1L, "Acme Corp", orderDate, 42L);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    Receipt receipt = new Receipt(List.of(cr));
    ReceiptViewModel vm = new ReceiptViewModel(receipt);

    Template t = engine.parse(
        "{receipt.chains.get(0).rows.get(0).cells.get(0).display} / " +
        "{receipt.chains.get(0).rows.get(0).cells.get(1).display} / " +
        "{receipt.chains.get(0).rows.get(0).cells.get(2).display} / " +
        "{receipt.chains.get(0).isAnchored} / " +
        "{receipt.chains.get(0).chainUri}");

    String rendered = t.data("receipt", vm.receipt()).render();

    // DATE cells render via ReceiptRenderSettings' default DateFormat
    // (DateFormat.getDateInstance()), not a raw Instant -- compute the
    // expected value the same way rather than hardcoding a
    // locale-dependent literal.
    String expectedDate = java.text.DateFormat.getDateInstance().format(orderDate);
    assertEquals(
        "Acme Corp / " + expectedDate + " / 42 / false / " + CHAIN_URL,
        rendered);
  }

  @Test
  public void testConfiguredEngine_renderAsImplicitRoot_noPrefixNeeded() {
    Engine engine = QutePreprocesser.newEngine();

    SourceRowBuilder builder = new SourceRowBuilder();
    SourceRow row = builder.buildRow(1L, "Acme Corp", 42L);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    ReceiptViewModel vm = new ReceiptViewModel(new Receipt(List.of(cr)));

    Template t = engine.parse("{chains.get(0).rows.get(0).cells.get(0).display}");
    assertEquals("Acme Corp", t.render(vm.receipt()));
  }

  @Test
  public void testConfiguredEngine_redactedCell_showsSentinelNotException() {
    Engine engine = QutePreprocesser.newEngine();

    SourceRowBuilder builder = new SourceRowBuilder();
    SourceRow row = builder.buildRow(1L, "secret-name", 42L).redact(0);
    ChainReceipt cr = new ChainReceipt(CHAIN_URL, List.of(row), Optional.empty());
    ReceiptViewModel vm = new ReceiptViewModel(new Receipt(List.of(cr)));

    Template t = engine.parse(
        "{receipt.chains.get(0).rows.get(0).cells.get(0).display} / " +
        "{receipt.chains.get(0).rows.get(0).cells.get(1).display}");

    assertEquals("[redacted] / 42", t.data("receipt", vm.receipt()).render());
  }
}
