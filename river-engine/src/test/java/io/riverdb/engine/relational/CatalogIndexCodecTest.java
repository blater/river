package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class CatalogIndexCodecTest {
  @Test
  void preservesVersionThreeImageAndRoundTrips() {
    ByteBuffer encoded = buffer();
    CatalogIndexCodec.encode(
        encoded,
        7,
        9,
        TableDefinition.INDEX_BUILDING,
        "events_value",
        false,
        true);
    assertEquals(
        "5249564552494e440000000300000007000000090000000100000002"
            + "0000000c6576656e74735f76616c7565",
        hex(encoded));

    CatalogIndexCodec.Result result = new CatalogIndexCodec.Result();
    assertEquals(
        StatusCode.OK,
        CatalogIndexCodec.decode(
            row(encoded), buffer(), "events_value", result));
    assertEquals(7, result.tableId());
    assertEquals(9, result.indexTableId());
    assertEquals(TableDefinition.INDEX_BUILDING, result.state());
    assertEquals(false, result.isUnique());
    assertEquals(true, result.isConstraint());
  }

  @Test
  void preservesMismatchAndCorruptionStatus() {
    ByteBuffer encoded = buffer();
    CatalogIndexCodec.encode(encoded, 7, 9, "events_value");
    CatalogIndexCodec.Result result = new CatalogIndexCodec.Result();

    assertEquals(
        StatusCode.CONFLICT,
        CatalogIndexCodec.decode(
            row(encoded), buffer(), "events_other", result));
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogIndexCodec.decode(
            row(encoded), buffer(), "short", result));
    assertEquals(
        StatusCode.CONFLICT,
        CatalogIndexCodec.decodeForTable(
            row(encoded), buffer(), 8, result));

    encoded.putInt(20, 99);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogIndexCodec.decodeForTable(
            row(encoded), buffer(), 7, result));
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
    ByteBuffer view = encoded.duplicate();
    byte[] bytes = new byte[view.remaining()];
    view.get(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
