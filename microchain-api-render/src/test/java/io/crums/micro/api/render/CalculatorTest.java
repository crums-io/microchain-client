/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

public class CalculatorTest {

  @Test
  public void testEnterTimes_computesLineSubtotal() {
    Calculator calc = new Calculator();
    calc.enter(449L);      // Long, as cell.display gives for a whole-number price
    calc.times(new BigDecimal("1"));
    assertEquals("449", calc.display());
  }

  @Test
  public void testMixedLongAndBigDecimal_coercedUniformly() {
    // The exact real-data case that broke pure Qute: list_price sometimes
    // LONG (1549), sometimes BIG_DEC (599.99), depending on the row.
    Calculator calc = new Calculator();
    calc.enter(1549L);
    calc.times(2L);
    assertEquals("3098", calc.display());

    calc.enter(new BigDecimal("599.99"));
    calc.times(2L);
    assertEquals("1199.98", calc.display());
  }

  @Test
  public void testMPlus_accumulatesAcrossRows_noArgument() {
    Calculator calc = new Calculator();

    calc.enter(449L);
    calc.times(1L);
    calc.mPlus();

    calc.enter(new BigDecimal("1799.99"));
    calc.times(2L);
    calc.mPlus();

    calc.mRecall();
    assertEquals("4048.98", calc.display());
  }

  @Test
  public void testMMinus_subtractsCurrentDisplayFromMemory() {
    Calculator calc = new Calculator();
    calc.enter(100L);
    calc.mPlus();      // memory = 100
    calc.enter(30L);
    calc.mMinus();     // memory = 70
    calc.mRecall();
    assertEquals("70", calc.display());
  }

  @Test
  public void testMClear_resetsMemoryOnly_displayUntouched() {
    Calculator calc = new Calculator();
    calc.enter(42L);
    calc.mPlus();
    calc.mClear();
    assertEquals("42", calc.display()); // display survives mClear()
    calc.mRecall();
    assertEquals("0", calc.display()); // memory was actually cleared
  }

  @Test
  public void testClear_resetsDisplayOnly_memoryUntouched() {
    Calculator calc = new Calculator();
    calc.enter(42L);
    calc.mPlus();
    calc.clear();
    assertEquals("0", calc.display());
    calc.mRecall();
    assertEquals("42", calc.display()); // memory survives clear()
  }

  @Test
  public void testMinusAndDividedBy() {
    Calculator calc = new Calculator();
    calc.enter(10L);
    calc.minus(4L);
    assertEquals("6", calc.display());
    calc.dividedBy(new BigDecimal("3"));
    // MathContext.DECIMAL64 -- non-terminating quotient tolerated, not thrown
    assertTrue(calc.display().startsWith("2"));
  }

  @Test
  public void testToString_sameAsDisplay() {
    Calculator calc = new Calculator();
    calc.enter(7L);
    assertEquals(calc.display(), calc.toString());
  }

  @Test
  public void testEveryMutator_returnsEmptyString_notVoidNotThis() {
    Calculator calc = new Calculator();
    assertEquals("", calc.clear());
    assertEquals("", calc.clearAll());
    assertEquals("", calc.enter(1L));
    assertEquals("", calc.plus(1L));
    assertEquals("", calc.minus(1L));
    assertEquals("", calc.times(1L));
    assertEquals("", calc.dividedBy(1L));
    assertEquals("", calc.mPlus());
    assertEquals("", calc.mMinus());
    assertEquals("", calc.mClear());
    assertEquals("", calc.mRecall());
  }

  @Test
  public void testCoercion_bigIntegerAndNumericString() {
    Calculator calc = new Calculator();
    calc.enter(BigInteger.valueOf(5));
    calc.plus("2.5");
    assertEquals("7.5", calc.display());
  }

  @Test
  public void testCoercion_rejectsNonNumeric() {
    Calculator calc = new Calculator();
    assertThrows(IllegalArgumentException.class, () -> calc.enter(new Object()));
  }

  @Test
  public void testZeroArgMemoryMethods_areSlot0() {
    Calculator calc = new Calculator();
    calc.enter(5L);
    calc.mPlus();        // slot 0
    calc.enter(3L);
    calc.mPlus(0);       // explicit slot 0 -- same slot as the zero-arg call above
    calc.mRecall();
    assertEquals("8", calc.display());
  }

  @Test
  public void testIndexedMemorySlots_areIndependent() {
    Calculator calc = new Calculator();
    calc.enter(10L);
    calc.mPlus(1);
    calc.enter(20L);
    calc.mPlus(2);

    calc.mRecall(1);
    assertEquals("10", calc.display());
    calc.mRecall(2);
    assertEquals("20", calc.display());
    calc.mRecall(0);
    assertEquals("0", calc.display()); // untouched slot 0
  }

  @Test
  public void testIndexedMMinusAndMClear() {
    Calculator calc = new Calculator();
    calc.enter(100L);
    calc.mPlus(5);       // slot 5 = 100
    calc.enter(30L);
    calc.mMinus(5);      // slot 5 = 70
    calc.mRecall(5);
    assertEquals("70", calc.display());

    calc.mClear(5);
    calc.mRecall(5);
    assertEquals("0", calc.display());
  }

  @Test
  public void testMemorySlots_outOfRange_throws() {
    Calculator calc = new Calculator();
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> calc.mPlus(Calculator.MEMORY_SLOTS));
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> calc.mRecall(-1));
  }

  @Test
  public void testMemorySlots_constantIsTen() {
    assertEquals(10, Calculator.MEMORY_SLOTS);
  }

  @Test
  public void testClearAll_resetsDisplayAndEveryMemorySlot() {
    Calculator calc = new Calculator();
    calc.enter(42L);
    calc.mPlus(0);
    calc.enter(7L);
    calc.mPlus(3);
    calc.enter(99L);

    calc.clearAll();
    assertEquals("0", calc.display());
    calc.mRecall(0);
    assertEquals("0", calc.display());
    calc.mRecall(3);
    assertEquals("0", calc.display());
  }
}
