package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class CatalogViewRecordTest {
  @Test
  void roundTripsBoundedViewDefinition() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    String query = "SELECT id, amount FROM events WHERE amount>=100";
    CatalogViewCodec.encode(encoded, "valuable", query, 7);
    HeapRowResult row = new HeapRowResult();
    row.set(encoded, 1, 0, encoded.remaining());
    ViewDefinition decoded = new ViewDefinition();
    assertEquals(
        StatusCode.OK,
        CatalogViewCodec.decode(
            row,
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            "valuable",
            decoded));
    assertEquals(query.length(), decoded.length());
    assertEquals(7, decoded.baseTableId());
    for (int index = 0; index < query.length(); index++) {
      assertEquals(query.charAt(index), decoded.charAt(index));
    }
  }

  @Test
  void preservesVersionOneBytes() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    CatalogViewCodec.encode(encoded, "v", "SELECT id FROM t", 7);

    byte[] actual = new byte[encoded.remaining()];
    encoded.get(actual);
    assertEquals(
        "524956455256494500000001000000010000001000000007"
            + "7653454c4543542069642046524f4d2074",
        HexFormat.of().formatHex(actual));
  }

  @Test
  void distinguishesFamilyAndNameMismatchFromCorruption() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    CatalogViewCodec.encode(encoded, "valuable", "SELECT id FROM events", 7);

    assertEquals(StatusCode.CORRUPTION, decode(encoded, "different"));
    assertEquals(StatusCode.CONFLICT, decode(encoded, "valuablx"));

    encoded.putLong(0, 0x524956455254424cL);
    assertEquals(StatusCode.CONFLICT, decode(encoded, "valuable"));

    CatalogViewCodec.encode(encoded, "valuable", "SELECT id FROM events", 7);
    encoded.putInt(8, 2);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "valuable"));

    CatalogViewCodec.encode(encoded, "valuable", "SELECT id FROM events", 7);
    encoded.limit(encoded.limit() - 1);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "valuable"));

    CatalogViewCodec.encode(encoded, "valuable", "SELECT id FROM events", 7);
    encoded.putInt(20, 0);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "valuable"));
  }

  @Test
  void scanDecodeRejectsMalformedNameAndResetsReusedResult() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    CatalogViewCodec.encode(encoded, "1bad", "SELECT id FROM events", 7);
    HeapRowResult row = row(encoded);
    ViewDefinition decoded = new ViewDefinition();
    decoded.append('x');
    decoded.setBaseTableId(9);

    assertEquals(
        StatusCode.CORRUPTION,
        CatalogViewCodec.decodeForScan(
            row,
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            new TableSchema.ColumnName(),
            decoded));
    assertEquals(1, decoded.length());
    assertEquals(9, decoded.baseTableId());

    assertEquals(
        StatusCode.CONFLICT,
        CatalogViewCodec.decode(
            row,
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            "nope",
            decoded));
    assertEquals(0, decoded.length());
    assertEquals(0, decoded.baseTableId());
  }

  private static StatusCode decode(
      ByteBuffer encoded, CharSequence expectedName) {
    return CatalogViewCodec.decode(
        row(encoded),
        ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
        expectedName,
        new ViewDefinition());
  }

  private static HeapRowResult row(ByteBuffer encoded) {
    HeapRowResult row = new HeapRowResult();
    row.set(encoded, 1, 0, encoded.remaining());
    return row;
  }
}
