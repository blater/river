package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class ExactDecimalTest {
  @Test
  void widensAndComparesWithoutIntermediateOverflow() {
    ExactDecimal.LongValue result = new ExactDecimal.LongValue();
    result.value = 71;
    assertTrue(ExactDecimal.widenScale(
        123,
        SqlTypeDescriptor.decimal(3, 1),
        SqlTypeDescriptor.decimal(6, 3),
        result));
    assertEquals(12_300, result.value);

    result.value = 71;
    assertFalse(ExactDecimal.widenScale(
        999,
        SqlTypeDescriptor.decimal(3, 0),
        SqlTypeDescriptor.decimal(3, 2),
        result));
    assertEquals(71, result.value);

    assertEquals(0, ExactDecimal.compare(
        1,
        SqlTypeDescriptor.BIGINT,
        100_000_000_000_000_000L,
        SqlTypeDescriptor.decimal(18, 17)));
    assertTrue(ExactDecimal.compare(
        -1,
        SqlTypeDescriptor.BIGINT,
        -100_000_000_000_000_001L,
        SqlTypeDescriptor.decimal(18, 17)) > 0);
    assertTrue(ExactDecimal.compare(
        12,
        SqlTypeDescriptor.decimal(2, 1),
        119,
        SqlTypeDescriptor.decimal(3, 2)) > 0);
  }

  @Test
  void averagesSignedWideSumsWithHalfEvenRounding() {
    ExactDecimal.LongValue result = new ExactDecimal.LongValue();
    ExactDecimal.WideScratch scratch = new ExactDecimal.WideScratch();
    long low = 0;
    long high = 0;
    long value = 999_999_999_999_999_999L;
    for (int index = 0; index < 10; index++) {
      long previous = low;
      low += value;
      high += Long.compareUnsigned(low, previous) < 0 ? 1 : 0;
    }
    assertTrue(ExactDecimal.average(
        high,
        low,
        10,
        0,
        SqlTypeDescriptor.decimal(18, 0),
        result,
        scratch));
    assertEquals(value, result.value);

    assertTrue(ExactDecimal.average(
        0, 3, 2, 0, SqlTypeDescriptor.decimal(18, 0), result, scratch));
    assertEquals(2, result.value);
    assertTrue(ExactDecimal.average(
        -1, -3, 2, 0, SqlTypeDescriptor.decimal(18, 0), result, scratch));
    assertEquals(-2, result.value);
    assertTrue(ExactDecimal.average(
        0, 1, 2, 0, SqlTypeDescriptor.decimal(18, 0), result, scratch));
    assertEquals(0, result.value);
  }

  @Test
  void derivesDescriptorsAndEvaluatesCheckedArithmetic() {
    int left = SqlTypeDescriptor.decimal(3, 2);
    int right = SqlTypeDescriptor.decimal(4, 3);
    assertEquals(
        SqlTypeDescriptor.decimal(5, 3),
        ExactDecimal.addResultDescriptor(left, right));
    assertEquals(
        SqlTypeDescriptor.decimal(7, 5),
        ExactDecimal.multiplyResultDescriptor(left, right));
    assertEquals(
        SqlTypeDescriptor.decimal(11, 7),
        ExactDecimal.divideResultDescriptor(left, right));
    assertEquals(
        SqlTypeDescriptor.decimal(4, 3),
        ExactDecimal.remainderResultDescriptor(left, right));

    ExactDecimal.LongValue result = new ExactDecimal.LongValue();
    ExactDecimal.WideScratch scratch = new ExactDecimal.WideScratch();
    int add = ExactDecimal.addResultDescriptor(
        SqlTypeDescriptor.decimal(3, 2),
        SqlTypeDescriptor.decimal(4, 3));
    assertEquals(StatusCode.OK, ExactDecimal.add(
        120,
        SqlTypeDescriptor.decimal(3, 2),
        2_345,
        SqlTypeDescriptor.decimal(4, 3),
        false,
        add,
        result,
        scratch));
    assertEquals(3_545, result.value);

    int product = ExactDecimal.multiplyResultDescriptor(
        SqlTypeDescriptor.decimal(3, 1),
        SqlTypeDescriptor.decimal(3, 2));
    assertEquals(StatusCode.OK, ExactDecimal.multiply(
        125,
        SqlTypeDescriptor.decimal(3, 1),
        200,
        SqlTypeDescriptor.decimal(3, 2),
        product,
        result,
        scratch));
    assertEquals(25_000, result.value);

    int quotient = ExactDecimal.divideResultDescriptor(
        SqlTypeDescriptor.decimal(3, 2),
        SqlTypeDescriptor.decimal(2, 1));
    assertEquals(StatusCode.OK, ExactDecimal.divide(
        100,
        SqlTypeDescriptor.decimal(3, 2),
        80,
        SqlTypeDescriptor.decimal(2, 1),
        quotient,
        result,
        scratch));
    assertEquals(125_000, result.value);

    int remainder = ExactDecimal.remainderResultDescriptor(
        SqlTypeDescriptor.decimal(3, 2),
        SqlTypeDescriptor.decimal(2, 1));
    assertEquals(StatusCode.OK, ExactDecimal.remainder(
        550,
        SqlTypeDescriptor.decimal(3, 2),
        20,
        SqlTypeDescriptor.decimal(2, 1),
        remainder,
        result,
        scratch));
    assertEquals(150, result.value);

    assertEquals(StatusCode.DIVISION_BY_ZERO, ExactDecimal.divide(
        1, left, 0, right, quotient, result, scratch));
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, ExactDecimal.add(
        900_000_000_000_000_000L,
        SqlTypeDescriptor.decimal(18, 0),
        900_000_000_000_000_000L,
        SqlTypeDescriptor.decimal(18, 0),
        false,
        SqlTypeDescriptor.decimal(18, 0),
        result,
        scratch));
  }

  @Test
  void roundsQuantizesAndFindsIntegralBoundsDeterministically() {
    ExactDecimal.LongValue result = new ExactDecimal.LongValue();
    ExactDecimal.WideScratch scratch = new ExactDecimal.WideScratch();
    int source = SqlTypeDescriptor.decimal(4, 3);
    int target = SqlTypeDescriptor.decimal(3, 2);
    assertEquals(StatusCode.OK, ExactDecimal.quantize(
        1_245, source, target, true, false, result, scratch));
    assertEquals(124, result.value);
    assertEquals(StatusCode.OK, ExactDecimal.quantize(
        1_255, source, target, true, false, result, scratch));
    assertEquals(126, result.value);
    assertEquals(StatusCode.OK, ExactDecimal.quantize(
        -1_259, source, target, false, false, result, scratch));
    assertEquals(-125, result.value);
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, ExactDecimal.quantize(
        1_251, source, target, false, true, result, scratch));

    assertEquals(StatusCode.OK, ExactDecimal.integral(
        1_201, source, true, result));
    assertEquals(2, result.value);
    assertEquals(StatusCode.OK, ExactDecimal.integral(
        -1_201, source, false, result));
    assertEquals(-2, result.value);

    assertTrue(ExactDecimal.ceilingScale(
        1_201, source, target, result));
    assertEquals(121, result.value);
    assertTrue(ExactDecimal.ceilingScale(
        -1_201, source, target, result));
    assertEquals(-120, result.value);
  }

  @Test
  void matchesBigDecimalForBoundedRandomArithmetic() {
    Random random = new Random(0x5249564552444543L);
    ExactDecimal.LongValue result = new ExactDecimal.LongValue();
    ExactDecimal.WideScratch scratch = new ExactDecimal.WideScratch();
    for (int iteration = 0; iteration < 2_000; iteration++) {
      int leftDescriptor = randomDescriptor(random);
      int rightDescriptor = randomDescriptor(random);
      long left = randomValue(random, leftDescriptor);
      long right = randomValue(random, rightDescriptor);
      BigDecimal leftDecimal = decimal(left, leftDescriptor);
      BigDecimal rightDecimal = decimal(right, rightDescriptor);

      assertArithmetic(
          ExactDecimal.add(
              left,
              leftDescriptor,
              right,
              rightDescriptor,
              false,
              ExactDecimal.addResultDescriptor(leftDescriptor, rightDescriptor),
              result,
              scratch),
          leftDecimal.add(rightDecimal),
          ExactDecimal.addResultDescriptor(leftDescriptor, rightDescriptor),
          result,
          iteration,
          "add");
      assertArithmetic(
          ExactDecimal.add(
              left,
              leftDescriptor,
              right,
              rightDescriptor,
              true,
              ExactDecimal.addResultDescriptor(leftDescriptor, rightDescriptor),
              result,
              scratch),
          leftDecimal.subtract(rightDecimal),
          ExactDecimal.addResultDescriptor(leftDescriptor, rightDescriptor),
          result,
          iteration,
          "subtract");
      assertArithmetic(
          ExactDecimal.multiply(
              left,
              leftDescriptor,
              right,
              rightDescriptor,
              ExactDecimal.multiplyResultDescriptor(leftDescriptor, rightDescriptor),
              result,
              scratch),
          leftDecimal.multiply(rightDecimal),
          ExactDecimal.multiplyResultDescriptor(leftDescriptor, rightDescriptor),
          result,
          iteration,
          "multiply");
      if (right != 0) {
        int quotientDescriptor =
            ExactDecimal.divideResultDescriptor(leftDescriptor, rightDescriptor);
        assertArithmetic(
            ExactDecimal.divide(
                left,
                leftDescriptor,
                right,
                rightDescriptor,
                quotientDescriptor,
                result,
                scratch),
            leftDecimal.divide(
                rightDecimal,
                SqlTypeDescriptor.parameterTwo(quotientDescriptor),
                RoundingMode.HALF_EVEN),
            quotientDescriptor,
            result,
            iteration,
            "divide");
        int remainderDescriptor =
            ExactDecimal.remainderResultDescriptor(leftDescriptor, rightDescriptor);
        assertArithmetic(
            ExactDecimal.remainder(
                left,
                leftDescriptor,
                right,
                rightDescriptor,
                remainderDescriptor,
                result,
                scratch),
            leftDecimal.remainder(rightDecimal),
            remainderDescriptor,
            result,
            iteration,
            "remainder");
      }
      assertEquals(
          Integer.signum(leftDecimal.compareTo(rightDecimal)),
          Integer.signum(ExactDecimal.compare(
              left, leftDescriptor, right, rightDescriptor)),
          "compare iteration " + iteration);

      int targetScale = random.nextInt(
          SqlTypeDescriptor.parameterOne(leftDescriptor) + 1);
      int targetDescriptor = ExactDecimal.quantizedDescriptor(leftDescriptor, targetScale);
      if (targetDescriptor != 0) {
        assertArithmetic(
            ExactDecimal.quantize(
                left,
                leftDescriptor,
                targetDescriptor,
                true,
                false,
                result,
                scratch),
            leftDecimal.setScale(targetScale, RoundingMode.HALF_EVEN),
            targetDescriptor,
            result,
            iteration,
            "quantize");
      }
    }
  }

  @Test
  void matchesBigDecimalForRandomWideAverages() {
    Random random = new Random(0x4156455241474531L);
    ExactDecimal.LongValue result = new ExactDecimal.LongValue();
    ExactDecimal.WideScratch scratch = new ExactDecimal.WideScratch();
    for (int iteration = 0; iteration < 500; iteration++) {
      int inputScale = random.nextInt(7);
      int targetScale = Math.min(12, inputScale + 6);
      int targetDescriptor = SqlTypeDescriptor.decimal(18, targetScale);
      int count = random.nextInt(30) + 1;
      BigInteger sum = BigInteger.ZERO;
      for (int index = 0; index < count; index++) {
        sum = sum.add(BigInteger.valueOf(
            random.nextLong(-1_000_000_000L, 1_000_000_001L)));
      }
      BigDecimal expected = new BigDecimal(sum, inputScale)
          .divide(BigDecimal.valueOf(count), targetScale, RoundingMode.HALF_EVEN);
      boolean fits = fits(expected, targetDescriptor);
      assertEquals(
          fits,
          ExactDecimal.average(
              sum.shiftRight(64).longValue(),
              sum.longValue(),
              count,
              inputScale,
              targetDescriptor,
              result,
              scratch),
          "average iteration " + iteration);
      if (fits) {
        assertEquals(expected.unscaledValue().longValueExact(), result.value);
      }
    }
  }

  private static int randomDescriptor(Random random) {
    int precision = random.nextInt(
        SqlTypeDescriptor.MAXIMUM_COMPACT_DECIMAL_PRECISION) + 1;
    return SqlTypeDescriptor.decimal(precision, random.nextInt(precision + 1));
  }

  private static long randomValue(Random random, int descriptor) {
    long limit = ExactDecimal.powerOfTen(SqlTypeDescriptor.parameterOne(descriptor));
    return random.nextLong(-limit + 1, limit);
  }

  private static BigDecimal decimal(long value, int descriptor) {
    return BigDecimal.valueOf(
        value, SqlTypeDescriptor.parameterTwo(descriptor));
  }

  private static void assertArithmetic(
      StatusCode actualStatus,
      BigDecimal expected,
      int descriptor,
      ExactDecimal.LongValue actual,
      int iteration,
      String operation) {
    int scale = SqlTypeDescriptor.parameterTwo(descriptor);
    BigDecimal rounded = expected.setScale(scale, RoundingMode.HALF_EVEN);
    boolean fits = fits(rounded, descriptor);
    String context = operation + " iteration " + iteration
        + " descriptor " + descriptor + " expected " + rounded
        + " actual-unscaled " + actual.value;
    assertEquals(
        fits ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        actualStatus,
        context);
    if (fits) {
      assertEquals(
          rounded.unscaledValue().longValueExact(),
          actual.value,
          context);
    }
  }

  private static boolean fits(BigDecimal value, int descriptor) {
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) return false;
    BigInteger magnitude = value.unscaledValue().abs();
    return magnitude.compareTo(
        BigInteger.TEN.pow(SqlTypeDescriptor.parameterOne(descriptor))) < 0;
  }
}
