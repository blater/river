package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class LocalTemporalStorageValidationTest {
  @Test
  void rejectsOutOfDomainAndOverPrecisePersistedValues() {
    TableSchema schema = temporalSchema();
    TableDefinition definition = new TableDefinition();
    definition.set(
        new RelationalSchemaGate(),
        17,
        0,
        TableDefinition.INDEX_NONE,
        -1,
        schema);
    ByteBuffer row = ByteBuffer.allocateDirect(definition.fixedRowBytes());
    row.putLong(0, LocalTemporal.MINIMUM_EPOCH_DAY);
    row.putLong(8, 86_399_999_000L);
    row.putLong(16, 0);
    row.putLong(24, 0);
    row.position(0);
    assertTrue(definition.isValidRow(row));

    row.putLong(8, 1);
    assertFalse(definition.isValidRow(row));
    row.putLong(8, 0);
    row.putLong(16, LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS);
    assertFalse(definition.isValidRow(row));
    row.putLong(16, LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS - 999);
    assertTrue(definition.isValidRow(row));
    row.putLong(0, LocalTemporal.MINIMUM_EPOCH_DAY - 1);
    assertFalse(definition.isValidRow(row));
    row.putLong(0, 0);
    row.putLong(24, LocalTemporal.MAXIMUM_INSTANT_MICROSECONDS);
    assertFalse(definition.isValidRow(row));
    row.putLong(24, LocalTemporal.MAXIMUM_INSTANT_MICROSECONDS - 999);
    assertTrue(definition.isValidRow(row));
  }

  @Test
  void rejectsCorruptTemporalCatalogDefaultsAndChecks() {
    TableSchema schema = temporalSchema();
    assertEquals(StatusCode.OK, schema.setLastCheck(TableSchema.CHECK_GREATER_OR_EQUAL, 0));
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    CatalogRecord.encodeTable(
        encoded,
        17,
        0,
        TableDefinition.INDEX_NONE,
        -1,
        "temporal_catalog",
        schema);
    int bytes = encoded.remaining();
    HeapRowResult source = new HeapRowResult();
    ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);

    encoded.putLong(CatalogRecord.TABLE_DEFAULTS_OFFSET + 2 * Long.BYTES, 1);
    source.set(encoded, 1, 0, bytes);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogRecord.decodeTable(
            source,
            scratch,
            "temporal_catalog",
            new RelationalSchemaGate(),
            new TableDefinition()));

    encoded.putLong(CatalogRecord.TABLE_DEFAULTS_OFFSET + 2 * Long.BYTES, 0);
    encoded.putLong(CatalogRecord.TABLE_CHECK_VALUES_OFFSET + 4 * Long.BYTES, 1);
    source.set(encoded, 1, 0, bytes);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogRecord.decodeTable(
            source,
            scratch,
            "temporal_catalog",
            new RelationalSchemaGate(),
            new TableDefinition()));

    encoded.putLong(CatalogRecord.TABLE_CHECK_VALUES_OFFSET + 4 * Long.BYTES, 0);
    encoded.putLong(CatalogRecord.TABLE_DEFAULTS_OFFSET + 4 * Long.BYTES, 1);
    source.set(encoded, 1, 0, bytes);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogRecord.decodeTable(
            source,
            scratch,
            "temporal_catalog",
            new RelationalSchemaGate(),
            new TableDefinition()));

    encoded.putLong(CatalogRecord.TABLE_DEFAULTS_OFFSET + 4 * Long.BYTES, 0);
    encoded.put(CatalogRecord.TABLE_DEFAULT_KINDS_OFFSET + 4, (byte) SqlDefaultKind.NONE);
    source.set(encoded, 1, 0, bytes);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogRecord.decodeTable(
            source,
            scratch,
            "temporal_catalog",
            new RelationalSchemaGate(),
            new TableDefinition()));

  }

  @Test
  void persistsCanonicalExpressionChecksAfterTextDefaults() {
    TableSchema schema = new TableSchema();
    assertEquals(StatusCode.OK, schema.addBigint("id", false));
    assertEquals(StatusCode.OK, schema.addVarchar("label", 8, true));
    ByteBuffer defaultText = ByteBuffer.wrap(new byte[] {'x'});
    assertEquals(StatusCode.OK, schema.setLastTextDefault(defaultText));
    assertEquals(StatusCode.OK, schema.addColumn("day", SqlTypeDescriptor.DATE, true));
    byte[] operators = {
        (byte) TableSchema.CHECK_COLUMN, (byte) TableSchema.CHECK_CAST};
    long[] operands = {2, 0};
    int[] descriptors = {SqlTypeDescriptor.DATE, SqlTypeDescriptor.DATE};
    assertEquals(
        StatusCode.OK,
        schema.setCheck(
            2,
            TableSchema.CHECK_GREATER_OR_EQUAL,
            SqlTypeDescriptor.DATE,
            0,
            2,
            operators,
            operands,
            descriptors));

    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    int bytes = encoded.remaining();
    HeapRowResult source = new HeapRowResult();
    ByteBuffer scratch = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    source.set(encoded, 1, 0, bytes);
    assertEquals(
        StatusCode.OK,
        CatalogRecord.decodeTable(
            source,
            scratch,
            "checked_text",
            new RelationalSchemaGate(),
            new TableDefinition()));

    encoded.putLong(bytes - 13 + 5, 1);
    assertCorrupt(encoded, source, scratch, bytes);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    encoded.putInt(bytes - 26 + 1, SqlTypeDescriptor.DATE | 1 << 8);
    assertCorrupt(encoded, source, scratch, bytes);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    encoded.putInt(CatalogRecord.TABLE_CHECK_NODE_TOTAL_OFFSET, 1);
    assertCorrupt(encoded, source, scratch, bytes);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    encoded.put(CatalogRecord.TABLE_CHECK_NODE_COUNTS_OFFSET + 2, (byte) 1);
    assertCorrupt(encoded, source, scratch, bytes);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    encoded.put(bytes - 26, (byte) 99);
    assertCorrupt(encoded, source, scratch, bytes);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    encoded.putLong(bytes - 26 + 5, 1);
    assertCorrupt(encoded, source, scratch, bytes);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    encoded.putInt(
        CatalogRecord.TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET + 2 * Integer.BYTES,
        SqlTypeDescriptor.BIGINT);
    assertCorrupt(encoded, source, scratch, bytes);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    encoded.putInt(
        CatalogRecord.TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET + Integer.BYTES,
        SqlTypeDescriptor.BIGINT);
    assertCorrupt(encoded, source, scratch, bytes);
    CatalogRecord.encodeTable(
        encoded, 17, 0, TableDefinition.INDEX_NONE, -1, "checked_text", schema);
    encoded.putInt(8, 13);
    assertCorrupt(encoded, source, scratch, bytes);
  }

  @Test
  void rejectsOutOfOrderFlatCheckProgramsAndBooleanOrdering() {
    TableSchema schema = new TableSchema();
    assertEquals(StatusCode.OK, schema.addBigint("id", false));
    assertEquals(StatusCode.OK, schema.addBigint("one", true));
    assertEquals(StatusCode.OK, schema.addColumn(
        "enabled", SqlTypeDescriptor.BOOLEAN, true));
    byte[] operator = {(byte) TableSchema.CHECK_COLUMN};
    long[] enabled = {2};
    int[] booleanType = {SqlTypeDescriptor.BOOLEAN};
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        schema.setCheck(
            2,
            TableSchema.CHECK_GREATER_THAN,
            SqlTypeDescriptor.BOOLEAN,
            0,
            1,
            operator,
            enabled,
            booleanType));
    assertEquals(
        StatusCode.OK,
        schema.setCheck(
            2,
            TableSchema.CHECK_EQUAL,
            SqlTypeDescriptor.BOOLEAN,
            1,
            1,
            operator,
            enabled,
            booleanType));
    long[] one = {1};
    int[] bigintType = {SqlTypeDescriptor.BIGINT};
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        schema.setCheck(
            1,
            TableSchema.CHECK_EQUAL,
            SqlTypeDescriptor.BIGINT,
            1,
            1,
            operator,
            one,
            bigintType));
  }

  private static TableSchema temporalSchema() {
    TableSchema schema = new TableSchema();
    assertEquals(StatusCode.OK, schema.addBigint("id", false));
    assertEquals(StatusCode.OK, schema.addColumn(
        "day", SqlTypeDescriptor.DATE, true));
    assertEquals(StatusCode.OK, schema.setLastDefault(0));
    assertEquals(StatusCode.OK, schema.addColumn(
        "alarm", SqlTypeDescriptor.time(3), true));
    assertEquals(StatusCode.OK, schema.setLastDefault(0));
    assertEquals(StatusCode.OK, schema.addColumn(
        "observed", SqlTypeDescriptor.timestamp(3), true));
    assertEquals(StatusCode.OK, schema.setLastDefault(0));
    assertEquals(StatusCode.OK, schema.addColumn(
        "captured", SqlTypeDescriptor.timestampWithTimeZone(3), true));
    assertEquals(
        StatusCode.OK, schema.setLastCurrentDefault(SqlDefaultKind.CURRENT_TIMESTAMP));
    return schema;
  }

  private static void assertCorrupt(
      ByteBuffer encoded, HeapRowResult source, ByteBuffer scratch, int bytes) {
    source.set(encoded, 1, 0, bytes);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogRecord.decodeTable(
            source,
            scratch,
            "checked_text",
            new RelationalSchemaGate(),
            new TableDefinition()));
  }
}
