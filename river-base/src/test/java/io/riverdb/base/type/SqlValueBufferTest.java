package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class SqlValueBufferTest {
  private static volatile long allocationGuard;

  @Test
  void storesFixedNullAndPackedUnicodeTextLanes() {
    SqlValueBuffer values = prepared(65, 128);
    int varchar = SqlTypeDescriptor.varchar(4);

    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, 42));
    assertEquals(StatusCode.OK, values.setNull(64, SqlTypeDescriptor.BOOLEAN));
    assertEquals(StatusCode.OK, values.setText(8, varchar, "A£河🌊"));

    assertEquals(42, values.valueAt(0));
    assertFalse(values.isNull(0));
    assertTrue(values.isNull(64));
    assertEquals(1, values.nullWord(1));
    assertEquals(varchar, values.descriptorAt(8));
    assertEquals(10, values.textByteLengthAt(8));
    byte[] encoded = new byte[10];
    assertEquals(StatusCode.OK, values.copyTextBytes(8, encoded, 0));
    assertEquals("A£河🌊", new String(encoded, StandardCharsets.UTF_8));
    char[] decoded = new char[5];
    int decodedLength = values.copyTextChars(8, decoded, 0);
    assertEquals(5, decodedLength);
    assertEquals("A£河🌊", new String(decoded, 0, decodedLength));
  }

  @Test
  void storesWideDecimalInTwoPrimitiveLanes() {
    SqlValueBuffer values = prepared(2, 0);
    int decimal = SqlTypeDescriptor.decimal(38, 7);
    long high = 542_101_086_242_752_217L;
    long low = 68_739_955_140_067_328L;

    assertEquals(StatusCode.OK, values.setDecimal128(0, decimal, high, low));
    assertEquals(high, values.highValueAt(0));
    assertEquals(low, values.valueAt(0));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, values.setFixed(1, decimal, 1));
    assertEquals(StatusCode.OK, values.setNull(1, decimal));
  }

  @Test
  void failuresPreservePublishedValuesAndBounds() {
    SqlValueBuffer values = prepared(9, 8);
    int varchar = SqlTypeDescriptor.varchar(8);
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, 7));
    assertEquals(StatusCode.OK, values.setText(1, varchar, "eight"));
    int used = values.textBytesUsed();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        values.setFixed(0, SqlTypeDescriptor.BOOLEAN, 2));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, values.setText(2, varchar, "more"));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, values.setText(9, varchar, "x"));
    assertEquals(7, values.valueAt(0));
    assertEquals(used, values.textBytesUsed());
    assertEquals(-1, values.textByteLengthAt(2));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, values.reserve(10, 9, 8, 8));
    assertEquals(9, values.count());
  }

  @Test
  void rejectsLaneReassignmentWithoutConsumingTextBudget() {
    SqlValueBuffer values = prepared(3, 16);
    int varchar = SqlTypeDescriptor.varchar(8);
    assertEquals(StatusCode.OK, values.setText(0, varchar, "first"));
    int used = values.textBytesUsed();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, values.setText(0, varchar, "again"));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        values.setFixed(0, SqlTypeDescriptor.BIGINT, 1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, values.setNull(0, varchar));
    assertEquals(used, values.textBytesUsed());
    assertEquals(5, values.textByteLengthAt(0));
  }

  @Test
  void storesCallerOwnedCharacterSlices() {
    SqlValueBuffer values = prepared(1, 64);
    char[] source = "xVARCHAR(255)y".toCharArray();
    int varchar = SqlTypeDescriptor.varchar(16);
    assertEquals(StatusCode.OK, values.setText(0, varchar, source, 1, 12));
    assertEquals(12, values.textByteLengthAt(0));
    char[] decoded = new char[12];
    assertEquals(12, values.copyTextChars(0, decoded, 0));
    assertEquals("VARCHAR(255)", new String(decoded));
  }

  @Test
  void storesTpcCDataWidthWithoutAOneByteLengthAssumption() {
    SqlValueBuffer values = prepared(1, 512);
    int varchar = SqlTypeDescriptor.varchar(500);
    String data = "x".repeat(500);
    assertEquals(StatusCode.OK, values.setText(0, varchar, data));
    assertEquals(500, values.textByteLengthAt(0));
    char[] decoded = new char[500];
    assertEquals(500, values.copyTextChars(0, decoded, 0));
    assertEquals(data, new String(decoded));
  }

  @Test
  void clearsUsedPrefixesAndRetainsHighWaterCapacity() {
    SqlValueBuffer values = prepared(1_664, 64);
    assertEquals(StatusCode.OK, values.setFixed(1_663, SqlTypeDescriptor.BIGINT, 99));
    int laneCapacity = values.capacity();
    int textCapacity = values.textCapacity();

    assertEquals(StatusCode.OK, values.clearForSize(8));
    assertEquals(StatusCode.OK, values.clearForSize(1_664));
    assertEquals(0, values.valueAt(1_663));
    assertEquals(0, values.descriptorAt(1_663));
    assertFalse(values.isNull(1_663));
    assertEquals(laneCapacity, values.capacity());
    assertEquals(textCapacity, values.textCapacity());
  }

  @Test
  void warmedLaneAndTextOperationsDoNotAllocate() {
    ThreadMXBean bean = allocationBean();
    SqlValueBuffer values = prepared(8, 64);
    int varchar = SqlTypeDescriptor.varchar(8);
    char[] text = "river".toCharArray();
    // Cross the tiered-compilation threshold before measuring VM bookkeeping.
    exercise(values, varchar, text, 100_000);

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    exercise(values, varchar, text, 100_000);
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    // ThreadMXBean can report a few hundred bytes of one-time tiered-JIT bookkeeping.
    assertTrue(allocated <= 512, "warmed SQL value operations allocated: " + allocated);
  }

  @Test
  void storesCanonicalUtf8SlicesForRowDecode() {
    SqlValueBuffer values = prepared(1, 16);
    ByteBuffer source = ByteBuffer.wrap(new byte[] {0, 'A', (byte) 0xc2, (byte) 0xa3});
    source.position(1);
    assertEquals(StatusCode.OK,
        values.setTextBytes(0, SqlTypeDescriptor.varchar(2), source, 1, 3));
    assertEquals(1, source.position());
    assertEquals(0x41, values.textByteAt(0, 0));
    assertEquals(0xa3, values.textByteAt(0, 2));
    assertEquals(-1, values.textByteAt(0, 3));
    assertEquals(16, values.textMaximumBytes());
  }

  private static SqlValueBuffer prepared(int lanes, int textBytes) {
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(lanes, 1_664, textBytes, textBytes));
    assertEquals(StatusCode.OK, values.clearForSize(lanes));
    return values;
  }

  private static void exercise(
      SqlValueBuffer values, int varchar, char[] text, int iterations) {
    for (int index = 0; index < iterations; index++) {
      values.clearForSize(8);
      values.setFixed(0, SqlTypeDescriptor.BIGINT, index);
      values.setText(1, varchar, text, 0, text.length);
      allocationGuard += values.valueAt(0) + values.textByteLengthAt(1);
    }
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }
}
