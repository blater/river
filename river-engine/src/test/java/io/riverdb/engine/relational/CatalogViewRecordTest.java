package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CatalogViewRecordTest {
  @Test
  void roundTripsBoundedViewDefinition() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    String query = "SELECT id, amount FROM events WHERE amount>=100";
    assertEquals(
        StatusCode.OK,
        encode(encoded, "valuable", query, 7, 0));
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
    assertEquals(1, decoded.tableCount());
    assertEquals(7, decoded.tableId(0));
    assertEquals(0, decoded.tableId(1));
    for (int index = 0; index < query.length(); index++) {
      assertEquals(query.charAt(index), decoded.charAt(index));
    }
  }

  @Test
  void roundTripsCanonicalOrderedTwoTableLineage() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    assertEquals(
        StatusCode.OK,
        encode(
            encoded, "joined", "SELECT l.id FROM l JOIN r ON l.id=r.id", 7, 9));
    ViewDefinition decoded = new ViewDefinition();
    assertEquals(
        StatusCode.OK,
        CatalogViewCodec.decode(
            row(encoded),
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            "joined",
            decoded));
    assertEquals(2, decoded.tableCount());
    assertEquals(7, decoded.tableId(0));
    assertEquals(9, decoded.tableId(1));
  }

  @Test
  void preservesExactVersionFourHeaderLineageAndUtf8Bytes() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    String query = "SELECT id FROM t";
    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", query, 7, 0));

    assertEquals(153 + query.length(), encoded.remaining());
    assertEquals(0x5249564552564945L, encoded.getLong(0));
    assertEquals(4, encoded.getInt(8));
    assertEquals(1, encoded.getInt(12));
    assertEquals(query.length(), encoded.getInt(16));
    assertEquals(1, encoded.getInt(20));
    assertEquals(7, encoded.getInt(24));
    for (int index = 1; index < ViewDefinition.MAXIMUM_LINEAGE_TABLES; index++) {
      assertEquals(0, encoded.getInt(24 + index * Integer.BYTES));
    }
    assertEquals('v', Byte.toUnsignedInt(encoded.get(152)));
    for (int index = 0; index < query.length(); index++) {
      assertEquals(query.charAt(index), Byte.toUnsignedInt(encoded.get(153 + index)));
    }
  }

  @Test
  void roundTripsCanonicalBmpAndSupplementaryUtf8() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    String query = "SELECT '猫😀' FROM moments";
    assertEquals(
        StatusCode.OK,
        encode(encoded, "unicode", query, 7, 0));
    ViewDefinition decoded = new ViewDefinition();
    assertEquals(
        StatusCode.OK,
        CatalogViewCodec.decode(
            row(encoded),
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            "unicode",
            decoded));
    assertTrue(query.contentEquals(decoded));
  }

  @Test
  void distinguishesFamilyAndNameMismatchFromCorruption() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    assertEquals(
        StatusCode.OK,
        encode(
            encoded, "valuable", "SELECT id FROM events", 7, 0));

    assertEquals(StatusCode.CORRUPTION, decode(encoded, "different"));
    assertEquals(StatusCode.CONFLICT, decode(encoded, "valuablx"));

    encoded.putLong(0, 0x524956455254424cL);
    assertEquals(StatusCode.CONFLICT, decode(encoded, "valuable"));

    assertEquals(
        StatusCode.OK,
        encode(
            encoded, "valuable", "SELECT id FROM events", 7, 0));
    encoded.putInt(8, 3);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "valuable"));

    assertEquals(
        StatusCode.OK,
        encode(
            encoded, "valuable", "SELECT id FROM events", 7, 0));
    encoded.limit(encoded.limit() - 1);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "valuable"));

    assertEquals(
        StatusCode.OK,
        encode(
            encoded, "valuable", "SELECT id FROM events", 7, 0));
    int end = encoded.limit();
    encoded.limit(end + 1);
    encoded.put(end, (byte) 0);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "valuable"));

    assertEquals(
        StatusCode.OK,
        encode(
            encoded, "valuable", "SELECT id FROM events", 7, 0));
    encoded.putInt(20, 0);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "valuable"));
  }

  @Test
  void rejectsMalformedAndNoncanonicalUtf8() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", "SELECT id FROM events", 7, 0));
    int queryOffset = 153;
    encoded.put(queryOffset, (byte) 0xc0);
    encoded.put(queryOffset + 1, (byte) 0x80);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "v"));

    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", "SELECT id FROM events", 7, 0));
    encoded.put(queryOffset, (byte) 0xf0);
    encoded.put(queryOffset + 1, (byte) 0x9f);
    encoded.put(queryOffset + 2, (byte) 0x98);
    encoded.put(queryOffset + 3, (byte) 0x20);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "v"));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        encode(encoded, "v", "SELECT '\ud800'", 7, 0));
  }

  @Test
  void rejectsNoncanonicalLineageInBothDecoders() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", "SELECT id FROM events", 7, 9));
    encoded.putInt(20, 1);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "v"));

    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", "SELECT id FROM events", 7, 9));
    encoded.putInt(20, 2);
    encoded.putInt(28, 0);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "v"));

    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", "SELECT id FROM events", 7, 9));
    encoded.putInt(28, 7);
    assertEquals(StatusCode.OK, decode(encoded, "v"));
    assertEquals(
        StatusCode.OK,
        CatalogViewCodec.decodeForScan(
            row(encoded),
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            new TableSchema.ColumnName(),
            new ViewDefinition()));

    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", "SELECT id FROM events", 7, 0));
    encoded.putInt(20, 3);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "v"));

    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", "SELECT id FROM events", 7, 7));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        encode(encoded, "v", "SELECT id FROM events", 0, 9));

    assertEquals(StatusCode.OK, encode(encoded, "v", "SELECT id FROM events", 7, 0));
    encoded.putInt(24, RelationalKey.MAXIMUM_TABLE_ID + 1);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "v"));
  }

  @Test
  void roundTripsMaximumOrderedLineageAndRejectsNonzeroUnusedSlots() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    int[] tableIds = new int[ViewDefinition.MAXIMUM_LINEAGE_TABLES];
    for (int index = 0; index < tableIds.length; index++) tableIds[index] = index + 1;
    assertEquals(
        StatusCode.OK,
        CatalogViewCodec.encode(
            encoded, "v", "SELECT id FROM events", tableIds, tableIds.length));
    ViewDefinition decoded = new ViewDefinition();
    assertEquals(
        StatusCode.OK,
        CatalogViewCodec.decode(
            row(encoded),
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            "v",
            decoded));
    assertEquals(tableIds.length, decoded.tableCount());
    for (int index = 0; index < tableIds.length; index++) {
      assertEquals(tableIds[index], decoded.tableId(index));
    }

    assertEquals(StatusCode.OK, encode(encoded, "v", "SELECT id FROM events", 7, 0));
    encoded.putInt(24 + 31 * Integer.BYTES, 9);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, "v"));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogViewCodec.encode(
            encoded, "v", "SELECT id FROM events", tableIds, tableIds.length + 1));
  }

  @Test
  void enforcesDecodedQueryCapacityAtEncode() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    String maximum = "x".repeat(ViewDefinition.MAXIMUM_QUERY_LENGTH);
    assertEquals(
        StatusCode.OK,
        encode(encoded, "v", maximum, 7, 0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        encode(encoded, "v", maximum + "x", 7, 0));
  }

  @Test
  void scanDecodeRejectsMalformedNameAndResetsReusedResult() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    assertEquals(
        StatusCode.OK,
        encode(
            encoded, "1bad", "SELECT id FROM events", 7, 0));
    HeapRowResult row = row(encoded);
    ViewDefinition decoded = new ViewDefinition();
    decoded.append('x');
    setLineage(decoded, 9);

    assertEquals(
        StatusCode.CORRUPTION,
        CatalogViewCodec.decodeForScan(
            row,
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            new TableSchema.ColumnName(),
            decoded));
    assertEquals(1, decoded.length());
    assertEquals(1, decoded.tableCount());
    assertEquals(9, decoded.tableId(0));
    assertEquals(0, decoded.tableId(1));

    assertEquals(
        StatusCode.CONFLICT,
        CatalogViewCodec.decode(
            row,
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            "nope",
            decoded));
    assertEquals(0, decoded.length());
    assertEquals(0, decoded.tableCount());
    assertEquals(0, decoded.tableId(0));
    assertEquals(0, decoded.tableId(1));
  }

  private static StatusCode decode(
      ByteBuffer encoded, CharSequence expectedName) {
    return CatalogViewCodec.decode(
        row(encoded),
        ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
        expectedName,
        new ViewDefinition());
  }

  private static StatusCode encode(
      ByteBuffer target,
      CharSequence name,
      CharSequence query,
      int firstTableId,
      int secondTableId) {
    int[] tableIds = secondTableId == 0
        ? new int[] {firstTableId} : new int[] {firstTableId, secondTableId};
    return CatalogViewCodec.encode(
        target, name, query, tableIds, tableIds.length);
  }

  private static void setLineage(ViewDefinition target, int... tableIds) {
    ByteBuffer encoded = ByteBuffer.allocate(tableIds.length * Integer.BYTES);
    for (int index = 0; index < tableIds.length; index++) {
      encoded.putInt(index * Integer.BYTES, tableIds[index]);
    }
    target.setLineage(encoded, 0, tableIds.length);
  }

  private static HeapRowResult row(ByteBuffer encoded) {
    HeapRowResult row = new HeapRowResult();
    row.set(encoded, 1, 0, encoded.remaining());
    return row;
  }
}
