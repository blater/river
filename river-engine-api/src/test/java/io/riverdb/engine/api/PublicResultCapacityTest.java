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
import java.nio.ByteBuffer;
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
    ByteBuffer encoded = ByteBuffer.allocate(5);
    assertEquals(5, result.encodedTextLengthAt(columns - 2));
    assertEquals(5, result.copyEncodedTextAt(columns - 2, encoded, 0));
    assertEquals(0x41, Byte.toUnsignedInt(encoded.get(0)));
    assertEquals(0xf0, Byte.toUnsignedInt(encoded.get(1)));
    assertEquals(0x9f, Byte.toUnsignedInt(encoded.get(2)));
    assertEquals(0x98, Byte.toUnsignedInt(encoded.get(3)));
    assertEquals(0x80, Byte.toUnsignedInt(encoded.get(4)));
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
  void warmedReleaseHighWaterRetainsCommandAndRowBitmapsWithoutAllocation() {
    ThreadMXBean bean = allocationBean();
    RowResult row = new RowResult();
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, row.reserve(8, 0));
    assertEquals(StatusCode.OK, command.reserve(8, 0));
    assertEquals(StatusCode.OK, row.releaseHighWater());
    assertEquals(StatusCode.OK, command.releaseHighWater());
    exerciseReleaseHighWater(row, command, 10_000);

    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    exerciseReleaseHighWater(row, command, 100_000);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertTrue(
        allocated <= 256,
        "warmed command and row releaseHighWater operations allocated: " + allocated);
  }

  @Test
  void releaseHighWaterErasesWideResultStateAndPreservesExactLeaseAccounting() {
    TrackingLease lease = new TrackingLease(Long.MAX_VALUE);
    RowResult result = new RowResult(lease);
    int columns = 128;
    long[] values = new long[columns];
    java.util.Arrays.fill(values, 37);
    int[] descriptors = descriptors(columns);
    descriptors[0] = SqlTypeDescriptor.varchar(5_000);
    long[] nullWords = new long[2];
    set(nullWords, 1);
    set(nullWords, columns - 1);
    char[] text = new char[5_000];
    java.util.Arrays.fill(text, 'x');

    assertEquals(StatusCode.OK, result.reserve(columns, 8_192));
    assertEquals(StatusCode.OK,
        result.complete(5, values, nullWords, nullWords.length, descriptors, columns));
    assertEquals(StatusCode.OK, result.setTextAt(0, text, 0, text.length));
    assertTrue(result.isAvailable());
    assertEquals(37, result.valueAt(0));
    assertEquals(5_000, result.textLengthAt(0));
    assertTrue(result.isNull(1));
    assertTrue(result.isNull(columns - 1));
    assertTrue(result.retainedBytes() > RowResult.retainedFloorBytes());
    assertEquals(result.retainedBytes(), lease.retainedBytes());

    assertEquals(StatusCode.OK, result.releaseHighWater());
    assertFalse(result.isAvailable());
    assertEquals(0, result.columnCount());
    assertEquals(0, result.valueAt(0));
    assertEquals(0, result.typeDescriptorAt(0));
    assertEquals(0, result.nullWordCount());
    assertEquals(0, result.nullWord(0));
    assertEquals(-1, result.textLengthAt(0));
    assertEquals(0, result.textCharacterAt(0, 0));
    assertEquals(RowResult.retainedFloorBytes(), result.retainedBytes());
    assertEquals(result.retainedBytes(), lease.retainedBytes());

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        result.complete(1, new long[1], new long[1], 1, new int[1],
            SqlShapeLimits.MAX_RESULT_COLUMNS + 1));
    assertFalse(result.isAvailable());
    assertEquals(RowResult.retainedFloorBytes(), lease.retainedBytes());

    long[] narrowValues = new long[8];
    narrowValues[0] = 91;
    int[] narrowDescriptors = descriptors(8);
    long[] narrowNullWords = {1};
    assertEquals(StatusCode.OK,
        result.complete(6, narrowValues, narrowNullWords, 1, narrowDescriptors, 8));
    assertTrue(result.isAvailable());
    assertEquals(91, result.valueAt(0));
    assertEquals(SqlTypeDescriptor.BIGINT, result.typeDescriptorAt(0));
    assertTrue(result.isNull(0));
    assertEquals(1, result.nullWord(0));
    assertEquals(StatusCode.OK, result.releaseHighWater());
    assertFalse(result.isAvailable());
    assertEquals(RowResult.retainedFloorBytes(), result.retainedBytes());
    assertEquals(result.retainedBytes(), lease.retainedBytes());

    narrowValues[0] = 92;
    assertEquals(StatusCode.OK,
        result.complete(7, narrowValues, new long[1], 1, narrowDescriptors, 8));
    assertEquals(92, result.valueAt(0));
    assertFalse(result.isNull(0));
    assertEquals(-1, result.textLengthAt(0));
    assertEquals(result.retainedBytes(), lease.retainedBytes());

    assertEquals(StatusCode.OK, result.release());
    assertEquals(0, result.retainedBytes());
    assertEquals(0, lease.retainedBytes());
  }

  @Test
  void rejectedReservationKeepsPriorBudgetChargeAndZeroColumnReleaseKeepsNoBitmap() {
    TrackingLease lease = new TrackingLease(512);
    RowResult result = new RowResult(lease);
    assertEquals(StatusCode.OK, result.reserve(8, 0));
    long priorCharge = result.retainedBytes();
    assertTrue(priorCharge > 0);
    assertEquals(priorCharge, lease.retainedBytes());

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, result.reserve(64, 1_024));
    assertEquals(priorCharge, result.retainedBytes());
    assertEquals(priorCharge, lease.retainedBytes());

    long[] values = new long[8];
    values[0] = 14;
    assertEquals(StatusCode.OK, result.complete(7, values, 0, descriptors(8), 8));
    assertEquals(14, result.valueAt(0));
    assertEquals(StatusCode.OK, result.release());
    assertEquals(0, result.retainedBytes());
    assertEquals(0, lease.retainedBytes());

    TrackingLease emptyLease = new TrackingLease(512);
    CommandResult empty = new CommandResult(emptyLease);
    assertEquals(StatusCode.OK,
        empty.complete(0, 0, false, false, 0, new long[0], 0, new int[0], 0));
    assertEquals(StatusCode.OK, empty.releaseHighWater());
    assertEquals(0, empty.nullWordCount());
    assertEquals(0, empty.nullWord(0));
    assertEquals(0, empty.retainedBytes());
    assertEquals(0, emptyLease.retainedBytes());
  }

  @Test
  void carriesMaximumDeclaredVarcharWithoutAConvenienceScratchLimit() {
    int scalars = io.riverdb.base.text.Utf8Text.MAXIMUM_SCALARS;
    char[] value = new String(new char[] {(char) 0xD83D, (char) 0xDE00})
        .repeat(scalars).toCharArray();
    int descriptor = SqlTypeDescriptor.varchar(scalars);
    CommandResult result = new CommandResult();

    assertEquals(StatusCode.OK, result.reserve(1, scalars * 4));
    assertEquals(StatusCode.OK, result.complete(
        1, 1, false, true, 0, new long[1], 0, new int[] {descriptor}, 1));
    assertEquals(StatusCode.OK, result.setTextAt(0, value, 0, value.length));
    assertEquals(value.length, result.textLengthAt(0));
    assertEquals(scalars * 4, result.encodedTextLengthAt(0));
    char[] decoded = new char[value.length];
    assertEquals(value.length, result.copyTextAt(0, decoded, 0));
    assertTrue(java.util.Arrays.equals(value, decoded));

    char[] oversized = java.util.Arrays.copyOf(value, value.length + 1);
    oversized[oversized.length - 1] = 'x';
    assertEquals(StatusCode.STRING_DATA_RIGHT_TRUNCATION,
        result.setTextAt(0, oversized, 0, oversized.length));
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

  private static void exerciseReleaseHighWater(
      RowResult row, CommandResult command, int iterations) {
    for (int index = 0; index < iterations; index++) {
      allocationGuard += row.releaseHighWater().ordinal();
      allocationGuard += command.releaseHighWater().ordinal();
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

  private static final class TrackingLease implements RetainedMemoryLease {
    private final long budget;
    private long current;

    private TrackingLease(long maximumBytes) { budget = maximumBytes; }

    @Override
    public StatusCode resize(long bytes) {
      if (bytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (bytes > budget) return StatusCode.RESOURCE_EXHAUSTED;
      current = bytes;
      return StatusCode.OK;
    }

    @Override
    public StatusCode awaitResize(long bytes) { return resize(bytes); }

    @Override
    public long retainedBytes() { return current; }
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
