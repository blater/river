package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class TableDefinitionCapacityTest {
  @Test
  void allocatesForActualShapeAndCopiesHighOrdinalColumnState() {
    TableDefinition definition = new TableDefinition();
    assertEquals(0, definition.columnNames.length);
    assertEquals(0, definition.uniqueIndexTableIds.length);

    TableSchema schema = bigintSchema(130);
    assertEquals(StatusCode.OK, schema.setReference(129, 91));
    assertEquals(StatusCode.OK, schema.setLastDefault(7));
    assertEquals(StatusCode.OK, schema.setLastCheck(TableSchema.CHECK_GREATER_THAN, 0));
    assertEquals(
        StatusCode.OK,
        definition.set(
            new RelationalSchemaGate(),
            41,
            0,
            TableDefinition.INDEX_NONE,
            -1,
            schema));

    assertEquals(130, definition.columnCount());
    assertTrue(definition.hasReference(129));
    assertTrue(definition.hasDefault(129));
    assertTrue(definition.hasCheck(129));
    assertEquals(1L << 1, definition.referenceWord(2));
    assertEquals(256, definition.columnNames.length);
  }

  @Test
  void admitsSixtyFourSecondaryIndexesAndRejectsTheNextWithoutMutation() {
    TableDefinition definition = new TableDefinition();
    assertEquals(
        StatusCode.OK,
        definition.set(
            new RelationalSchemaGate(),
            42,
            0,
            TableDefinition.INDEX_NONE,
            -1,
            bigintSchema(66)));
    for (int index = 0; index < SqlShapeLimits.MAX_SECONDARY_INDEXES; index++) {
      assertEquals(
          StatusCode.OK,
          definition.upsertIndex(
              1_000 + index, TableDefinition.INDEX_READY, index + 1, false));
    }

    assertEquals(SqlShapeLimits.MAX_SECONDARY_INDEXES, definition.uniqueIndexCount());
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        definition.upsertIndex(2_000, TableDefinition.INDEX_READY, 65, false));
    assertEquals(SqlShapeLimits.MAX_SECONDARY_INDEXES, definition.uniqueIndexCount());
  }

  @Test
  void validatesWideTransitionalNullBitmapAndRejectsOversizedRows() {
    TableDefinition definition = transitionalDefinition(1_024);
    ByteBuffer row = ByteBuffer.allocateDirect(definition.fixedRowBytes() + 1);
    row.limit(definition.fixedRowBytes());
    int bitmap = definition.nullBitmapOffset();

    int[] columns = {7, 8, 63, 64, 255, 256, 1_023};
    for (int column : columns) {
      row.put(bitmap + (column >>> 3), (byte) (1 << (column & 7)));
      assertTrue(definition.isNull(row, column));
      assertTrue(definition.isValidRow(row));
      row.put(bitmap + (column >>> 3), (byte) 0);
    }

    row.limit(definition.fixedRowBytes() + 1);
    assertEquals(false, definition.isNull(row, 1_023));
    assertEquals(false, definition.isValidRow(row));

    definition = transitionalDefinition(1_023);
    row = ByteBuffer.allocateDirect(definition.fixedRowBytes());
    row.put(
        definition.nullBitmapOffset() + definition.nullBitmapBytes() - 1,
        (byte) 0x80);
    assertEquals(false, definition.isValidRow(row));
  }

  @Test
  void assignsSixteenByteWideDecimalLanesWithoutMisaligningNarrowColumns() {
    TableSchema schema = new TableSchema();
    assertEquals(StatusCode.OK, schema.addBigint("id", false));
    assertEquals(StatusCode.OK, schema.addColumn(
        "money", SqlTypeDescriptor.decimal(38, 2), false));
    assertEquals(StatusCode.OK, schema.addColumn("enabled", SqlTypeDescriptor.BOOLEAN, false));
    assertEquals(StatusCode.OK, schema.addColumn(
        "scaled", SqlTypeDescriptor.decimal(22, 18), false));
    assertEquals(StatusCode.OK, schema.addColumn("day", SqlTypeDescriptor.DATE, false));
    TableDefinition definition = new TableDefinition();
    assertEquals(StatusCode.OK, definition.set(
        new RelationalSchemaGate(), 43, 0, TableDefinition.INDEX_NONE, -1, schema));

    assertEquals(0, definition.highValueOffset(1));
    assertEquals(8, definition.valueOffset(1));
    assertEquals(16, definition.valueOffset(2));
    assertEquals(24, definition.highValueOffset(3));
    assertEquals(32, definition.valueOffset(3));
    assertEquals(40, definition.valueOffset(4));
    assertEquals(48, definition.nullBitmapOffset());
    assertEquals(49, definition.fixedRowBytes());

    ByteBuffer row = ByteBuffer.allocateDirect(definition.fixedRowBytes());
    putWide(row, definition, 1,
        new BigInteger("12345678901234567890123456789012345678"));
    row.putLong(definition.valueOffset(2), 1);
    putWide(row, definition, 3, new BigInteger("1234567890123456789012"));
    row.putLong(definition.valueOffset(4), 1);
    assertTrue(definition.isValidRow(row));
  }

  private static TableSchema bigintSchema(int columns) {
    TableSchema schema = new TableSchema();
    for (int column = 0; column < columns; column++) {
      assertEquals(StatusCode.OK, schema.addBigint("c" + column, column != 0));
    }
    return schema;
  }

  private static TableDefinition transitionalDefinition(int columns) {
    TableDefinition definition = new TableDefinition();
    definition.available = true;
    definition.columnCount = columns;
    definition.typeDescriptors = new int[columns];
    definition.valueOffsets = new int[columns];
    Arrays.fill(definition.typeDescriptors, SqlTypeDescriptor.BIGINT);
    TableDefinitionRowLayout.deriveOffsets(definition);
    return definition;
  }

  private static void putWide(
      ByteBuffer row, TableDefinition definition, int column, BigInteger value) {
    row.putLong(definition.highValueOffset(column), value.shiftRight(Long.SIZE).longValue());
    row.putLong(definition.valueOffset(column), value.longValue());
  }
}
