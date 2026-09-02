package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Populates a reusable table definition after all required storage has been admitted. */
final class TableDefinitionStateLoader {
  private TableDefinitionStateLoader() { }

  static StatusCode setMinimal(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      CharSequence keyName,
      CharSequence valueName) {
    int indexes = valueIndexTableId > 0 ? 1 : 0;
    StatusCode status = prepare(table, 2, 0, 0, indexes);
    if (!status.isOk()) return status;
    table.owner = schemaGate;
    table.tableId = id;
    table.columnCount = 2;
    table.notNullColumns.set(0);
    table.typeDescriptors[0] = SqlTypeDescriptor.BIGINT;
    table.typeDescriptors[1] = SqlTypeDescriptor.BIGINT;
    TableDefinitionRowLayout.deriveOffsets(table);
    table.columnNames[0].set(keyName);
    table.columnNames[1].set(valueName);
    if (indexes != 0) {
      TableDefinitionIndexMutation.set(
          table, 0, valueIndexTableId, valueIndexState, 1, true, false);
      table.uniqueIndexCount = 1;
    }
    markAvailable(table, schemaGate);
    return StatusCode.OK;
  }

  static StatusCode setDefinition(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableDefinition schema,
      boolean unique) {
    int indexes = schema.uniqueIndexCount;
    if (valueIndexTableId > 0
        && requiresIndexSlot(schema, valueIndexTableId, indexColumn)) {
      indexes++;
    }
    StatusCode status = prepare(
        table,
        schema.columnCount,
        schema.checkNodeCount,
        schema.defaultTextBytesUsed,
        indexes);
    if (!status.isOk()) return status;
    table.owner = schemaGate;
    table.tableId = id;
    table.columnCount = schema.columnCount;
    copyBits(table, schema);
    copyColumns(table, schema);
    copyChecks(table, schema);
    copyText(table, schema);
    copyIndexes(table, schema);
    table.identity = schema.identity;
    table.primaryIndexColumn = schema.identity ? 0 : -1;
    TableDefinitionRowLayout.deriveOffsets(table);
    if (valueIndexTableId > 0) {
      status = table.upsertIndex(
          valueIndexTableId, valueIndexState, indexColumn, unique);
      if (!status.isOk()) {
        reset(table);
        return status;
      }
    }
    markAvailable(table, schemaGate);
    return StatusCode.OK;
  }

  static StatusCode setSchema(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableSchema schema) {
    int indexes = valueIndexTableId > 0 ? 1 : 0;
    StatusCode status = prepare(
        table,
        schema.columnCount(),
        schema.checkNodeCount(),
        schema.defaultTextBytes(),
        indexes);
    if (!status.isOk()) return status;
    table.owner = schemaGate;
    table.tableId = id;
    table.columnCount = schema.columnCount();
    copyBits(table, schema);
    for (int index = 0; index < table.columnCount; index++) {
      table.defaultValues[index] = schema.defaultValue(index);
      table.defaultKinds[index] = (byte) schema.defaultKind(index);
      table.typeDescriptors[index] = schema.typeDescriptor(index);
      table.checkComparisons[index] = schema.checkComparison(index);
      table.checkValues[index] = schema.checkValue(index);
      table.checkTypeDescriptors[index] = schema.checkTypeDescriptor(index);
      table.checkNodeOffsets[index] = table.checkNodeCount;
      table.checkNodeCounts[index] = schema.checkNodeCount(index);
      table.checkNodeCount += schema.checkNodeCount(index);
      table.referenceTableIds[index] = schema.referenceTableId(index);
      table.columnNames[index].set(schema.columnName(index));
    }
    for (int node = 0; node < table.checkNodeCount; node++) {
      table.checkOperators[node] = (byte) schema.checkOperator(node);
      table.checkOperands[node] = schema.checkOperand(node);
      table.checkNodeDescriptors[node] = schema.checkNodeDescriptor(node);
    }
    table.defaultTextBytesUsed = schema.defaultTextBytes();
    for (int index = 0; index < table.defaultTextBytesUsed; index++) {
      table.defaultTextBytes[index] = schema.defaultTextByte(index);
    }
    table.identity = schema.hasIdentity();
    table.primaryIndexColumn = schema.hasIdentity() ? 0 : -1;
    TableDefinitionRowLayout.deriveOffsets(table);
    if (indexes != 0) {
      TableDefinitionIndexMutation.set(
          table, 0, valueIndexTableId, valueIndexState, indexColumn, true, false);
      table.uniqueIndexCount = 1;
    }
    markAvailable(table, schemaGate);
    return StatusCode.OK;
  }

  static void reset(TableDefinition table) {
    table.owner = null;
    table.tableId = 0;
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      TableDefinitionIndexMutation.set(
          table, index, 0, TableDefinition.INDEX_NONE, 0, false, false);
    }
    for (int index = 0; index < table.columnCount; index++) {
      table.columnNames[index].reset();
      table.typeDescriptors[index] = 0;
      table.valueOffsets[index] = 0;
      table.defaultKinds[index] = 0;
      table.checkTypeDescriptors[index] = 0;
      table.checkNodeCounts[index] = 0;
      table.checkNodeOffsets[index] = 0;
      table.referenceTableIds[index] = 0;
    }
    TableDefinitionCapacity.clearBits(table);
    table.uniqueIndexCount = 0;
    table.columnCount = 0;
    table.defaultTextBytesUsed = 0;
    table.checkNodeCount = 0;
    table.layoutColumns = 0;
    table.schemaVersion = 0;
    table.schemaAdmission = 0;
    table.durableSchemaId = 0;
    table.durableRowLayoutId = 0;
    table.durableCatalogGeneration = 0;
    table.available = false;
    table.identity = false;
    table.descriptorView = false;
    table.primaryIndexColumn = -1;
  }

  private static StatusCode prepare(
      TableDefinition table,
      int columns,
      int nodes,
      int textBytes,
      int indexes) {
    StatusCode status = TableDefinitionCapacity.ensure(
        table, columns, nodes, textBytes, indexes);
    if (status.isOk()) status = TableDefinitionCapacity.ensureNames(table, columns);
    if (!status.isOk()) return status;
    reset(table);
    return StatusCode.OK;
  }

  private static void copyBits(TableDefinition target, TableSchema source) {
    int words = (source.columnCount() + Long.SIZE - 1) / Long.SIZE;
    for (int word = 0; word < words; word++) {
      target.notNullColumns.setWord(word, source.notNullWord(word));
      target.defaultColumns.setWord(word, source.defaultWord(word));
      target.checkColumns.setWord(word, source.checkWord(word));
      target.referenceColumns.setWord(word, source.referenceWord(word));
    }
  }

  private static void copyBits(TableDefinition target, TableDefinition source) {
    for (int word = 0; word < source.bitmapWordCount(); word++) {
      target.notNullColumns.setWord(word, source.notNullWord(word));
      target.defaultColumns.setWord(word, source.defaultWord(word));
      target.checkColumns.setWord(word, source.checkWord(word));
      target.referenceColumns.setWord(word, source.referenceWord(word));
    }
  }

  private static void copyColumns(TableDefinition target, TableDefinition source) {
    for (int index = 0; index < target.columnCount; index++) {
      target.defaultValues[index] = source.defaultValues[index];
      target.defaultKinds[index] = source.defaultKinds[index];
      target.typeDescriptors[index] = source.typeDescriptors[index];
      target.checkComparisons[index] = source.checkComparisons[index];
      target.checkValues[index] = source.checkValues[index];
      target.checkTypeDescriptors[index] = source.checkTypeDescriptors[index];
      target.checkNodeCounts[index] = source.checkNodeCounts[index];
      target.checkNodeOffsets[index] = source.checkNodeOffsets[index];
      target.referenceTableIds[index] = source.referenceTableIds[index];
      target.columnNames[index].set(source.columnNames[index]);
    }
  }

  private static void copyChecks(TableDefinition target, TableDefinition source) {
    target.checkNodeCount = source.checkNodeCount;
    for (int node = 0; node < target.checkNodeCount; node++) {
      target.checkOperators[node] = source.checkOperators[node];
      target.checkOperands[node] = source.checkOperands[node];
      target.checkNodeDescriptors[node] = source.checkNodeDescriptors[node];
    }
  }

  private static void copyText(TableDefinition target, TableDefinition source) {
    target.defaultTextBytesUsed = source.defaultTextBytesUsed;
    System.arraycopy(
        source.defaultTextBytes,
        0,
        target.defaultTextBytes,
        0,
        target.defaultTextBytesUsed);
  }

  private static void copyIndexes(TableDefinition target, TableDefinition source) {
    target.uniqueIndexCount = source.uniqueIndexCount;
    for (int index = 0; index < target.uniqueIndexCount; index++) {
      TableDefinitionIndexMutation.set(
          target,
          index,
          source.uniqueIndexTableIds[index],
          source.uniqueIndexStates[index],
          source.uniqueIndexColumns[index],
          source.uniqueIndexes[index],
          source.constraintIndexes[index]);
    }
  }

  private static void markAvailable(
      TableDefinition table, RelationalSchemaGate schemaGate) {
    table.schemaVersion = schemaGate.version();
    table.schemaAdmission = 0;
    table.available = true;
  }

  private static boolean requiresIndexSlot(
      TableDefinition table, int tableId, int column) {
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      if (table.uniqueIndexTableIds[index] == tableId
          || table.uniqueIndexColumns[index] == column) {
        return false;
      }
    }
    return true;
  }
}
