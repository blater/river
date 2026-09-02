package io.riverdb.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlApproximateNumeric;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class PublicResultCapacityTest {
  private static volatile long allocationGuard;

  @Test
  void commandCarriesMaximumResultShapeAndHighNullOrdinals() {
    int columns = SqlShapeLimits.MAX_RESULT_COLUMNS;
    long[] values = new long[columns];
    int[] descriptors = descriptors(columns);
    long[] nulls = new long[(columns + 63) / 64];
    set(nulls, 0);
    set(nulls, 63);
    set(nulls, 64);
    set(nulls, columns - 1);
    descriptors[columns - 2] = SqlTypeDescriptor.varchar(2);

    CommandResult result = new CommandResult();
    assertEquals(
        StatusCode.OK,
        result.complete(1, 7, false, true, 0, values, nulls, nulls.length,
            descriptors, columns));
    assertEquals(StatusCode.OK,
        result.setTextAt(columns - 2, "A😀".toCharArray(), 0, 3));

    assertEquals(columns, result.columnCount());
    assertEquals(nulls.length, result.nullWordCount());
    assertTrue(result.isNull(0));
    assertTrue(result.isNull(63));
    assertTrue(result.isNull(64));
    assertTrue(result.isNull(columns - 1));
    assertFalse(result.isNull(columns - 2));
    assertEquals(3, result.textLengthAt(columns - 2));
    assertEquals('A', result.textCharacterAt(columns - 2, 0));
    assertEquals(Character.highSurrogate(0x1f600), result.textCharacterAt(columns - 2, 1));
  }

  @Test
  void rowRejectsOverLimitAndNonCanonicalNullWordsWithoutPublishing() {
    RowResult result = new RowResult();
    int maximum = SqlShapeLimits.MAX_RESULT_COLUMNS;
    int columns = maximum - 1;
    long[] values = new long[maximum + 1];
    int[] descriptors = descriptors(maximum + 1);
    long[] nulls = new long[(columns + 63) / 64];
    nulls[nulls.length - 1] = 1L << (columns & 63);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        result.complete(0, values, nulls, nulls.length, descriptors, columns));
    assertFalse(result.isAvailable());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        result.complete(0, values, new long[(maximum + 64) / 64], (maximum + 64) / 64,
            descriptors, maximum + 1));
    assertFalse(result.isAvailable());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        result.complete(0, values, 0, descriptors, Long.SIZE + 1));
  }

  @Test
  void resetRetainsCapacityAndWarmedNarrowRowsDoNotAllocate() {
    ThreadMXBean bean = allocationBean();
    RowResult result = new RowResult();
    long[] values = new long[8];
    int[] descriptors = descriptors(8);
    descriptors[1] = SqlTypeDescriptor.varchar(8);
    char[] text = "river".toCharArray();
    assertEquals(StatusCode.OK, result.reserve(8, 64));
    exercise(result, values, descriptors, text, 10_000);

    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    exercise(result, values, descriptors, text, 100_000);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertTrue(allocated <= 256, "warmed public row operations allocated: " + allocated);
  }

  @Test
  void metadataReservationIsGenerationExactAndFailureDoesNotPublish() {
    MutableMetadata metadata = new MutableMetadata(65, 4_096, 1);
    RowResult result = new RowResult();
    StatusDetail detail = new StatusDetail(96);

    assertEquals(StatusCode.OK, result.reserve(metadata, detail));
    assertTrue(result.isReservedFor(metadata));
    result.reset();
    assertTrue(result.isReservedFor(metadata));
    metadata.generation = 2;
    assertFalse(result.isReservedFor(metadata));

    MutableMetadata oversized = new MutableMetadata(
        SqlShapeLimits.MAX_RESULT_COLUMNS + 1, 0, 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, result.reserve(oversized, detail));
    assertFalse(result.isAvailable());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, detail.code());
  }

  @Test
  void validatesAndPublishesCanonicalNumericResultLanes() {
    long[] values = {
        Short.MAX_VALUE,
        Integer.MIN_VALUE,
        Long.MAX_VALUE,
        -1_250,
        SqlApproximateNumeric.realBits(1.5f),
        SqlApproximateNumeric.doubleBits(-2.25d)
    };
    int[] descriptors = {
        SqlTypeDescriptor.SMALLINT,
        SqlTypeDescriptor.INTEGER,
        SqlTypeDescriptor.BIGINT,
        SqlTypeDescriptor.decimal(6, 3),
        SqlTypeDescriptor.REAL,
        SqlTypeDescriptor.DOUBLE
    };
    CommandResult result = new CommandResult();
    assertEquals(StatusCode.OK,
        result.complete(1, 1, false, true, 0, values, 0, descriptors, values.length));
    assertEquals(Short.MAX_VALUE, result.smallintAt(0));
    assertEquals(Integer.MIN_VALUE, result.integerAt(1));
    assertEquals(Long.MAX_VALUE, result.bigintAt(2));
    assertEquals(-1_250, result.decimalUnscaledAt(3));
    assertEquals(1.5f, result.realAt(4));
    assertEquals(-2.25d, result.doubleAt(5));

    values[4] = Integer.toUnsignedLong(Float.floatToRawIntBits(-0.0f));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        result.complete(1, 1, false, true, 0, values, 0, descriptors, values.length));
  }

  @Test
  void publishesWideDecimalResultPairsAndRejectsOutOfDomainPairs() {
    int descriptor = SqlTypeDescriptor.decimal(38, 6);
    long[] highs = {669_260_594_276_348_691L};
    long[] lows = {-4_302_749_291_975_740_594L};
    RowResult result = new RowResult();
    assertEquals(StatusCode.OK, result.complete(
        1, highs, lows, new long[1], 1, new int[] {descriptor}, 1));
    assertEquals(0, result.decimalUnscaledAt(0));
    assertEquals(highs[0], result.decimalUnscaledHighAt(0));
    assertEquals(lows[0], result.decimalUnscaledLowAt(0));

    highs[0] = Long.MAX_VALUE;
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, result.complete(
        1, highs, lows, new long[1], 1, new int[] {descriptor}, 1));
  }

  private static void exercise(
      RowResult result, long[] values, int[] descriptors, char[] text, int iterations) {
    for (int index = 0; index < iterations; index++) {
      values[0] = index;
      result.reset();
      result.complete(index, values, 0, descriptors, 8);
      result.setTextAt(1, text, 0, text.length);
      allocationGuard += result.valueAt(0) + result.textLengthAt(1);
    }
  }

  private static int[] descriptors(int columns) {
    int[] descriptors = new int[columns];
    java.util.Arrays.fill(descriptors, SqlTypeDescriptor.BIGINT);
    return descriptors;
  }

  private static void set(long[] words, int bit) {
    words[bit >>> 6] |= 1L << (bit & 63);
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }

  private static final class MutableMetadata implements QueryMetadata {
    private final int columns;
    private final int textBytes;
    private long generation;

    private MutableMetadata(int columnCount, int maximumTextBytes, long queryGeneration) {
      columns = columnCount;
      textBytes = maximumTextBytes;
      generation = queryGeneration;
    }

    @Override
    public int columnCount() { return columns; }

    @Override
    public int maximumEncodedTextBytes() { return textBytes; }

    @Override
    public long reservationGeneration() { return generation; }
  }
}
