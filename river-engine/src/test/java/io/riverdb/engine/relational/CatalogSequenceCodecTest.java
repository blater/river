package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class CatalogSequenceCodecTest {
  @Test
  void preservesVersionOneImages() {
    ByteBuffer encoded = buffer();
    CatalogSequenceCodec.encodeAllocation(encoded, 7);
    assertEquals(
        "52495645525345510000000100000007", hex(encoded));

    CatalogSequenceCodec.encodeUser(encoded, "seq", 9, -2, true);
    assertEquals(
        "524956455255534500000001000000030000000000000009"
            + "fffffffffffffffe00000001736571",
        hex(encoded));

    CatalogSequenceCodec.encodeIdentity(encoded, 7, 11, false);
    assertEquals(
        "52495645524944530000000100000007000000000000000b00000000",
        hex(encoded));
  }

  @Test
  void roundTripsAllSequenceKinds() {
    ByteBuffer encoded = buffer();
    CatalogSequenceCodec.encodeAllocation(encoded, 7);
    CatalogSequenceCodec.IntResult allocation = new CatalogSequenceCodec.IntResult();
    assertEquals(
        StatusCode.OK,
        CatalogSequenceCodec.decodeAllocation(
            row(encoded), buffer(), allocation));
    assertEquals(7, allocation.value());

    CatalogSequenceCodec.encodeUser(encoded, "seq", 9, -2, true);
    CatalogSequenceCodec.SequenceResult sequence =
        new CatalogSequenceCodec.SequenceResult();
    assertEquals(
        StatusCode.OK,
        CatalogSequenceCodec.decodeUser(
            row(encoded), buffer(), "seq", sequence));
    assertEquals(9, sequence.nextValue());
    assertEquals(-2, sequence.increment());
    assertEquals(true, sequence.isExhausted());

    CatalogSequenceCodec.encodeIdentity(encoded, 7, 11, false);
    assertEquals(
        StatusCode.OK,
        CatalogSequenceCodec.decodeIdentity(
            row(encoded), buffer(), 7, sequence));
    assertEquals(11, sequence.nextValue());
    assertEquals(1, sequence.increment());
    assertEquals(false, sequence.isExhausted());
  }

  @Test
  void preservesMismatchAndCorruptionStatus() {
    ByteBuffer encoded = buffer();
    CatalogSequenceCodec.encodeUser(encoded, "seq", 9, 2, false);
    CatalogSequenceCodec.SequenceResult result =
        new CatalogSequenceCodec.SequenceResult();
    assertEquals(
        StatusCode.CONFLICT,
        CatalogSequenceCodec.decodeUser(
            row(encoded), buffer(), "set", result));
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogSequenceCodec.decodeUser(
            row(encoded), buffer(), "long", result));

    encoded.putLong(24, 0);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogSequenceCodec.decodeUser(
            row(encoded), buffer(), "seq", result));

    CatalogSequenceCodec.encodeIdentity(encoded, 7, 11, false);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogSequenceCodec.decodeIdentity(
            row(encoded), buffer(), 8, result));

    CatalogSequenceCodec.encodeAllocation(encoded, 0);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogSequenceCodec.decodeAllocation(
            row(encoded), buffer(), new CatalogSequenceCodec.IntResult()));
  }

  private static ByteBuffer buffer() {
    return ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
  }

  private static HeapRowResult row(ByteBuffer encoded) {
    HeapRowResult row = new HeapRowResult();
    row.set(encoded, 1, 0, encoded.remaining());
    return row;
  }

  private static String hex(ByteBuffer encoded) {
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
