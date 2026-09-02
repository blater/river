package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CatalogStatisticsCodecTest {
  @Test
  void rejectsForeignTableDefinitionsBeforeCatalogWrite() {
    RelationalSchemaGate local = new RelationalSchemaGate();
    RelationalSchemaGate foreign = new RelationalSchemaGate();
    TableDefinition table = new TableDefinition();
    table.set(foreign, 7, 0, TableDefinition.INDEX_NONE);
    TableStatistics statistics = statistics();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        new RelationalCatalogDdl(local).writeStatistics(null, table, statistics));
  }

  @Test
  void roundTripsCanonicalLittleEndianPayloadAndRejectsByteExactCorruption() {
    RelationalSchemaGate gate = new RelationalSchemaGate();
    TableDefinition table = new TableDefinition();
    table.set(gate, 7, 0, TableDefinition.INDEX_NONE);
    TableStatistics source = statistics();
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogStatisticsCodec.payloadBytes(2));
    assertEquals(StatusCode.OK, CatalogStatisticsCodec.encode(encoded, source, 0, 2));

    TableStatistics decoded = new TableStatistics();
    assertEquals(StatusCode.OK, CatalogStatisticsCodec.decode(
        encoded, 0, encoded.remaining(), table, 0, 2, decoded));
    assertTrue(decoded.availableFor(table));
    assertEquals(9, decoded.epoch());
    assertEquals(3, decoded.rowCount());
    assertEquals(1, decoded.nullCount(1));
    assertEquals(2, decoded.distinctCount(1));
    assertEquals(10, decoded.minimumValue(1));
    assertEquals(20, decoded.maximumValue(1));
    assertFalse(decoded.sampled(0));
    assertTrue(decoded.sampled(1));

    encoded.put(CatalogStatisticsCodec.HEADER_BYTES, (byte) 4);
    assertEquals(StatusCode.CORRUPTION, CatalogStatisticsCodec.decode(
        encoded, 0, encoded.remaining(), table, 0, 2, decoded));
    assertEquals(StatusCode.OK, CatalogStatisticsCodec.encode(encoded, source, 0, 2));
    assertEquals(StatusCode.CORRUPTION, CatalogStatisticsCodec.decode(
        encoded, 0, encoded.remaining() - 1, table, 0, 2, decoded));
    encoded.putLong(0, 0);
    assertEquals(StatusCode.CORRUPTION, CatalogStatisticsCodec.decode(
        encoded, 0, encoded.remaining(), table, 0, 2, decoded));
  }

  @Test
  void canonicalCarrierRejectsImpossibleCountsAndRanges() {
    RelationalSchemaGate gate = new RelationalSchemaGate();
    TableDefinition table = new TableDefinition();
    table.set(gate, 7, 0, TableDefinition.INDEX_NONE);
    TableStatistics source = new TableStatistics();
    source.begin(7, 2, 9);
    source.setRowCount(3);
    source.setColumn(0, 0, 4, false, false, 0, 0);
    assertFalse(source.canonicalFor(table));
    source.begin(7, 2, 9);
    source.setRowCount(3);
    source.setColumn(0, 3, 0, false, true, 1, 1);
    assertFalse(source.canonicalFor(table));
  }

  private static TableStatistics statistics() {
    TableStatistics source = new TableStatistics();
    source.begin(7, 2, 9);
    source.setRowCount(3);
    source.setColumn(0, 0, 3, false, true, 1, 3);
    source.setColumn(1, 1, 2, true, true, 10, 20);
    return source;
  }
}
