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

    ReceiptRenderSettings settings = new ReceiptRenderSettings().setHashEncoding(HashEncoding.HEX);
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

    for (HashEncoding enc : HashEncoding.values()) {
      ReceiptRenderSettings settings = new ReceiptRenderSettings().setHashEncoding(enc);
      Engine engine = QutePreprocesser.newEngine(settings);
      Template t = engine.parse("{cell.display}");
      assertEquals(expected, t.data("cell", cell).render());
    }
  }

  @Test
  public void testBytesCell_32Bytes_stillUsesStandardBase64_notHashEncoding() {
    // A BYTES cell that happens to be 32 bytes long must still go through
    // standard Base64, not HashEncoding -- it's the cell's declared type
    // (BYTES), not its length, that decides the encoding.
    byte[] data = new byte[32];
    for (int i = 0; i < data.length; i++) data[i] = (byte) (31 - i);
    Cell cell = new Cell.UnsaltedReveal(DataType.BYTES, ByteBuffer.wrap(data));

    ReceiptRenderSettings settings = new ReceiptRenderSettings().setHashEncoding(HashEncoding.BASE64_32);
    Engine engine = QutePreprocesser.newEngine(settings);
    Template t = engine.parse("{cell.display}");

    String rendered = t.data("cell", cell).render();
    assertEquals(Base64.getEncoder().encodeToString(data), rendered);
    assertNotEquals(HashEncoding.BASE64_32.encode(ByteBuffer.wrap(data)), rendered);
  }
}
