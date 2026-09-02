package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Caller-owned reusable decoder for actual-count catalog table records. */
final class CatalogTableDecoder {
  private static final int COLUMN_FIXED_BYTES = 42;
  private static final int INDEX_BYTES = 16;
  private static final int CHECK_NODE_BYTES = 13;

  private final TableSchema schema = new TableSchema();
  private final TableSchema.ColumnName decodedName = new TableSchema.ColumnName();
  private int[] comparisons = new int[0];
  private long[] values = new long[0];
  private int[] descriptors = new int[0];
  private int[] nodeCounts = new int[0];
  private byte[] columnFlags = new byte[0];
  private byte[] operators = new byte[0];
  private long[] operands = new long[0];
  private int[] nodeDescriptors = new int[0];

  StatusCode decode(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result,
      long expectedMagic) {
    if (source == null || scratch == null || expectedName == null
        || schemaGate == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) return status;
    long actualMagic = longAt(scratch, source.length(), 0, 0);
    if (actualMagic != expectedMagic) {
      return knownCatalogMagic(actualMagic)
          ? StatusCode.CONFLICT : StatusCode.CORRUPTION;
    }
    return decodeCopied(
        source.length(), scratch, expectedName, schemaGate, result, expectedMagic);
  }

  StatusCode decodeForScan(
      HeapRowResult source,
      ByteBuffer scratch,
      RelationalSchemaGate schemaGate,
      TableSchema.ColumnName name,
      TableDefinition result) {
    if (source == null || scratch == null || schemaGate == null || name == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) return status;
    int bytes = source.length();
    long magic = longAt(scratch, bytes, 0, 0);
    if (magic != CatalogRecord.TABLE_MAGIC && magic != CatalogRecord.DROPPING_TABLE_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = intAt(scratch, bytes, 16, -1);
    if (intAt(scratch, bytes, 8, -1) != CatalogRecord.TABLE_VERSION
        || nameBytes <= 0 || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || CatalogTableEncoder.HEADER_BYTES > bytes - nameBytes) {
      return StatusCode.CORRUPTION;
    }
    name.set(scratch, CatalogTableEncoder.HEADER_BYTES, nameBytes);
    if (!RelationalKey.validName(name)) return StatusCode.CORRUPTION;
    return decodeCopied(bytes, scratch, name, schemaGate, result, magic);
  }

  private StatusCode decodeCopied(
      int bytes,
      ByteBuffer source,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result,
      long expectedMagic) {
    result.reset();
    schema.reset();
    if (bytes < CatalogTableEncoder.HEADER_BYTES
        || longAt(source, bytes, 0, 0) != expectedMagic
        || intAt(source, bytes, 8, -1) != CatalogRecord.TABLE_VERSION) {
      return StatusCode.CORRUPTION;
    }
    int tableId = intAt(source, bytes, 12, -1);
    int nameBytes = intAt(source, bytes, 16, -1);
    int columns = intAt(source, bytes, 20, -1);
    int indexes = intAt(source, bytes, 24, -1);
    int flags = intAt(source, bytes, 28, -1);
    if (tableId <= 0 || tableId > RelationalKey.MAXIMUM_TABLE_ID
        || nameBytes <= 0 || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || columns < 2 || columns > SqlShapeLimits.MAX_TABLE_COLUMNS
        || indexes < 0 || indexes > SqlShapeLimits.MAX_SECONDARY_INDEXES
        || (flags & ~CatalogTableEncoder.IDENTITY) != 0
        || CatalogTableEncoder.HEADER_BYTES > bytes - nameBytes) {
      return StatusCode.CORRUPTION;
    }
    if (!nameMatches(
        source, CatalogTableEncoder.HEADER_BYTES, nameBytes, expectedName)) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = ensureColumnScratch(columns);
    if (!status.isOk()) return status;
    int offset = CatalogTableEncoder.HEADER_BYTES + nameBytes;
    int totalNodes = 0;
    for (int column = 0; column < columns; column++) {
      if (offset > bytes - Integer.BYTES) return corruption(result);
      int columnNameBytes = source.getInt(offset);
      offset += Integer.BYTES;
      if (columnNameBytes <= 0 || columnNameBytes > TableSchema.MAXIMUM_NAME_LENGTH
          || offset > bytes - columnNameBytes - COLUMN_FIXED_BYTES) return corruption(result);
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
        return corruption(result);
      }
      status = schema.addColumn(
          decodedName, descriptor, (columnFlags & CatalogTableEncoder.NULLABLE) != 0);
      if (!status.isOk()) return corruption(result);
      if (defaultPresent) {
        status = applyDefault(source, offset, defaultTextBytes, text, defaultKind, defaultValue);
        if (!status.isOk()) return corruption(result);
      }
      offset += defaultTextBytes;
      if (referencePresent) {
        status = schema.setReference(column, referenceTableId);
        if (!status.isOk()) return corruption(result);
      }
      comparisons[column] = comparison;
      values[column] = checkValue;
      descriptors[column] = checkDescriptor;
      nodeCounts[column] = nodes;
      this.columnFlags[column] = (byte) columnFlags;
      totalNodes += nodes;
    }
    if ((flags & CatalogTableEncoder.IDENTITY) != 0) {
      status = schema.setPrimaryKeyIdentity();
      if (!status.isOk()) return corruption(result);
    }
    for (int column = 0; column < columns; column++) {
      int nodes = nodeCounts[column];
      if (nodes == 0) continue;
      status = ensureNodeScratch(nodes);
      if (!status.isOk()) return status;
      if (offset > bytes - nodes * CHECK_NODE_BYTES) return corruption(result);
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
      if (!status.isOk()) return corruption(result);
    }
    if (!schema.isValid()) return corruption(result);
    status = result.set(
        schemaGate, tableId, 0, TableDefinition.INDEX_NONE, -1, schema);
    if (!status.isOk()) return status;
    for (int slot = 0; slot < indexes; slot++) {
      if (offset > bytes - INDEX_BYTES) return corruption(result);
      int indexTableId = source.getInt(offset);
      int state = source.getInt(offset + 4);
      int column = source.getInt(offset + 8);
      int indexFlags = source.getInt(offset + 12);
      offset += INDEX_BYTES;
      if (!validIndex(result, tableId, indexTableId, state, column, indexFlags)
          || duplicateIndex(result, indexTableId, column)) return corruption(result);
      status = result.upsertIndex(
          indexTableId, state, column, (indexFlags & 1) != 0, (indexFlags & 2) != 0);
      if (!status.isOk()) return corruption(result);
    }
    return offset == bytes && CatalogTableColumnValidator.validColumns(result)
        ? StatusCode.OK : corruption(result);
  }

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

  private static boolean validIndex(
      TableDefinition table,
      int tableId,
      int indexTableId,
      int state,
      int column,
      int flags) {
    return indexTableId > 0 && indexTableId <= RelationalKey.MAXIMUM_TABLE_ID
        && indexTableId != tableId && column > 0 && column < table.columnCount()
        && (state == TableDefinition.INDEX_BUILDING || state == TableDefinition.INDEX_READY
            || state == TableDefinition.INDEX_DROPPING)
        && (flags & ~3) == 0
        && ((flags & 3) != 2 || table.hasReference(column));
  }

  private static boolean duplicateIndex(TableDefinition table, int tableId, int column) {
    for (int slot = 0; slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexTableId(slot) == tableId || table.uniqueIndexColumn(slot) == column) {
        return true;
      }
    }
    return false;
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
      byte[] grownFlags = new byte[capacity];
      comparisons = grownComparisons;
      values = grownValues;
      descriptors = grownDescriptors;
      nodeCounts = grownNodeCounts;
      columnFlags = grownFlags;
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

  private static boolean nameMatches(
      ByteBuffer source, int offset, int length, CharSequence expected) {
    if (expected.length() != length) return false;
    for (int index = 0; index < length; index++) {
      if (Byte.toUnsignedInt(source.get(offset + index)) != expected.charAt(index)) return false;
    }
    return true;
  }

  private static int intAt(ByteBuffer source, int bytes, int offset, int fallback) {
    return offset <= bytes - Integer.BYTES ? source.getInt(offset) : fallback;
  }

  private static long longAt(ByteBuffer source, int bytes, int offset, long fallback) {
    return offset <= bytes - Long.BYTES ? source.getLong(offset) : fallback;
  }

  private static boolean knownCatalogMagic(long magic) {
    return CatalogSequenceCodec.matchesMagic(magic)
        || CatalogViewCodec.matchesMagic(magic)
        || magic == CatalogRecord.TABLE_MAGIC
        || magic == CatalogRecord.DROPPING_TABLE_MAGIC
        || CatalogIndexCodec.matchesMagic(magic);
  }

  private static StatusCode corruption(TableDefinition result) {
    result.reset();
    return StatusCode.CORRUPTION;
  }
}
