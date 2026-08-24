package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Populates a reusable table definition from the supported schema sources. */
final class TableDefinitionStateLoader {
  private TableDefinitionStateLoader() { }

  static void setMinimal(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      CharSequence keyName,
      CharSequence valueName) {
    table.owner = schemaGate;
    table.tableId = id;
    table.columnCount = 2;
    table.notNullMask = 1;
    table.defaultMask = 0;
    table.typeDescriptors[0] = SqlTypeDescriptor.BIGINT;
    table.typeDescriptors[1] = SqlTypeDescriptor.BIGINT;
    table.checkMask = 0;
    table.checkNodeCount = 0;
    table.referenceMask = 0;
    table.uniqueIndexCount = 0;
    table.identity = false;
    if (valueIndexTableId > 0) {
      TableDefinitionIndexMutation.set(
          table, 0, valueIndexTableId, valueIndexState, 1, true, false);
      table.uniqueIndexCount = 1;
    }
    table.keyColumnName.set(keyName);
    table.valueColumnName.set(valueName);
    markAvailable(table, schemaGate);
  }

  static void setDefinition(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableDefinition schema,
      boolean unique) {
    table.owner = schemaGate;
    table.tableId = id;
    table.columnCount = schema.columnCount();
    table.notNullMask = schema.notNullMask;
    copyTypes(table, schema);
    copyDefaults(table, schema);
    copyChecks(table, schema);
    copyReferences(table, schema);
    table.identity = schema.identity;
    for (int index = 0; index < table.columnCount; index++) {
      table.setColumnName(index, schema.columnName(index));
    }
    copyIndexes(table, schema);
    if (valueIndexTableId > 0) {
      table.upsertIndex(valueIndexTableId, valueIndexState, indexColumn, unique);
    }
    markAvailable(table, schemaGate);
  }

  static void setSchema(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableSchema schema) {
    table.owner = schemaGate;
    table.tableId = id;
    table.columnCount = schema.columnCount();
    table.notNullMask = schema.notNullMask();
    table.defaultMask = schema.defaultMask();
    table.checkMask = schema.checkMask();
    table.checkNodeCount = 0;
    table.referenceMask = schema.referenceMask();
    table.identity = schema.hasIdentity();
    for (int index = 0; index < table.columnCount; index++) {
      table.defaultValues[index] = schema.defaultValue(index);
      table.defaultKinds[index] = (byte) schema.defaultKind(index);
      table.typeDescriptors[index] = schema.typeDescriptor(index);
      table.checkComparisons[index] = schema.checkComparison(index);
      table.checkValues[index] = schema.checkValue(index);
      table.checkTypeDescriptors[index] = schema.checkTypeDescriptor(index);
      table.checkNodeOffsets[index] = (byte) table.checkNodeCount;
      table.checkNodeCounts[index] = (byte) schema.checkNodeCount(index);
      table.checkNodeCount += schema.checkNodeCount(index);
      table.referenceTableIds[index] = schema.referenceTableId(index);
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
    table.uniqueIndexCount = 0;
    if (valueIndexTableId > 0) {
      TableDefinitionIndexMutation.set(
          table, 0, valueIndexTableId, valueIndexState, indexColumn, true, false);
      table.uniqueIndexCount = 1;
    }
    for (int index = 0; index < table.columnCount; index++) {
      table.setColumnName(index, schema.columnName(index));
    }
    markAvailable(table, schemaGate);
  }

  static void setPersisted(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      ByteBuffer source,
      int columnsOffset,
      int columns,
      long requiredNotNullMask,
      long requiredDefaultMask,
      int typeDescriptorsOffset,
      boolean requiredIdentity,
      long requiredCheckMask,
      int checksOffset,
      int checkValuesOffset,
      int checkTypeDescriptorsOffset,
      int checkNodeCountsOffset,
      int checkProgramOffset,
      long requiredReferenceMask,
      int referenceTableIdsOffset,
      int defaultsOffset,
      int defaultKindsOffset,
      int defaultTextOffset,
      int defaultTextLength) {
    table.owner = schemaGate;
    table.tableId = id;
    table.columnCount = columns;
    table.notNullMask = requiredNotNullMask;
    table.defaultMask = requiredDefaultMask;
    table.identity = requiredIdentity;
    table.checkMask = requiredCheckMask;
    table.checkNodeCount = 0;
    table.referenceMask = requiredReferenceMask;
    for (int index = 0; index < columns; index++) {
      table.defaultValues[index] = source.getLong(defaultsOffset + index * Long.BYTES);
      table.defaultKinds[index] = source.get(defaultKindsOffset + index);
      table.typeDescriptors[index] = source.getInt(
          typeDescriptorsOffset + index * Integer.BYTES);
      table.checkComparisons[index] = source.getInt(checksOffset + index * Integer.BYTES);
      table.checkValues[index] = source.getLong(checkValuesOffset + index * Long.BYTES);
      table.checkTypeDescriptors[index] = source.getInt(
          checkTypeDescriptorsOffset + index * Integer.BYTES);
      table.checkNodeOffsets[index] = (byte) table.checkNodeCount;
      table.checkNodeCounts[index] = source.get(checkNodeCountsOffset + index);
      table.checkNodeCount += Byte.toUnsignedInt(table.checkNodeCounts[index]);
      table.referenceTableIds[index] = source.getInt(
          referenceTableIdsOffset + index * Integer.BYTES);
    }
    int programOffset = checkProgramOffset;
    for (int node = 0; node < table.checkNodeCount; node++) {
      table.checkOperators[node] = source.get(programOffset);
      table.checkNodeDescriptors[node] = source.getInt(programOffset + 1);
      table.checkOperands[node] = source.getLong(programOffset + 5);
      programOffset += 13;
    }
    table.defaultTextBytesUsed = defaultTextLength;
    for (int index = 0; index < defaultTextLength; index++) {
      table.defaultTextBytes[index] = source.get(defaultTextOffset + index);
    }
    table.uniqueIndexCount = 0;
    if (valueIndexTableId > 0) {
      TableDefinitionIndexMutation.set(
          table, 0, valueIndexTableId, valueIndexState, indexColumn, true, false);
      table.uniqueIndexCount = 1;
    }
    int offset = columnsOffset;
    for (int index = 0; index < columns; index++) {
      int length = source.getInt(offset);
      offset += Integer.BYTES;
      table.setColumnName(index, source, offset, length);
      offset += length;
    }
    markAvailable(table, schemaGate);
  }

  static void reset(TableDefinition table) {
    table.owner = null;
    table.tableId = 0;
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      table.uniqueIndexTableIds[index] = 0;
      table.uniqueIndexStates[index] = TableDefinition.INDEX_NONE;
      table.uniqueIndexColumns[index] = 0;
      table.uniqueIndexes[index] = false;
      table.constraintIndexes[index] = false;
    }
    table.uniqueIndexCount = 0;
    table.keyColumnName.reset();
    table.valueColumnName.reset();
    for (int index = 0; index < table.columnCount - 2; index++) {
      table.additionalColumns[index].reset();
    }
    for (int index = 0; index < table.columnCount; index++) {
      table.typeDescriptors[index] = 0;
      table.defaultKinds[index] = 0;
      table.checkTypeDescriptors[index] = 0;
      table.checkNodeCounts[index] = 0;
      table.checkNodeOffsets[index] = 0;
    }
    table.columnCount = 0;
    table.notNullMask = 0;
    table.defaultMask = 0;
    table.defaultTextBytesUsed = 0;
    table.checkMask = 0;
    table.checkNodeCount = 0;
    table.referenceMask = 0;
    table.schemaVersion = 0;
    table.schemaAdmission = 0;
    table.available = false;
    table.identity = false;
  }

  private static void markAvailable(TableDefinition table, RelationalSchemaGate schemaGate) {
    table.schemaVersion = schemaGate.version();
    table.schemaAdmission = 0;
    table.available = true;
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

  private static void copyDefaults(TableDefinition target, TableDefinition source) {
    target.defaultMask = source.defaultMask;
    for (int index = 0; index < target.columnCount; index++) {
      target.defaultValues[index] = source.defaultValues[index];
      target.defaultKinds[index] = source.defaultKinds[index];
    }
    target.defaultTextBytesUsed = source.defaultTextBytesUsed;
    System.arraycopy(source.defaultTextBytes, 0, target.defaultTextBytes, 0, target.defaultTextBytesUsed);
  }

  private static void copyTypes(TableDefinition target, TableDefinition source) {
    for (int index = 0; index < target.columnCount; index++) {
      target.typeDescriptors[index] = source.typeDescriptors[index];
    }
  }

  private static void copyChecks(TableDefinition target, TableDefinition source) {
    target.checkMask = source.checkMask;
    target.checkNodeCount = source.checkNodeCount;
    for (int index = 0; index < target.columnCount; index++) {
      target.checkComparisons[index] = source.checkComparisons[index];
      target.checkValues[index] = source.checkValues[index];
      target.checkTypeDescriptors[index] = source.checkTypeDescriptors[index];
      target.checkNodeCounts[index] = source.checkNodeCounts[index];
      target.checkNodeOffsets[index] = source.checkNodeOffsets[index];
    }
    for (int node = 0; node < target.checkNodeCount; node++) {
      target.checkOperators[node] = source.checkOperators[node];
      target.checkOperands[node] = source.checkOperands[node];
      target.checkNodeDescriptors[node] = source.checkNodeDescriptors[node];
    }
  }

  private static void copyReferences(TableDefinition target, TableDefinition source) {
    target.referenceMask = source.referenceMask;
    for (int index = 0; index < target.columnCount; index++) {
      target.referenceTableIds[index] = source.referenceTableIds[index];
    }
  }
}
