package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Current catalog encoding for bounded relational schemas and index-build state. */
final class CatalogRecord {
  private static final CatalogTableScanDecoder TABLE_SCAN_DECODER =
      new CatalogTableScanDecoder();
  static final int MAXIMUM_BYTES =
      240 + TableSchema.MAXIMUM_COLUMNS * Long.BYTES
          + TableSchema.MAXIMUM_COLUMNS
          + 44 + TableSchema.MAXIMUM_CHECK_NODES * 13
          + TableDefinition.MAXIMUM_INDEXES * 16
          + 64 + TableSchema.MAXIMUM_COLUMNS * (Integer.BYTES + 64)
          + TableSchema.MAXIMUM_ROW_BYTES;

  static final long TABLE_MAGIC = 0x524956455254424cL; // RIVERTBL
  static final long DROPPING_TABLE_MAGIC = 0x524956455244524fL; // RIVERDRO
  static final int TABLE_VERSION = 14;
  static final int TABLE_CHECK_MASK_OFFSET = 60;
  static final int TABLE_CHECKS_OFFSET = 68;
  static final int TABLE_CHECK_VALUES_OFFSET = 104;
  static final int TABLE_DEFAULTS_OFFSET = 168;
  static final int TABLE_REFERENCE_MASK_OFFSET = 232;
  static final int TABLE_REFERENCE_IDS_OFFSET = 240;
  static final int TABLE_TYPE_DESCRIPTORS_OFFSET =
      TABLE_REFERENCE_IDS_OFFSET + TableSchema.MAXIMUM_COLUMNS * Integer.BYTES;
  static final int TABLE_DEFAULT_KINDS_OFFSET =
      TABLE_TYPE_DESCRIPTORS_OFFSET + TableSchema.MAXIMUM_COLUMNS * Integer.BYTES;
  static final int TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET =
      TABLE_DEFAULT_KINDS_OFFSET + TableSchema.MAXIMUM_COLUMNS;
  static final int TABLE_CHECK_NODE_COUNTS_OFFSET =
      TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET
          + TableSchema.MAXIMUM_COLUMNS * Integer.BYTES;
  static final int TABLE_CHECK_NODE_TOTAL_OFFSET =
      TABLE_CHECK_NODE_COUNTS_OFFSET + TableSchema.MAXIMUM_COLUMNS;
  static final int TABLE_INDEXES_OFFSET =
      TABLE_CHECK_NODE_TOTAL_OFFSET + Integer.BYTES;

  private CatalogRecord() {
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      CharSequence name) {
    CatalogTableEncoder.encode(target, tableId, uniqueValueIndexTableId, name);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name) {
    CatalogTableEncoder.encode(
        target, tableId, uniqueValueIndexTableId, uniqueValueIndexState, name);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        name,
        keyColumnName,
        valueColumnName);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableSchema schema) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema,
        true);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema,
        unique,
        false);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique,
      boolean constraint) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema,
        unique,
        constraint);
  }

  static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result) {
    return decodeTable(
        source, scratch, expectedName, schemaGate, result, TABLE_MAGIC);
  }

  static StatusCode decodeDroppingTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result) {
    return decodeTable(
        source, scratch, expectedName, schemaGate, result, DROPPING_TABLE_MAGIC);
  }

  static boolean isDroppingTable(HeapRowResult source, ByteBuffer scratch) {
    scratch.clear();
    return source.copyTo(scratch).isOk()
        && source.length() >= Long.BYTES
        && scratch.getLong(0) == DROPPING_TABLE_MAGIC;
  }

  static StatusCode decodeTableForScan(
      HeapRowResult source,
      ByteBuffer scratch,
      RelationalSchemaGate schemaGate,
      TableSchema.ColumnName name,
      TableDefinition result) {
    return TABLE_SCAN_DECODER.decode(
        source, scratch, schemaGate, name, result);
  }

  static void encodeDroppingTable(
      ByteBuffer target,
      int tableId,
      CharSequence name,
      TableDefinition schema) {
    CatalogTableEncoder.encodeDropping(target, tableId, name, schema);
  }

  private static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result,
      long expectedMagic) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    long actualMagic = longAt(scratch, source.length(), 0, 0);
    if (actualMagic != expectedMagic) {
      return knownCatalogMagic(actualMagic)
          ? StatusCode.CONFLICT : StatusCode.CORRUPTION;
    }
    int version = intAt(scratch, source.length(), 8, -1);
    int nameBytes = intAt(scratch, source.length(), 16, -1);
    int columnCount = intAt(scratch, source.length(), 20, -1);
    int indexCount = intAt(scratch, source.length(), 24, -1);
    long notNullMask = longAt(scratch, source.length(), 28, -1);
    long defaultMask = longAt(scratch, source.length(), 36, -1);
    long defaultTextBytes = longAt(scratch, source.length(), 44, -1);
    long identityMask = longAt(scratch, source.length(), 52, -1);
    long checkMask = longAt(
        scratch, source.length(), TABLE_CHECK_MASK_OFFSET, -1);
    long referenceMask = longAt(
        scratch, source.length(), TABLE_REFERENCE_MASK_OFFSET, -1);
    int checkNodeTotal = intAt(
        scratch, source.length(), TABLE_CHECK_NODE_TOTAL_OFFSET, -1);
    boolean validIndexCount = indexCount >= 0
        && indexCount <= TableDefinition.MAXIMUM_INDEXES;
    int nameOffset = validIndexCount
        ? TABLE_INDEXES_OFFSET + indexCount * 16 : -1;
    int columnsOffset = nameOffset < 0 ? -1 : nameOffset + nameBytes;
    int expectedBytes = tableRecordBytes(
        scratch,
        source.length(),
        validIndexCount,
        nameBytes,
        nameOffset,
        columnsOffset,
        columnCount,
        defaultTextBytes,
        checkNodeTotal);
    if (!validTableRecord(
            scratch,
            source.length(),
            expectedMagic,
            version,
            validIndexCount,
            nameBytes,
            nameOffset,
            columnCount,
            expectedBytes,
            notNullMask,
            defaultMask,
            defaultTextBytes,
            identityMask,
            checkMask,
            referenceMask,
            expectedName.length(),
            checkNodeTotal,
            result.checkValidationStack())) {
      return StatusCode.CORRUPTION;
    }
    if (!tableNameMatches(scratch, nameOffset, nameBytes, expectedName)) {
      return StatusCode.CONFLICT;
    }
    result.set(
        schemaGate,
        scratch.getInt(12),
        0,
        TableDefinition.INDEX_NONE,
        -1,
        scratch,
        columnsOffset,
        columnCount,
        notNullMask,
        defaultMask,
        TABLE_TYPE_DESCRIPTORS_OFFSET,
        identityMask == 1,
        checkMask,
        TABLE_CHECKS_OFFSET,
        TABLE_CHECK_VALUES_OFFSET,
        TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET,
        TABLE_CHECK_NODE_COUNTS_OFFSET,
        expectedBytes - checkNodeTotal * 13,
        referenceMask,
        TABLE_REFERENCE_IDS_OFFSET,
        TABLE_DEFAULTS_OFFSET,
        TABLE_DEFAULT_KINDS_OFFSET,
        expectedBytes - checkNodeTotal * 13 - (int) defaultTextBytes,
        (int) defaultTextBytes);
    status = decodeTableIndexes(
        scratch, indexCount, columnCount, referenceMask, result);
    if (!status.isOk() || !validColumns(result)) {
      result.reset();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    return StatusCode.OK;
  }

  private static int intAt(
      ByteBuffer source,
      int sourceBytes,
      int offset,
      int fallback) {
    return offset <= sourceBytes - Integer.BYTES ? source.getInt(offset) : fallback;
  }

  private static long longAt(
      ByteBuffer source,
      int sourceBytes,
      int offset,
      long fallback) {
    return offset <= sourceBytes - Long.BYTES ? source.getLong(offset) : fallback;
  }

  private static int tableRecordBytes(
      ByteBuffer source,
      int sourceBytes,
      boolean validIndexCount,
      int nameBytes,
      int nameOffset,
      int columnsOffset,
      int columnCount,
      long defaultTextBytes,
      int checkNodeTotal) {
    if (!validIndexCount
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || columnsOffset < nameOffset
        || columnsOffset > sourceBytes
        || columnCount < 2
        || columnCount > TableSchema.MAXIMUM_COLUMNS
        || checkNodeTotal < 0
        || checkNodeTotal > TableSchema.MAXIMUM_CHECK_NODES) {
      return -1;
    }
    int expectedBytes = columnsOffset;
    for (int index = 0; index < columnCount; index++) {
      if (expectedBytes > sourceBytes - Integer.BYTES) {
        return -1;
      }
      int columnBytes = source.getInt(expectedBytes);
      if (columnBytes <= 0
          || columnBytes > TableSchema.MAXIMUM_NAME_LENGTH
          || expectedBytes > sourceBytes - Integer.BYTES - columnBytes) {
        return -1;
      }
      expectedBytes += Integer.BYTES + columnBytes;
    }
    if (defaultTextBytes < 0
        || defaultTextBytes > TableSchema.MAXIMUM_ROW_BYTES
        || expectedBytes > sourceBytes - defaultTextBytes
            - checkNodeTotal * 13L) {
      return -1;
    }
    return expectedBytes + (int) defaultTextBytes + checkNodeTotal * 13;
  }

  private static boolean validTableRecord(
      ByteBuffer source,
      int sourceBytes,
      long expectedMagic,
      int version,
      boolean validIndexCount,
      int nameBytes,
      int nameOffset,
      int columnCount,
      int expectedBytes,
      long notNullMask,
      long defaultMask,
      long defaultTextBytes,
      long identityMask,
      long checkMask,
      long referenceMask,
      int expectedNameBytes,
      int checkNodeTotal,
      int[] checkStack) {
    int tableId = source.getInt(12);
    long columnMask = (1L << columnCount) - 1;
    return version == TABLE_VERSION
        && validIndexCount
        && nameOffset >= 0
        && sourceBytes >= nameOffset + 1
        && sourceBytes == expectedBytes
        && source.getLong(0) == expectedMagic
        && tableId > 0
        && tableId <= RelationalKey.MAXIMUM_TABLE_ID
        && nameBytes > 0
        && nameBytes <= TableSchema.MAXIMUM_NAME_LENGTH
        && columnCount >= 2
        && columnCount <= TableSchema.MAXIMUM_COLUMNS
        && (notNullMask & 1) != 0
        && (notNullMask & ~columnMask) == 0
        && (defaultMask & 1) == 0
        && (defaultMask & ~columnMask) == 0
        && validTypeDescriptors(source, columnCount)
        && validDefaults(
            source,
            columnCount,
            defaultMask,
            (int) defaultTextBytes,
            expectedBytes - checkNodeTotal * 13 - (int) defaultTextBytes)
        && (identityMask == 0 || identityMask == 1)
        && (checkMask & ~columnMask) == 0
        && validChecks(source, sourceBytes, columnCount, checkMask, checkStack)
        && (referenceMask & 1) == 0
        && (referenceMask & ~columnMask) == 0
        && validReferences(source, columnCount, referenceMask, tableId)
        && nameBytes == expectedNameBytes;
  }

  private static boolean tableNameMatches(
      ByteBuffer source,
      int offset,
      int bytes,
      CharSequence expected) {
    for (int index = 0; index < bytes; index++) {
      if (Byte.toUnsignedInt(source.get(offset + index)) != expected.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  private static StatusCode decodeTableIndexes(
      ByteBuffer source,
      int indexCount,
      int columnCount,
      long referenceMask,
      TableDefinition result) {
    int buildingIndexes = 0;
    for (int index = 0; index < indexCount; index++) {
      int offset = TABLE_INDEXES_OFFSET + index * 16;
      int tableId = source.getInt(offset);
      int state = source.getInt(offset + 4);
      int column = source.getInt(offset + 8);
      int flags = source.getInt(offset + 12);
      if (!validIndex(
          source, index, tableId, state, column, flags, columnCount, referenceMask)) {
        return StatusCode.CORRUPTION;
      }
      if (state == TableDefinition.INDEX_BUILDING && ++buildingIndexes > 1) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = result.upsertIndex(
          tableId, state, column, (flags & 1) != 0, (flags & 2) != 0);
      if (status == StatusCode.CONFLICT
          || status == StatusCode.RESOURCE_EXHAUSTED
          || status == StatusCode.INVALID_EXTERNAL_INPUT) {
        return StatusCode.CORRUPTION;
      }
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private static boolean validIndex(
      ByteBuffer source,
      int slot,
      int tableId,
      int state,
      int column,
      int flags,
      int columnCount,
      long referenceMask) {
    return tableId > 0
        && tableId <= RelationalKey.MAXIMUM_TABLE_ID
        && tableId != source.getInt(12)
        && (state == TableDefinition.INDEX_BUILDING
            || state == TableDefinition.INDEX_READY
            || state == TableDefinition.INDEX_DROPPING)
        && column > 0
        && column < columnCount
        && (flags & ~3) == 0
        && ((flags & 3) != 2 || (referenceMask & 1L << column) != 0)
        && !duplicateIndex(source, slot, tableId, column);
  }

  private static boolean validColumns(TableDefinition table) {
    for (int index = 0; index < table.columnCount(); index++) {
      CharSequence name = table.columnName(index);
      if (!RelationalKey.validName(name)) {
        return false;
      }
      for (int prior = 0; prior < index; prior++) {
        if (sameName(name, table.columnName(prior))) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean validChecks(
      ByteBuffer source,
      int sourceBytes,
      int columns,
      long checkMask,
      int[] checkStack) {
    int total = source.getInt(TABLE_CHECK_NODE_TOTAL_OFFSET);
    int programOffset = sourceBytes - total * 13;
    int nodes = 0;
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      int comparison = source.getInt(TABLE_CHECKS_OFFSET + index * Integer.BYTES);
      long value = source.getLong(TABLE_CHECK_VALUES_OFFSET + index * Long.BYTES);
      int valueDescriptor = source.getInt(
          TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET + index * Integer.BYTES);
      int count = Byte.toUnsignedInt(source.get(TABLE_CHECK_NODE_COUNTS_OFFSET + index));
      boolean checked = index < columns && (checkMask & 1L << index) != 0;
      if (checked
          ? count <= 0
              || nodes > total - count
              || !TableSchema.validFixedValue(valueDescriptor, value)
              || !TableSchema.validCheckComparison(comparison)
              || SqlTypeDescriptor.typeId(valueDescriptor)
                      == SqlTypeDescriptor.TYPE_ID_BOOLEAN
                  && comparison != TableSchema.CHECK_EQUAL
                  && comparison != TableSchema.CHECK_NOT_EQUAL
              || !validCheckProgram(
                  source,
                  programOffset + nodes * 13,
                  count,
                  index,
                  valueDescriptor,
                  checkStack)
          : comparison != 0 || value != 0 || valueDescriptor != 0 || count != 0) {
        return false;
      }
      nodes += count;
    }
    return nodes == total;
  }

  private static boolean validCheckProgram(
      ByteBuffer source,
      int offset,
      int nodes,
      int owner,
      int valueDescriptor,
      int[] stack) {
    int ownerDescriptor = source.getInt(
        TABLE_TYPE_DESCRIPTORS_OFFSET + owner * Integer.BYTES);
    return CatalogCheckProgramValidator.valid(
        source,
        offset,
        nodes,
        owner,
        ownerDescriptor,
        valueDescriptor,
        stack);
  }

  private static boolean validReferences(
      ByteBuffer source,
      int columns,
      long referenceMask,
      int tableId) {
    for (int column = 0; column < TableSchema.MAXIMUM_COLUMNS; column++) {
      int referencedTableId = source.getInt(
          TABLE_REFERENCE_IDS_OFFSET + column * Integer.BYTES);
      boolean referenced = column < columns && (referenceMask & 1L << column) != 0;
      if (referenced
          ? source.getInt(TABLE_TYPE_DESCRIPTORS_OFFSET + column * Integer.BYTES)
                  != SqlTypeDescriptor.BIGINT
              || referencedTableId <= 0
              || referencedTableId > RelationalKey.MAXIMUM_TABLE_ID
              || referencedTableId == tableId
          : referencedTableId != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean validTypeDescriptors(ByteBuffer source, int columns) {
    for (int column = 0; column < TableSchema.MAXIMUM_COLUMNS; column++) {
      int descriptor = source.getInt(
          TABLE_TYPE_DESCRIPTORS_OFFSET + column * Integer.BYTES);
      if (column >= columns) {
        if (descriptor != 0) {
          return false;
        }
      } else if (column == 0) {
        if (descriptor != SqlTypeDescriptor.BIGINT) {
          return false;
        }
      } else if (!SqlTypeDescriptor.isValid(descriptor)
          || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_BIGINT
              && SqlTypeDescriptor.typeId(descriptor)
                  != SqlTypeDescriptor.TYPE_ID_VARCHAR
              && SqlTypeDescriptor.typeId(descriptor)
                  != SqlTypeDescriptor.TYPE_ID_BOOLEAN
              && SqlTypeDescriptor.typeId(descriptor)
                  != SqlTypeDescriptor.TYPE_ID_DECIMAL
              && SqlTypeDescriptor.typeId(descriptor)
                  != SqlTypeDescriptor.TYPE_ID_DATE
              && SqlTypeDescriptor.typeId(descriptor)
                  != SqlTypeDescriptor.TYPE_ID_TIME
              && SqlTypeDescriptor.typeId(descriptor)
                  != SqlTypeDescriptor.TYPE_ID_TIMESTAMP
              && SqlTypeDescriptor.typeId(descriptor)
                  != SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) {
        return false;
      }
    }
    return true;
  }

  private static boolean validDefaults(
      ByteBuffer source,
      int columns,
      long defaultMask,
      int defaultTextBytes,
      int defaultTextOffset) {
    int expectedOffset = 0;
    for (int column = 0; column < TableSchema.MAXIMUM_COLUMNS; column++) {
      long value = source.getLong(TABLE_DEFAULTS_OFFSET + column * Long.BYTES);
      int kind = Byte.toUnsignedInt(source.get(TABLE_DEFAULT_KINDS_OFFSET + column));
      boolean present = column < columns && (defaultMask & 1L << column) != 0;
      int descriptor = column < columns
          ? source.getInt(TABLE_TYPE_DESCRIPTORS_OFFSET + column * Integer.BYTES) : 0;
      boolean varchar = SqlTypeDescriptor.typeId(descriptor)
          == SqlTypeDescriptor.TYPE_ID_VARCHAR;
      if (!present) {
        if (kind != SqlDefaultKind.NONE || value != 0) {
          return false;
        }
        continue;
      }
      if (SqlDefaultKind.isCurrent(kind)) {
        if (value != 0 || !SqlDefaultKind.compatible(kind, descriptor)) {
          return false;
        }
        continue;
      }
      if (kind != SqlDefaultKind.LITERAL) {
        return false;
      }
      if (!varchar) {
        if (!TableSchema.validFixedValue(descriptor, value)) {
          return false;
        }
        continue;
      }
      int offset = (int) (value >>> 32);
      int length = (int) value;
      if (offset != expectedOffset
          || length < 0
          || offset > defaultTextBytes - length
          || Utf8Text.validate(
              source,
              defaultTextOffset + offset,
              length,
              SqlTypeDescriptor.parameterOne(descriptor)) < 0) {
        return false;
      }
      expectedOffset += length;
    }
    return expectedOffset == defaultTextBytes;
  }

  private static boolean duplicateIndex(
      ByteBuffer source,
      int slot,
      int tableId,
      int column) {
    for (int prior = 0; prior < slot; prior++) {
      int offset = TABLE_INDEXES_OFFSET + prior * 16;
      if (source.getInt(offset) == tableId || source.getInt(offset + 8) == column) {
        return true;
      }
    }
    return false;
  }

  private static boolean sameName(CharSequence first, CharSequence second) {
    if (first.length() == second.length()) {
      for (int index = 0; index < first.length(); index++) {
        if (first.charAt(index) != second.charAt(index)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private static boolean knownCatalogMagic(long magic) {
    return CatalogSequenceCodec.matchesMagic(magic)
        || CatalogViewCodec.matchesMagic(magic)
        || magic == TABLE_MAGIC
        || magic == DROPPING_TABLE_MAGIC
        || CatalogIndexCodec.matchesMagic(magic);
  }

}
