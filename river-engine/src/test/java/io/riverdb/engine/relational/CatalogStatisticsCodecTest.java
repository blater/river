package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CatalogStatisticsCodecTest {
  @Test
  void rejectsForeignTableDefinitionsBeforeCatalogWrite() {
    RelationalSchemaGate local = new RelationalSchemaGate();
    RelationalSchemaGate foreign = new RelationalSchemaGate();
    TableDefinition table = new TableDefinition();
    table.set(foreign, 7, 0, TableDefinition.INDEX_NONE);
    TableStatistics statistics = new TableStatistics();
    statistics.begin(7, 2, 9);
    statistics.setRowCount(0);
    statistics.setColumn(0, 0, 0, false, false, 0, 0);
    statistics.setColumn(1, 0, 0, false, false, 0, 0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        new RelationalCatalogDdl(local).writeStatistics(null, table, statistics));
  }

  @Test
  void roundTripsCanonicalStatisticsAndRejectsCorruption() {
    RelationalSchemaGate gate = new RelationalSchemaGate();
    TableDefinition table = new TableDefinition();
    table.set(gate, 7, 0, TableDefinition.INDEX_NONE);
    TableStatistics source = new TableStatistics();
    source.begin(7, 2, 9);
    source.setRowCount(3);
    source.setColumn(0, 0, 3, false, true, 1, 3);
    source.setColumn(1, 1, 2, true, true, 10, 20);
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogStatisticsCodec.BYTES + 1);
    CatalogStatisticsCodec.encode(encoded, source);

    TableStatistics decoded = new TableStatistics();
    assertEquals(StatusCode.OK, decode(encoded, table, decoded));
    assertTrue(decoded.availableFor(table));
    assertEquals(9, decoded.epoch());
    assertEquals(3, decoded.rowCount());
    assertEquals(1, decoded.nullCount(1));
    assertEquals(2, decoded.distinctCount(1));
    assertEquals(10, decoded.minimumValue(1));
    assertEquals(20, decoded.maximumValue(1));
    assertFalse(decoded.sampled(0));
    assertTrue(decoded.sampled(1));

    source.setRowCount(-1);
    assertFalse(source.canonicalFor(table));
    source.begin(7, 2, 9);
    source.setRowCount(3);
    source.setColumn(0, 0, 4, false, false, 0, 0);
    assertFalse(source.canonicalFor(table));
    source.begin(7, 2, 9);
    source.setRowCount(3);
    source.setColumn(0, 0, 0, false, false, 0, 0);
    assertFalse(source.canonicalFor(table));
    source.begin(7, 2, 9);
    source.setRowCount(3);
    source.setColumn(0, 3, 0, false, true, 1, 1);
    assertFalse(source.canonicalFor(table));
    source.begin(7, 2, 9);
    source.setRowCount(3);
    source.setColumn(0, 0, 3, false, true, 1, 3);
    source.setColumn(1, 1, 2, true, true, 10, 20);

    CatalogStatisticsCodec.encode(encoded, source);
    encoded.putLong(120, 0);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, table, decoded));
    CatalogStatisticsCodec.encode(encoded, source);
    encoded.putInt(8, 2);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, table, decoded));
    CatalogStatisticsCodec.encode(encoded, source);
    encoded.putLong(72, 1);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, table, decoded));
    CatalogStatisticsCodec.encode(encoded, source);
    encoded.limit(encoded.limit() - 1);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, table, decoded));
    CatalogStatisticsCodec.encode(encoded, source);
    encoded.limit(encoded.limit() + 1);
    encoded.put(encoded.limit() - 1, (byte) 0);
    assertEquals(StatusCode.CORRUPTION, decode(encoded, table, decoded));
  }

  private static StatusCode decode(
      ByteBuffer encoded, TableDefinition table, TableStatistics result) {
    HeapRowResult row = new HeapRowResult();
    row.set(encoded, 1, 0, encoded.remaining());
    return CatalogStatisticsCodec.decode(
        row,
        ByteBuffer.allocateDirect(CatalogStatisticsCodec.BYTES + 1),
        table,
        result);
  }
}
