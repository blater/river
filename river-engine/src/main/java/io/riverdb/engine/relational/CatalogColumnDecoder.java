package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Decodes reusable column, default, and check-expression catalog scratch. */
final class CatalogColumnDecoder {
  private static final int COLUMN_FIXED_BYTES = 42;
  private static final int CHECK_NODE_BYTES = 13;

  private final TableSchema schema = new TableSchema();
  private final TableSchema.ColumnName decodedName = new TableSchema.ColumnName();
  private int[] comparisons = new int[0];
  private long[] values = new long[0];
  private int[] descriptors = new int[0];
  private int[] nodeCounts = new int[0];
  private byte[] operators = new byte[0];
  private long[] operands = new long[0];
  private int[] nodeDescriptors = new int[0];
  private int nextOffset;

  void reset() { schema.reset(); }

  StatusCode decode(
      ByteBuffer source, int bytes, int offset, int columns, int tableId, int flags) {
    StatusCode status = ensureColumnScratch(columns);
    if (!status.isOk()) return status;
    int totalNodes = 0;
    for (int column = 0; column < columns; column++) {
      if (offset > bytes - Integer.BYTES) return StatusCode.CORRUPTION;
      int columnNameBytes = source.getInt(offset);
      offset += Integer.BYTES;
      if (columnNameBytes <= 0 || columnNameBytes > TableSchema.MAXIMUM_NAME_LENGTH
          || offset > bytes - columnNameBytes - COLUMN_FIXED_BYTES) {
        return StatusCode.CORRUPTION;
      }
      decodedName.set(source, offset, columnNameBytes);
      offset += columnNameBytes;
      int descriptor = source.getInt(offset);
      int columnFlags = Byte.toUnsignedInt(source.get(offset + 4));
      int defaultKind = Byte.toUnsignedInt(source.get(offset + 5));
      long defaultValue = source.getLong(offset + 6);
      int defaultTextBytes = source.getInt(offset + 14);
      int comparison = source.getInt(offset + 18);
      long checkValue = source.getLong(offset + 22);
      int checkDescriptor = source.getInt(offset + 30);
      int nodes = source.getInt(offset + 34);
      int referenceTableId = source.getInt(offset + 38);
      offset += COLUMN_FIXED_BYTES;
      boolean defaultPresent = (columnFlags & CatalogTableEncoder.HAS_DEFAULT) != 0;
      boolean checkPresent = (columnFlags & CatalogTableEncoder.HAS_CHECK) != 0;
      boolean referencePresent = (columnFlags & CatalogTableEncoder.HAS_REFERENCE) != 0;
      boolean text = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
      if ((columnFlags & ~(CatalogTableEncoder.NULLABLE | CatalogTableEncoder.HAS_DEFAULT
              | CatalogTableEncoder.HAS_CHECK | CatalogTableEncoder.HAS_REFERENCE)) != 0
          || defaultTextBytes < 0 || offset > bytes - defaultTextBytes
          || !defaultPresent && (defaultKind != SqlDefaultKind.NONE || defaultValue != 0
              || defaultTextBytes != 0)
          || defaultPresent && (text ? defaultValue != 0 : defaultTextBytes != 0)
          || !checkPresent && (comparison != 0 || checkValue != 0 || checkDescriptor != 0
              || nodes != 0)
          || checkPresent && (nodes <= 0 || nodes > SqlShapeLimits.MAX_EXPRESSION_NODES - totalNodes)
          || !referencePresent && referenceTableId != 0
          || referencePresent && (referenceTableId <= 0
              || referenceTableId > RelationalKey.MAXIMUM_TABLE_ID || referenceTableId == tableId)) {
        return StatusCode.CORRUPTION;
      }
      status = schema.addColumn(
          decodedName, descriptor, (columnFlags & CatalogTableEncoder.NULLABLE) != 0);
      if (!status.isOk()) return StatusCode.CORRUPTION;
      if (defaultPresent) {
        status = applyDefault(source, offset, defaultTextBytes, text, defaultKind, defaultValue);
        if (!status.isOk()) return StatusCode.CORRUPTION;
      }
      offset += defaultTextBytes;
      if (referencePresent) {
        status = schema.setReference(column, referenceTableId);
        if (!status.isOk()) return StatusCode.CORRUPTION;
      }
      comparisons[column] = comparison;
      values[column] = checkValue;
      descriptors[column] = checkDescriptor;
      nodeCounts[column] = nodes;
      totalNodes += nodes;
    }
    if ((flags & CatalogTableEncoder.IDENTITY) != 0) {
      status = schema.setPrimaryKeyIdentity();
      if (!status.isOk()) return StatusCode.CORRUPTION;
    }
    for (int column = 0; column < columns; column++) {
      int nodes = nodeCounts[column];
      if (nodes == 0) continue;
      status = ensureNodeScratch(nodes);
      if (!status.isOk()) return status;
      if (offset > bytes - nodes * CHECK_NODE_BYTES) return StatusCode.CORRUPTION;
      for (int node = 0; node < nodes; node++) {
        operators[node] = source.get(offset);
        nodeDescriptors[node] = source.getInt(offset + 1);
        operands[node] = source.getLong(offset + 5);
        offset += CHECK_NODE_BYTES;
      }
      status = schema.setCheck(
          column,
          comparisons[column],
          descriptors[column],
          values[column],
          nodes,
          operators,
          operands,
          nodeDescriptors);
      if (!status.isOk()) return StatusCode.CORRUPTION;
    }
    if (!schema.isValid()) return StatusCode.CORRUPTION;
    nextOffset = offset;
    return StatusCode.OK;
  }

  TableSchema schema() { return schema; }

  int nextOffset() { return nextOffset; }

  private StatusCode applyDefault(
      ByteBuffer source,
      int offset,
      int textBytes,
      boolean text,
      int kind,
      long value) {
    if (text) {
      if (kind != SqlDefaultKind.LITERAL) return StatusCode.INVALID_EXTERNAL_INPUT;
      source.position(offset).limit(offset + textBytes);
      StatusCode status = schema.setLastTextDefault(source);
      source.position(0).limit(source.capacity());
      return status;
    }
    return SqlDefaultKind.isCurrent(kind)
        ? schema.setLastCurrentDefault(kind)
        : kind == SqlDefaultKind.LITERAL ? schema.setLastDefault(value)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode ensureColumnScratch(int required) {
    if (required <= comparisons.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        comparisons.length, required, SqlShapeLimits.MAX_TABLE_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      int[] grownComparisons = new int[capacity];
      long[] grownValues = new long[capacity];
      int[] grownDescriptors = new int[capacity];
      int[] grownNodeCounts = new int[capacity];
      comparisons = grownComparisons;
      values = grownValues;
      descriptors = grownDescriptors;
      nodeCounts = grownNodeCounts;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode ensureNodeScratch(int required) {
    if (required <= operators.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        operators.length, required, SqlShapeLimits.MAX_EXPRESSION_NODES, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      byte[] grownOperators = new byte[capacity];
      long[] grownOperands = new long[capacity];
      int[] grownDescriptors = new int[capacity];
      operators = grownOperators;
      operands = grownOperands;
      nodeDescriptors = grownDescriptors;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
