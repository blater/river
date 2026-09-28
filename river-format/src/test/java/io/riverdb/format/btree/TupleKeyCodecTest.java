package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleOrder;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class TupleKeyCodecTest {
  private static final int COMPARE_OFFSET = 9;
  private static final int COMPARE_LENGTH = 80;

  @Test
  void wordComparisonMatchesByteLoopAcrossBufferKindsAndOrders() {
    byte[] original = new byte[COMPARE_LENGTH];
    for (int index = 0; index < original.length; index++) {
      original[index] = (byte) ((index * 73 + 11) ^ (index >>> 1));
    }
    ByteOrder[] orders = {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN};
    for (int leftKind = 0; leftKind < 6; leftKind++) {
      int rightKind = (leftKind + 3) % 6;
      for (ByteOrder leftOrder : orders) {
        for (ByteOrder rightOrder : orders) {
          ByteBuffer left = comparisonView(original, leftKind, leftOrder, COMPARE_OFFSET);
          ByteBuffer equalRight = comparisonView(original, rightKind, rightOrder, COMPARE_OFFSET);
          for (int length = 0; length <= COMPARE_LENGTH; length++) {
            assertComparisonMatchesByteLoop(
                left, length, equalRight, length, COMPARE_OFFSET);
            if (length < COMPARE_LENGTH) {
              assertComparisonMatchesByteLoop(
                  left, length, equalRight, length + 1, COMPARE_OFFSET);
              assertComparisonMatchesByteLoop(
                  left, length + 1, equalRight, length, COMPARE_OFFSET);
            }
          }
          for (int mismatch = 0; mismatch < COMPARE_LENGTH; mismatch++) {
            byte[] changed = original.clone();
            changed[mismatch] ^= (byte) 0xff;
            ByteBuffer right = comparisonView(changed, rightKind, rightOrder, COMPARE_OFFSET);
            assertComparisonMatchesByteLoop(
                left, COMPARE_LENGTH, right, COMPARE_LENGTH, COMPARE_OFFSET);
            assertComparisonMatchesByteLoop(
                right, COMPARE_LENGTH, left, COMPARE_LENGTH, COMPARE_OFFSET);
          }
        }
      }
    }
  }

  @Test
  void wordComparisonSupportsEveryByteAlignment() {
    byte[] original = new byte[32];
    for (int index = 0; index < original.length; index++) {
      original[index] = (byte) (index * 41 + 3);
    }
    int[] mismatches = {0, 7, 8, 15, 16, 23, 31};
    ByteOrder[] orders = {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN};
    for (int offset = 8; offset < 16; offset++) {
      for (int kind = 0; kind < 6; kind++) {
        for (ByteOrder order : orders) {
          ByteBuffer left = comparisonView(original, kind, order, offset);
          for (int mismatch : mismatches) {
            byte[] changed = original.clone();
            changed[mismatch] ^= (byte) 0xff;
            ByteBuffer right = comparisonView(changed, kind, order, offset);
            assertComparisonMatchesByteLoop(
                left, original.length, right, changed.length, offset);
          }
        }
      }
    }
  }

  @Test
  void physicalMixedTupleOrdersByUserValuesThenLogicalIdentity() {
    int[] descriptors = {
        SqlTypeDescriptor.BIGINT,
        SqlTypeDescriptor.BOOLEAN,
        SqlTypeDescriptor.decimal(12, 2),
        SqlTypeDescriptor.DATE,
        SqlTypeDescriptor.time(6),
        SqlTypeDescriptor.timestamp(6),
        SqlTypeDescriptor.timestampWithTimeZone(6),
        SqlTypeDescriptor.varchar(16)
    };
    TupleShape shape = shape(descriptors);
    ByteBuffer bytes = ByteBuffer.allocate(1024);
    int firstBytes = mixedKey(bytes, 0, descriptors, "Å🙂", 7);
    int secondBytes = mixedKey(bytes, 256, descriptors, "Å🙂", 8);
    assertTrue(TupleKeyCodec.matchesPhysicalIndexKey(bytes, 0, firstBytes, shape));
    assertEquals(7, TupleKeyCodec.logicalRowId(bytes, 0, firstBytes));
    assertEquals(0, TupleKeyCodec.compareUserTuple(
        bytes, 0, firstBytes, bytes, 256, secondBytes));
    assertTrue(TupleKeyCodec.compare(
        bytes, 0, firstBytes, bytes, 256, secondBytes) < 0);
  }

  @Test
  void genericTupleAdmits1664PartsWhileIndexRejectsPart33() {
    int count = SqlShapeLimits.MAX_TUPLE_PARTS;
    int[] descriptors = new int[count];
    Arrays.fill(descriptors, SqlTypeDescriptor.BOOLEAN);
    TupleShape shape = shape(descriptors);
    ByteBuffer bytes = ByteBuffer.allocate(8_192);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginTuple(bytes, 0, count));
    for (int part = 0; part < count; part++) {
      assertEquals(StatusCode.OK, builder.addNull(descriptors[part]));
    }
    assertEquals(StatusCode.OK, builder.finishTuple());
    assertEquals(count, TupleKeyCodec.arity(bytes, 0, builder.keyBytes()));
    assertTrue(TupleKeyCodec.matchesShape(bytes, 0, builder.keyBytes(), shape));
    assertFalse(TupleKeyCodec.isPhysical(bytes, 0, builder.keyBytes()));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        builder.beginIndex(bytes, 0, SqlShapeLimits.MAX_KEY_PARTS + 1));
  }

  @Test
  void rejects3073UserBytesBeforePhysicalKeyPublication() {
    int text = SqlTypeDescriptor.varchar(255);
    ByteBuffer bytes = ByteBuffer.allocate(4_096);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(bytes, 0, 3));
    assertEquals(StatusCode.OK, builder.addText(text, "a".repeat(255)));
    assertEquals(StatusCode.OK, builder.addText(text, "b".repeat(254)));
    assertEquals(StatusCode.OK, builder.addText(text, "c".repeat(254)));
    assertEquals(3_073, TupleKeyCodec.headerBytes(3) + 3 * 2 + (255 + 254 + 254 + 3) * 4);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, builder.finishPhysical(1));
    assertEquals(0, builder.keyBytes());
  }

  @Test
  void semanticComparatorSuppliesDirectionAndNullPlacement() {
    TupleShape shape = shape(new int[] {SqlTypeDescriptor.BIGINT});
    ByteBuffer bytes = ByteBuffer.allocate(128);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginTuple(bytes, 0, 1));
    assertEquals(StatusCode.OK, builder.addNull(SqlTypeDescriptor.BIGINT));
    assertEquals(StatusCode.OK, builder.finishTuple());
    int nullBytes = builder.keyBytes();
    assertEquals(StatusCode.OK, builder.beginTuple(bytes, 32, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, 9));
    assertEquals(StatusCode.OK, builder.finishTuple());
    int valueBytes = builder.keyBytes();
    ByteBufferTupleInput left = new ByteBufferTupleInput();
    ByteBufferTupleInput right = new ByteBufferTupleInput();
    assertEquals(StatusCode.OK, left.reset(bytes, 0, nullBytes));
    assertEquals(StatusCode.OK, right.reset(bytes, 32, valueBytes));
    TupleOrder.Result orderResult = new TupleOrder.Result();
    assertEquals(StatusCode.OK, TupleOrder.create(
        shape, new byte[] {(byte) TupleOrder.DESC_NULLS_LAST}, 0, orderResult));
    TupleComparison comparison = new TupleComparison();
    assertEquals(StatusCode.OK, new TupleComparator().compare(
        left, right, shape, orderResult.value(), false, comparison));
    assertTrue(comparison.value() > 0);
  }

  @Test
  void wideDecimalsPreserveSignedNumericOrder() {
    int decimal = SqlTypeDescriptor.decimal(38, 4);
    TupleShape shape = shape(new int[] {decimal});
    ByteBuffer bytes = ByteBuffer.allocate(128);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginTuple(bytes, 0, 1));
    assertEquals(StatusCode.OK, builder.addDecimal128(decimal, -1, -1));
    assertEquals(StatusCode.OK, builder.finishTuple());
    int negativeBytes = builder.keyBytes();
    assertEquals(StatusCode.OK, builder.beginTuple(bytes, 64, 1));
    assertEquals(StatusCode.OK, builder.addDecimal128(decimal, 0, Long.MIN_VALUE));
    assertEquals(StatusCode.OK, builder.finishTuple());
    int positiveBytes = builder.keyBytes();

    assertTrue(TupleKeyCodec.matchesShape(bytes, 0, negativeBytes, shape));
    assertTrue(TupleKeyCodec.matchesShape(bytes, 64, positiveBytes, shape));
    assertTrue(TupleKeyCodec.compare(
        bytes, 0, negativeBytes, bytes, 64, positiveBytes) < 0);
  }

  @Test
  void rejectsMalformedFlagsArityTextAndFixedDomains() {
    ByteBuffer bytes = ByteBuffer.allocate(128);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(bytes, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BOOLEAN, 1));
    assertEquals(StatusCode.OK, builder.finishPhysical(1));
    int length = builder.keyBytes();
    bytes.put(1, (byte) 2);
    assertFalse(TupleKeyCodec.validate(bytes, 0, length));
    bytes.put(1, (byte) TupleKeyCodec.FLAG_PHYSICAL);
    int valueOffset = TupleKeyCodec.headerBytes(1) + 2;
    TupleKeyCodec.putBigEndianLong(bytes, valueOffset, 2 ^ Long.MIN_VALUE);
    assertFalse(TupleKeyCodec.validate(bytes, 0, length));
  }

  private static ByteBuffer comparisonView(
      byte[] source, int kind, ByteOrder order, int offset) {
    boolean direct = kind == 1 || kind == 4 || kind == 5;
    boolean sliced = kind >= 3;
    ByteBuffer backing = direct
        ? ByteBuffer.allocateDirect(source.length + offset + 16)
        : ByteBuffer.allocate(source.length + offset + 16);
    int payloadOffset = offset + (sliced ? 4 : 0);
    for (int index = 0; index < source.length; index++) {
      backing.put(payloadOffset + index, source[index]);
    }
    ByteBuffer view = backing;
    if (sliced) {
      backing.position(4);
      backing.limit(backing.capacity() - 2);
      view = backing.slice();
    }
    if (kind == 2 || kind == 5) view = view.asReadOnlyBuffer();
    view.order(order);
    view.position(2);
    view.limit(view.capacity() - 1);
    return view;
  }

  private static void assertComparisonMatchesByteLoop(
      ByteBuffer left, int leftLength, ByteBuffer right, int rightLength, int offset) {
    int leftPosition = left.position();
    int leftLimit = left.limit();
    ByteOrder leftOrder = left.order();
    int rightPosition = right.position();
    int rightLimit = right.limit();
    ByteOrder rightOrder = right.order();
    int expected = compareByByte(left, leftLength, right, rightLength, offset);
    assertEquals(expected, TupleKeyCodec.compare(
        left, offset, leftLength, right, offset, rightLength));
    assertEquals(leftPosition, left.position());
    assertEquals(leftLimit, left.limit());
    assertEquals(leftOrder, left.order());
    assertEquals(rightPosition, right.position());
    assertEquals(rightLimit, right.limit());
    assertEquals(rightOrder, right.order());
  }

  private static int compareByByte(
      ByteBuffer left, int leftLength, ByteBuffer right, int rightLength, int offset) {
    int shared = Math.min(leftLength, rightLength);
    for (int index = 0; index < shared; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(left.get(offset + index)),
          Byte.toUnsignedInt(right.get(offset + index)));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftLength, rightLength);
  }

  private static int mixedKey(
      ByteBuffer target, int offset, int[] descriptors, String text, long logicalRowId) {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, offset, descriptors.length));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[0], -4));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[1], 1));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[2], 1234));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[3], 0));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[4], 1_000_000));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[5], 0));
    assertEquals(StatusCode.OK, builder.addFixed(descriptors[6], 0));
    assertEquals(StatusCode.OK, builder.addText(descriptors[7], text));
    assertEquals(StatusCode.OK, builder.finishPhysical(logicalRowId));
    return builder.keyBytes();
  }

  private static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK, TupleShape.create(descriptors, result));
    return result.value();
  }
}
