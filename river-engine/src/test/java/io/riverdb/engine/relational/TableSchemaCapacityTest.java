package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class TableSchemaCapacityTest {
  @Test
  void rejectsLogicalNarrowColumnsBeforePhysicalRowExceedsBound() {
    TableSchema schema = new TableSchema();
    assertEquals(StatusCode.OK, schema.addBigint("id", false));
    StatusCode status = StatusCode.OK;
    while (status.isOk() && schema.columnCount() < SqlShapeLimits.MAX_TABLE_COLUMNS) {
      int column = schema.columnCount();
      status = schema.addColumn("c" + column, SqlTypeDescriptor.BOOLEAN, true);
    }

    int admitted = schema.columnCount();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, status);
    assertEquals(1_004, admitted);
    assertTrue(admitted < SqlShapeLimits.MAX_TABLE_COLUMNS);
    assertTrue(schema.maximumRowBytes() <= TableSchema.MAXIMUM_ROW_BYTES);
    TableDefinition definition = new TableDefinition();
    assertEquals(StatusCode.OK, definition.set(
        new RelationalSchemaGate(), 1, 0, TableDefinition.INDEX_NONE, -1, schema));
    assertTrue(definition.fixedRowBytes() <= TableSchema.MAXIMUM_ROW_BYTES);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        schema.addColumn("overflow_date", SqlTypeDescriptor.DATE, true));
    assertEquals(admitted, schema.columnCount());
  }

  @Test
  void retainsHighOrdinalFlagsAcrossGrowthAndReset() {
    TableSchema schema = bigintSchema(130);
    assertEquals(StatusCode.OK, schema.setReference(129, 77));
    assertEquals(StatusCode.OK, schema.setLastDefault(11));
    assertEquals(StatusCode.OK, schema.setLastCheck(TableSchema.CHECK_GREATER_THAN, 0));

    assertTrue((schema.referenceWord(2) & 1L << 1) != 0);
    assertTrue((schema.defaultWord(2) & 1L << 1) != 0);
    assertTrue((schema.checkWord(2) & 1L << 1) != 0);
    assertEquals(1L << 1, schema.referenceWord(2));
    assertEquals(1L << 1, schema.defaultWord(2));
    assertEquals(1L << 1, schema.checkWord(2));

    schema.reset();
    assertEquals(0, schema.columnCount());
    assertEquals(StatusCode.OK, schema.addBigint("replacement", false));
    assertEquals(0, schema.referenceWord(2));
    assertEquals(0, schema.defaultWord(2));
    assertEquals(0, schema.checkWord(2));
  }

  private static TableSchema bigintSchema(int columns) {
    TableSchema schema = new TableSchema();
    for (int column = 0; column < columns; column++) {
      assertEquals(StatusCode.OK, schema.addBigint("c" + column, column != 0));
    }
    return schema;
  }
}
