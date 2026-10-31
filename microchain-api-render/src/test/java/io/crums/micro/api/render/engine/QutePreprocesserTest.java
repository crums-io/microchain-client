/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.crums.micro.api.render.ReceiptRenderSettings;
import io.crums.sldg.json.HashEncoding;
import io.crums.sldg.src.Cell;
import io.crums.sldg.src.DataType;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;

public class QutePreprocesserTest {

  @Test
  public void testHashCell_usesConfiguredHashEncoding() {
    byte[] data = new byte[32];
    for (int i = 0; i < data.length; i++) data[i] = (byte) i;
    Cell cell = new Cell.UnsaltedReveal(DataType.HASH, ByteBuffer.wrap(data));

    ReceiptRenderSettings settings = new ReceiptRenderSettings();
    settings.setHashEncodingToHex();
    Engine engine = QutePreprocesser.newEngine(settings);
    Template t = engine.parse("{cell.display}");
    assertEquals(HashEncoding.HEX.encode(ByteBuffer.wrap(data)), t.data("cell", cell).render());
  }

  @Test
  public void testBytesCell_nonHashLength_rendersAsStandardBase64_underAnyHashEncoding() {
    // Not 32 bytes -- the exact case that used to throw when hashEncoding
    // was set to BASE64_32 (Base64_32.encode requires exactly 32 bytes),
    // and silently used the wrong (hash) alphabet even under the default
    // HEX setting.
    byte[] data = { 1, 2, 3, 4, 5, 6, 7 };
    Cell cell = new Cell.UnsaltedReveal(DataType.BYTES, ByteBuffer.wrap(data));
    String expected = Base64.getEncoder().encodeToString(data);

    ReceiptRenderSettings hexSettings = new ReceiptRenderSettings();
    hexSettings.setHashEncodingToHex();
    Engine hexEngine = QutePreprocesser.newEngine(hexSettings);
    assertEquals(expected, hexEngine.parse("{cell.display}").data("cell", cell).render());

    ReceiptRenderSettings b64Settings = new ReceiptRenderSettings();
    b64Settings.setHashEncodingToB64();
    Engine b64Engine = QutePreprocesser.newEngine(b64Settings);
    assertEquals(expected, b64Engine.parse("{cell.display}").data("cell", cell).render());
  }

  @Test
  public void testBytesCell_32Bytes_stillUsesStandardBase64_notHashEncoding() {
    // A BYTES cell that happens to be 32 bytes long must still go through
    // standard Base64, not HashEncoding -- it's the cell's declared type
    // (BYTES), not its length, that decides the encoding.
    byte[] data = new byte[32];
    for (int i = 0; i < data.length; i++) data[i] = (byte) (31 - i);
    Cell cell = new Cell.UnsaltedReveal(DataType.BYTES, ByteBuffer.wrap(data));

    ReceiptRenderSettings settings = new ReceiptRenderSettings();
    settings.setHashEncodingToB64();
    Engine engine = QutePreprocesser.newEngine(settings);
    Template t = engine.parse("{cell.display}");

    String rendered = t.data("cell", cell).render();
    assertEquals(Base64.getEncoder().encodeToString(data), rendered);
    assertNotEquals(HashEncoding.BASE64_32.encode(ByteBuffer.wrap(data)), rendered);
  }

  @Test
  public void testSettingsSetterCallableFromTemplate_mutatesLiveSettings_noStrayOutput() {
    // The whole point of returning "" (not void, not this): the setter must
    // actually be invocable from the template (requires non-void), and
    // calling it as a bare statement must not leak anything into the
    // rendered output -- no {#if}/{#let} wrapper needed, since the return
    // value itself renders as nothing.
    Cell cell = new Cell.UnsaltedReveal(DataType.HASH, ByteBuffer.wrap(new byte[32]));

    ReceiptRenderSettings settings = new ReceiptRenderSettings(); // starts HEX (default)
    Engine engine = QutePreprocesser.newEngine(settings);
    Template t = engine.parse(
        "{cell.display}/{settings.setHashEncodingToB64()}{cell.display}");

    String expectedHex = HashEncoding.HEX.encode(ByteBuffer.wrap(new byte[32]));
    String expectedB64 = HashEncoding.BASE64_32.encode(ByteBuffer.wrap(new byte[32]));

    // Both cell.display calls, across the one render, close over the SAME
    // settings instance -- the second one must reflect the mutation the
    // template itself made between the two calls, not a stale snapshot.
    assertEquals(
        expectedHex + "/" + expectedB64,
        t.data("cell", cell, "settings", settings).render());
  }

  @Test
  public void testMismatchedSettingsInstances_scriptMutationHasNoEffect() {
    // Regression guard for the exact bug ReceiptRenderService#parseAndRender
    // used to have: an Engine built against one ReceiptRenderSettings
    // instance, with a DIFFERENT instance exposed as "settings" template
    // data. cell.display's resolver closes over whichever instance built
    // the engine -- so mutating the other one through a script-authored
    // setter call is silently a no-op for cell.display, even though the
    // setter itself runs fine and {settings...} reads reflect the change.
    Cell cell = new Cell.UnsaltedReveal(DataType.HASH, ByteBuffer.wrap(new byte[32]));

    ReceiptRenderSettings engineSettings = new ReceiptRenderSettings(); // closed over below
    ReceiptRenderSettings dataSettings = new ReceiptRenderSettings();  // a DIFFERENT instance
    Engine engine = QutePreprocesser.newEngine(engineSettings);
    Template t = engine.parse(
        "{cell.display}/{settings.setHashEncodingToB64()}{cell.display}/{settings.hashEncoding}");

    String expectedHex = HashEncoding.HEX.encode(ByteBuffer.wrap(new byte[32]));
    // Both cell.display calls stay HEX (engineSettings was never touched),
    // even though the final {settings.hashEncoding} read confirms
    // dataSettings itself really was mutated to BASE64_32.
    assertEquals(
        expectedHex + "/" + expectedHex + "/" + HashEncoding.BASE64_32,
        t.data("cell", cell, "settings", dataSettings).render());
  }
}
