package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class TupleKeyCodecTest {
  @Test
  void encodesMixedCompositeTextAndStableNonuniqueTieBreaks() {
    ByteBuffer bytes = ByteBuffer.allocate(256);
    TupleKeyBuilder first = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, first.begin(bytes, 8, 4));
    assertEquals(StatusCode.OK, first.addFixed(SqlTypeDescriptor.TYPE_ID_BIGINT, 1));
    assertEquals(StatusCode.OK, first.addFixed(SqlTypeDescriptor.TYPE_ID_BIGINT, 2));
    assertEquals(StatusCode.OK, first.addText(SqlTypeDescriptor.varchar(16), "BAR\u0000"));
    assertEquals(StatusCode.OK, first.addText(SqlTypeDescriptor.varchar(16), "Å🙂"));
    assertEquals(StatusCode.OK, first.finish(7));
    assertTrue(TupleKeyCodec.validate(bytes, first.keyOffset(), first.keyBytes()));
    assertEquals(7, TupleKeyCodec.logicalRowId(bytes, first.keyOffset(), first.keyBytes()));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "010400000101800000000000000101018000000000000002020100000043000000"
                + "420000005300000001000000000201000000c60001f64300000000000000000000"
                + "0007"),
        Arrays.copyOfRange(bytes.array(), first.keyOffset(), first.keyOffset() + first.keyBytes()));

    TupleKeyBuilder second = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, second.begin(bytes, 128, 4));
    assertEquals(StatusCode.OK, second.addFixed(SqlTypeDescriptor.TYPE_ID_BIGINT, 1));
    assertEquals(StatusCode.OK, second.addFixed(SqlTypeDescriptor.TYPE_ID_BIGINT, 2));
    assertEquals(StatusCode.OK, second.addText(SqlTypeDescriptor.varchar(16), "BAR\u0000"));
    assertEquals(StatusCode.OK, second.addText(SqlTypeDescriptor.varchar(16), "Å🙂"));
    assertEquals(StatusCode.OK, second.finish(8));
    assertTrue(TupleKeyCodec.compare(
        bytes, first.keyOffset(), first.keyBytes(),
        bytes, second.keyOffset(), second.keyBytes()) < 0);
    assertEquals(0, TupleKeyCodec.compareUserTuple(
        bytes, first.keyOffset(), first.keyBytes(),
        bytes, second.keyOffset(), second.keyBytes()));
  }

  @Test
  void nullSortsBeforeValuesAndMalformedKeysFailClosed() {
    ByteBuffer bytes = ByteBuffer.allocate(128);
    TupleKeyBuilder nullKey = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, nullKey.begin(bytes, 0, 1));
    assertEquals(StatusCode.OK, nullKey.addNull(SqlTypeDescriptor.varchar(1)));
    assertEquals(StatusCode.OK, nullKey.finish(1));
    TupleKeyBuilder valueKey = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, valueKey.begin(bytes, 32, 1));
    assertEquals(StatusCode.OK, valueKey.addText(SqlTypeDescriptor.varchar(1), "A"));
    assertEquals(StatusCode.OK, valueKey.finish(1));
    assertTrue(TupleKeyCodec.compare(
        bytes, nullKey.keyOffset(), nullKey.keyBytes(),
        bytes, valueKey.keyOffset(), valueKey.keyBytes()) < 0);

    bytes.put(valueKey.keyOffset(), (byte) 0);
    assertFalse(TupleKeyCodec.validate(bytes, valueKey.keyOffset(), valueKey.keyBytes()));
  }

  @Test
  void enforcesTextAndFixedValueDomainsAtTheDurableBoundary() {
    ByteBuffer bytes = ByteBuffer.allocate(TupleKeyCodec.MAXIMUM_KEY_BYTES * 2);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.begin(bytes, 0, 1));
    assertEquals(
        StatusCode.OK,
        builder.addText(
            SqlTypeDescriptor.varchar(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS),
            "a".repeat(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS)));
    assertEquals(StatusCode.OK, builder.finish(1));
    assertTrue(TupleKeyCodec.validate(bytes, 0, builder.keyBytes()));

    assertEquals(StatusCode.OK, builder.begin(bytes, 0, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        builder.addText(
            SqlTypeDescriptor.varchar(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS),
            "a".repeat(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS + 1)));
    assertEquals(StatusCode.OK, builder.begin(bytes, 0, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        builder.addFixed(SqlTypeDescriptor.BOOLEAN, 2));

    assertEquals(StatusCode.OK, builder.begin(bytes, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BOOLEAN, 1));
    assertEquals(StatusCode.OK, builder.finish(1));
    TupleKeyCodec.putBigEndianLong(bytes, TupleKeyCodec.HEADER_BYTES + 2, 2 ^ Long.MIN_VALUE);
    assertFalse(TupleKeyCodec.validate(bytes, 0, builder.keyBytes()));
  }
}
