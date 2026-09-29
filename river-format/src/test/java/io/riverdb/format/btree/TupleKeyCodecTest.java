package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.text.Utf8TextArena;
import io.riverdb.base.tuple.TupleOrder;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class TupleKeyCodecTest {
  @Test
  void admittedUtf8KeyMatchesCharacterKeyForEmptyAndUnicodeText() {
    int descriptor = SqlTypeDescriptor.varchar(16);
    Utf8TextArena text = new Utf8TextArena();
    assertEquals(StatusCode.OK, text.reserve(64, 64));
    ByteBuffer expected = ByteBuffer.allocate(128);
    ByteBuffer actual = ByteBuffer.allocate(128);
    TupleKeyBuilder characters = new TupleKeyBuilder();
    TupleKeyBuilder utf8 = new TupleKeyBuilder();
    for (String value : new String[] {"", "plain", "A£河🌊", "🌊🌊"}) {
      text.reset();
      assertEquals(StatusCode.OK, text.append(value, 16));
      assertEquals(StatusCode.OK, characters.beginTuple(expected, 0, 1));
      assertEquals(StatusCode.OK, characters.addText(descriptor, value));
      assertEquals(StatusCode.OK, characters.finishTuple());
      assertEquals(StatusCode.OK, utf8.beginTuple(actual, 0, 1));
      assertEquals(StatusCode.OK, utf8.addUtf8(
          descriptor, text, text.lastOffset(), text.lastLength()));
      assertEquals(StatusCode.OK, utf8.finishTuple());
      assertEquals(characters.keyBytes(), utf8.keyBytes());
      byte[] characterBytes = new byte[characters.keyBytes()];
      byte[] utf8Bytes = new byte[utf8.keyBytes()];
      expected.get(0, characterBytes);
      actual.get(0, utf8Bytes);
      assertArrayEquals(characterBytes, utf8Bytes);
    }
  }

  @Test
  void utf8KeyRejectsMalformedExternalBytesBeforeEncoding() {
    TupleKeyBuilder builder = new TupleKeyBuilder();
    ByteBuffer target = ByteBuffer.allocate(128);
    int descriptor = SqlTypeDescriptor.varchar(8);
    byte[][] malformed = {
        {(byte) 0xc0, (byte) 0x81},
        {(byte) 0xe0, (byte) 0x80, (byte) 0x81},
        {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
        {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
        {(byte) 0xe2, (byte) 0x28, (byte) 0xa1}
    };
    for (byte[] bytes : malformed) {
      BoundedByteSource source = new BoundedByteSource() {
        @Override public int length() { return bytes.length; }
        @Override public byte getByte(int index) { return bytes[index]; }
      };
      assertEquals(StatusCode.OK, builder.beginTuple(target, 0, 1));
      assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
          builder.addUtf8(descriptor, source, 0, bytes.length));
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
