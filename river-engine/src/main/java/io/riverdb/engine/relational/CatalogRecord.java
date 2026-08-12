package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Current catalog encoding for bounded relational schemas and index-build state. */
final class CatalogRecord {
  static final int MAXIMUM_BYTES =
      208 + TableSchema.MAXIMUM_COLUMNS * Long.BYTES
          + TableDefinition.MAXIMUM_INDEXES * 16
          + 64 + TableSchema.MAXIMUM_COLUMNS * (Integer.BYTES + 64);

  private static final long SEQUENCE_MAGIC = 0x5249564552534551L; // RIVERSEQ
  private static final long USER_SEQUENCE_MAGIC = 0x5249564552555345L; // RIVERUSE
  private static final long IDENTITY_SEQUENCE_MAGIC = 0x5249564552494453L; // RIVERIDS
  private static final long VIEW_MAGIC = 0x5249564552564945L; // RIVERVIE
  private static final long TABLE_MAGIC = 0x524956455254424cL; // RIVERTBL
  private static final long DROPPING_TABLE_MAGIC = 0x524956455244524fL; // RIVERDRO
  private static final long INDEX_MAGIC = 0x5249564552494e44L; // RIVERIND
  private static final int SEQUENCE_VERSION = 1;
  private static final int USER_SEQUENCE_VERSION = 1;
  private static final int IDENTITY_SEQUENCE_VERSION = 1;
  private static final int VIEW_VERSION = 1;
  private static final int TABLE_VERSION = 10;
  private static final int INDEX_VERSION = 3;
  private static final int TABLE_CHECK_MASK_OFFSET = 60;
  private static final int TABLE_CHECKS_OFFSET = 68;
  private static final int TABLE_CHECK_VALUES_OFFSET = 104;
  private static final int TABLE_DEFAULTS_OFFSET = 168;
  private static final int TABLE_REFERENCE_MASK_OFFSET = 232;
  private static final int TABLE_REFERENCE_IDS_OFFSET = 240;
  private static final int TABLE_INDEXES_OFFSET =
      TABLE_REFERENCE_IDS_OFFSET + TableSchema.MAXIMUM_COLUMNS * Integer.BYTES;

  private CatalogRecord() {
  }

  static void encodeSequence(ByteBuffer target, int nextTableId) {
    clear(target);
    target.putLong(0, SEQUENCE_MAGIC);
    target.putInt(8, SEQUENCE_VERSION);
    target.putInt(12, nextTableId);
    target.position(0);
    target.limit(16);
  }

  static StatusCode decodeSequence(
      HeapRowResult source,
      ByteBuffer scratch,
      IntResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()
        || source.length() != 16
        || scratch.getLong(0) != SEQUENCE_MAGIC
        || scratch.getInt(8) != SEQUENCE_VERSION
        || scratch.getInt(12) <= 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(scratch.getInt(12));
    return StatusCode.OK;
  }

  static void encodeUserSequence(
      ByteBuffer target,
      CharSequence name,
      long nextValue,
      long increment,
      boolean exhausted) {
    clear(target);
    target.putLong(0, USER_SEQUENCE_MAGIC);
    target.putInt(8, USER_SEQUENCE_VERSION);
    target.putInt(12, name.length());
    target.putLong(16, nextValue);
    target.putLong(24, increment);
    target.putInt(32, exhausted ? 1 : 0);
    for (int index = 0; index < name.length(); index++) {
      target.put(36 + index, (byte) name.charAt(index));
    }
    target.position(0);
    target.limit(36 + name.length());
  }

  static StatusCode decodeUserSequence(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      UserSequenceResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    if (source.length() < Long.BYTES) {
      return StatusCode.CORRUPTION;
    }
    if (scratch.getLong(0) != USER_SEQUENCE_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = source.length() >= 16 ? scratch.getInt(12) : -1;
    long increment = source.length() >= 32 ? scratch.getLong(24) : 0;
    int exhausted = source.length() >= 36 ? scratch.getInt(32) : -1;
    if (source.length() < 37
        || scratch.getInt(8) != USER_SEQUENCE_VERSION
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || source.length() != 36 + nameBytes
        || increment == 0
        || (exhausted != 0 && exhausted != 1)
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(36 + index)) != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(scratch.getLong(16), increment, exhausted == 1);
    return StatusCode.OK;
  }

  static void encodeIdentitySequence(
      ByteBuffer target,
      int tableId,
      long nextValue,
      boolean exhausted) {
    clear(target);
    target.putLong(0, IDENTITY_SEQUENCE_MAGIC);
    target.putInt(8, IDENTITY_SEQUENCE_VERSION);
    target.putInt(12, tableId);
    target.putLong(16, nextValue);
    target.putInt(24, exhausted ? 1 : 0);
    target.position(0);
    target.limit(28);
  }

  static StatusCode decodeIdentitySequence(
      HeapRowResult source,
      ByteBuffer scratch,
      int expectedTableId,
      UserSequenceResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    int exhausted = source.length() >= 28 ? scratch.getInt(24) : -1;
    if (source.length() != 28
        || scratch.getLong(0) != IDENTITY_SEQUENCE_MAGIC
        || scratch.getInt(8) != IDENTITY_SEQUENCE_VERSION
        || scratch.getInt(12) != expectedTableId
        || scratch.getLong(16) < 1
        || scratch.getLong(16) > RelationalKey.MAXIMUM_USER_KEY
        || (exhausted != 0 && exhausted != 1)) {
      return StatusCode.CORRUPTION;
    }
    result.set(scratch.getLong(16), 1, exhausted == 1);
    return StatusCode.OK;
  }

  static void encodeView(
      ByteBuffer target,
      CharSequence name,
      CharSequence query,
      int baseTableId) {
    clear(target);
    target.putLong(0, VIEW_MAGIC);
    target.putInt(8, VIEW_VERSION);
    target.putInt(12, name.length());
    target.putInt(16, query.length());
    target.putInt(20, baseTableId);
    int offset = 24;
    for (int index = 0; index < name.length(); index++) {
      target.put(offset++, (byte) name.charAt(index));
    }
    for (int index = 0; index < query.length(); index++) {
      target.put(offset++, (byte) query.charAt(index));
    }
    target.position(0);
    target.limit(offset);
  }

  static StatusCode decodeView(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      ViewDefinition result) {
    result.reset();
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    if (source.length() < Long.BYTES) {
      return StatusCode.CORRUPTION;
    }
    if (scratch.getLong(0) != VIEW_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = source.length() >= 20 ? scratch.getInt(12) : -1;
    int queryBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    if (source.length() < 25
        || scratch.getInt(8) != VIEW_VERSION
        || scratch.getInt(20) <= 0
        || scratch.getInt(20) > RelationalKey.MAXIMUM_TABLE_ID
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || queryBytes <= 0
        || queryBytes > ViewDefinition.MAXIMUM_QUERY_LENGTH
        || source.length() != 24 + nameBytes + queryBytes
        || expectedName.length() != nameBytes) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(24 + index))
          != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    for (int index = 0; index < queryBytes; index++) {
      result.append((char) Byte.toUnsignedInt(scratch.get(24 + nameBytes + index)));
    }
    result.setBaseTableId(scratch.getInt(20));
    return StatusCode.OK;
  }

  static StatusCode decodeViewForScan(
      HeapRowResult source,
      ByteBuffer scratch,
      TableSchema.ColumnName name,
      ViewDefinition result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    long magic = status.isOk() && source.length() >= Long.BYTES
        ? scratch.getLong(0) : 0;
    if (!status.isOk()) {
      return status;
    }
    if (magic != VIEW_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = source.length() >= 20 ? scratch.getInt(12) : -1;
    if (source.length() < 25
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || nameBytes > source.length() - 24) {
      return StatusCode.CORRUPTION;
    }
    name.set(scratch, 24, nameBytes);
    if (!RelationalKey.validName(name)) {
      return StatusCode.CORRUPTION;
    }
    return decodeView(source, scratch, name, result);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      CharSequence name) {
    encodeTable(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexTableId == 0
            ? TableDefinition.INDEX_NONE : TableDefinition.INDEX_READY,
        name,
        "key",
        "value");
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name) {
    encodeTable(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        name,
        "key",
        "value");
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName) {
    int indexColumn = uniqueValueIndexTableId == 0 ? -1 : 1;
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        2,
        1L,
        0,
        0,
        null,
        null,
        true,
        false);
    offset = encodeColumn(target, offset, keyColumnName);
    offset = encodeColumn(target, offset, valueColumnName);
    target.position(0);
    target.limit(offset);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableSchema schema) {
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema.columnCount(),
        schema.notNullMask(),
        schema.defaultMask(),
        schema.varcharMask(),
        schema,
        null,
        true,
        false);
    for (int index = 0; index < schema.columnCount(); index++) {
      offset = encodeColumn(target, offset, schema.columnName(index));
    }
    target.position(0);
    target.limit(offset);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema) {
    encodeTable(
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
    encodeTable(
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
    int offset = encodeTableHeader(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema.columnCount(),
        schema.notNullMask(),
        schema.defaultMask(),
        schema.varcharMask(),
        null,
        schema,
        unique,
        constraint);
    for (int index = 0; index < schema.columnCount(); index++) {
      offset = encodeColumn(target, offset, schema.columnName(index));
    }
    target.position(0);
    target.limit(offset);
  }

  static void encodeIndex(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      CharSequence name) {
    encodeIndex(
        target,
        tableId,
        indexTableId,
        TableDefinition.INDEX_READY,
        name,
        true);
  }

  static void encodeIndex(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name) {
    encodeIndex(target, tableId, indexTableId, indexState, name, true);
  }

  static void encodeIndex(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name,
      boolean unique) {
    encodeIndex(target, tableId, indexTableId, indexState, name, unique, false);
  }

  static void encodeIndex(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      CharSequence name,
      boolean unique,
      boolean constraint) {
    clear(target);
    target.putLong(0, INDEX_MAGIC);
    target.putInt(8, INDEX_VERSION);
    target.putInt(12, tableId);
    target.putInt(16, indexTableId);
    target.putInt(20, indexState);
    target.putInt(24, (unique ? 1 : 0) | (constraint ? 2 : 0));
    target.putInt(28, name.length());
    for (int index = 0; index < name.length(); index++) {
      target.put(32 + index, (byte) name.charAt(index));
    }
    target.position(0);
    target.limit(32 + name.length());
  }

  static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalDatabase database,
      TableDefinition result) {
    return decodeTable(
        source, scratch, expectedName, database, result, TABLE_MAGIC);
  }

  static StatusCode decodeDroppingTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalDatabase database,
      TableDefinition result) {
    return decodeTable(
        source, scratch, expectedName, database, result, DROPPING_TABLE_MAGIC);
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
      RelationalDatabase database,
      TableSchema.ColumnName name,
      TableDefinition result) {
    if (source == null || scratch == null || database == null || name == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    long magic = status.isOk() && source.length() >= Long.BYTES
        ? scratch.getLong(0) : 0;
    if (!status.isOk()) {
      return status;
    }
    if (magic != TABLE_MAGIC && magic != DROPPING_TABLE_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int indexCount = source.length() >= 28 ? scratch.getInt(24) : -1;
    int nameBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    int nameOffset = indexCount >= 0 && indexCount <= TableDefinition.MAXIMUM_INDEXES
        ? TABLE_INDEXES_OFFSET + indexCount * 16 : -1;
    if (source.length() < 28
        || scratch.getInt(8) != TABLE_VERSION
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || nameOffset < 0
        || nameOffset > source.length() - nameBytes) {
      return StatusCode.CORRUPTION;
    }
    name.set(scratch, nameOffset, nameBytes);
    if (!RelationalKey.validName(name)) {
      return StatusCode.CORRUPTION;
    }
    return decodeTable(source, scratch, name, database, result, magic);
  }

  static void encodeDroppingTable(
      ByteBuffer target,
      int tableId,
      CharSequence name,
      TableDefinition schema) {
    encodeTable(
        target,
        tableId,
        0,
        TableDefinition.INDEX_NONE,
        -1,
        name,
        schema);
    target.putLong(0, DROPPING_TABLE_MAGIC);
  }

  private static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalDatabase database,
      TableDefinition result,
      long expectedMagic) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    long actualMagic = status.isOk() && source.length() >= Long.BYTES
        ? scratch.getLong(0) : 0;
    if (status.isOk() && actualMagic != expectedMagic) {
      return knownCatalogMagic(actualMagic)
          ? StatusCode.CONFLICT : StatusCode.CORRUPTION;
    }
    int version = source.length() >= 12 ? scratch.getInt(8) : -1;
    int nameBytes = source.length() >= 20 ? scratch.getInt(16) : -1;
    int columnCount = source.length() >= 24 ? scratch.getInt(20) : -1;
    int indexCount = source.length() >= 28 ? scratch.getInt(24) : -1;
    long notNullMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(28) : -1;
    long defaultMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(36) : -1;
    long varcharMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(44) : -1;
    long identityMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(52) : -1;
    long checkMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(TABLE_CHECK_MASK_OFFSET) : -1;
    long referenceMask = source.length() >= TABLE_INDEXES_OFFSET
        ? scratch.getLong(TABLE_REFERENCE_MASK_OFFSET) : -1;
    boolean validIndexCount = indexCount >= 0
        && indexCount <= TableDefinition.MAXIMUM_INDEXES;
    int nameOffset = validIndexCount
        ? TABLE_INDEXES_OFFSET + indexCount * 16 : -1;
    int columnsOffset = nameOffset < 0 ? -1 : nameOffset + nameBytes;
    int expectedBytes = columnsOffset;
    if (validIndexCount
        && nameBytes > 0
        && nameBytes <= TableSchema.MAXIMUM_NAME_LENGTH
        && columnsOffset >= nameOffset
        && columnsOffset <= source.length()
        && columnCount >= 2
        && columnCount <= TableSchema.MAXIMUM_COLUMNS) {
      for (int index = 0; index < columnCount; index++) {
        if (expectedBytes > source.length() - Integer.BYTES) {
          expectedBytes = -1;
          break;
        }
        int columnBytes = scratch.getInt(expectedBytes);
        if (columnBytes <= 0
            || columnBytes > TableSchema.MAXIMUM_NAME_LENGTH
            || expectedBytes > source.length() - Integer.BYTES - columnBytes) {
          expectedBytes = -1;
          break;
        }
        expectedBytes += Integer.BYTES + columnBytes;
      }
    }
    if (!status.isOk()
        || version != TABLE_VERSION
        || !validIndexCount
        || nameOffset < 0
        || source.length() < nameOffset + 1
        || source.length() != expectedBytes
        || scratch.getLong(0) != expectedMagic
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || columnCount < 2
        || columnCount > TableSchema.MAXIMUM_COLUMNS
        || (notNullMask & 1) == 0
        || (notNullMask & ~((1L << columnCount) - 1)) != 0
        || (defaultMask & 1) != 0
        || (defaultMask & ~((1L << columnCount) - 1)) != 0
        || (varcharMask & 1) != 0
        || (varcharMask & ~((1L << columnCount) - 1)) != 0
        || (identityMask != 0 && identityMask != 1)
        || (checkMask & ~((1L << columnCount) - 1)) != 0
        || (checkMask & varcharMask) != 0
        || !validChecks(scratch, columnCount, checkMask)
        || (referenceMask & 1) != 0
        || (referenceMask & ~((1L << columnCount) - 1)) != 0
        || (referenceMask & varcharMask) != 0
        || !validReferences(
            scratch, columnCount, referenceMask, scratch.getInt(12))
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(nameOffset + index)) != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(
        database,
        scratch.getInt(12),
        0,
        TableDefinition.INDEX_NONE,
        -1,
        scratch,
        columnsOffset,
        columnCount,
        notNullMask,
        defaultMask,
        varcharMask,
        identityMask == 1,
        checkMask,
        TABLE_CHECKS_OFFSET,
        TABLE_CHECK_VALUES_OFFSET,
        referenceMask,
        TABLE_REFERENCE_IDS_OFFSET,
        TABLE_DEFAULTS_OFFSET);
    int buildingIndexes = 0;
    for (int index = 0; status.isOk() && index < indexCount; index++) {
      int offset = TABLE_INDEXES_OFFSET + index * 16;
      int indexTableId = scratch.getInt(offset);
      int indexState = scratch.getInt(offset + 4);
      int indexColumn = scratch.getInt(offset + 8);
      int flags = scratch.getInt(offset + 12);
      if (indexTableId <= 0
          || indexTableId > RelationalKey.MAXIMUM_TABLE_ID
          || indexTableId == scratch.getInt(12)
          || (indexState != TableDefinition.INDEX_BUILDING
              && indexState != TableDefinition.INDEX_READY
              && indexState != TableDefinition.INDEX_DROPPING)
          || indexColumn <= 0
          || indexColumn >= columnCount
          || (flags & ~3) != 0
          || ((flags & 3) == 2 && (referenceMask & 1L << indexColumn) == 0)
          || duplicateIndex(scratch, index, indexTableId, indexColumn)
          || (indexState == TableDefinition.INDEX_BUILDING && ++buildingIndexes > 1)) {
        status = StatusCode.CORRUPTION;
      } else {
        status = result.upsertIndex(
            indexTableId,
            indexState,
            indexColumn,
            (flags & 1) != 0,
            (flags & 2) != 0);
        if (status == StatusCode.CONFLICT
            || status == StatusCode.RESOURCE_EXHAUSTED
            || status == StatusCode.INVALID_EXTERNAL_INPUT) {
          status = StatusCode.CORRUPTION;
        }
      }
    }
    if (!status.isOk() || !validColumns(result)) {
      result.reset();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    return StatusCode.OK;
  }

  static StatusCode decodeIndex(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      IndexResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    int version = source.length() >= 12 ? scratch.getInt(8) : -1;
    int state = source.length() >= 24 ? scratch.getInt(20) : -1;
    int flags = source.length() >= 28 ? scratch.getInt(24) : -1;
    int nameOffset = 32;
    int nameBytes = source.length() >= 32 ? scratch.getInt(28) : -1;
    if (!status.isOk()
        || version != INDEX_VERSION
        || source.length() < nameOffset + 1
        || source.length() != nameOffset + nameBytes
        || scratch.getLong(0) != INDEX_MAGIC
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || scratch.getInt(16) <= 0
        || scratch.getInt(16) > RelationalKey.MAXIMUM_TABLE_ID
        || (state != TableDefinition.INDEX_BUILDING
            && state != TableDefinition.INDEX_READY
            && state != TableDefinition.INDEX_DROPPING)
        || (flags & ~3) != 0
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(nameOffset + index)) != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(
        scratch.getInt(12),
        scratch.getInt(16),
        state,
        (flags & 1) != 0,
        (flags & 2) != 0);
    return StatusCode.OK;
  }

  static StatusCode decodeIndexForTable(
      HeapRowResult source,
      ByteBuffer scratch,
      int expectedTableId,
      IndexResult result) {
    return decodeIndexForTable(source, scratch, expectedTableId, null, result);
  }

  static StatusCode decodeIndexForTable(
      HeapRowResult source,
      ByteBuffer scratch,
      int expectedTableId,
      TableSchema.ColumnName name,
      IndexResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    if (source.length() < 32 || scratch.getLong(0) != INDEX_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = scratch.getInt(28);
    int state = scratch.getInt(20);
    int flags = scratch.getInt(24);
    if (scratch.getInt(8) != INDEX_VERSION
        || source.length() != 32 + nameBytes
        || scratch.getInt(12) <= 0
        || scratch.getInt(12) > RelationalKey.MAXIMUM_TABLE_ID
        || scratch.getInt(16) <= 0
        || scratch.getInt(16) > RelationalKey.MAXIMUM_TABLE_ID
        || (state != TableDefinition.INDEX_BUILDING
            && state != TableDefinition.INDEX_READY
            && state != TableDefinition.INDEX_DROPPING)
        || (flags & ~3) != 0
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH) {
      return StatusCode.CORRUPTION;
    }
    if (scratch.getInt(12) != expectedTableId) {
      return StatusCode.CONFLICT;
    }
    if (name != null) {
      name.set(scratch, 32, nameBytes);
      if (!RelationalKey.validName(name)) {
        return StatusCode.CORRUPTION;
      }
    }
    result.set(
        scratch.getInt(12),
        scratch.getInt(16),
        state,
        (flags & 1) != 0,
        (flags & 2) != 0);
    return StatusCode.OK;
  }

  private static int encodeTableHeader(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      int indexColumn,
      CharSequence name,
      int columnCount,
      long notNullMask,
      long defaultMask,
      long varcharMask,
      TableSchema definition,
      TableDefinition existing,
      boolean unique,
      boolean constraint) {
    clear(target);
    int existingCount = existing == null ? 0 : existing.uniqueIndexCount();
    int overrideSlot = -1;
    if (indexTableId > 0 && existing != null) {
      for (int index = 0; index < existingCount; index++) {
        if (existing.uniqueIndexTableId(index) == indexTableId
            || existing.uniqueIndexColumn(index) == indexColumn) {
          overrideSlot = index;
          break;
        }
      }
    }
    int indexCount = existingCount
        + (indexTableId > 0 && overrideSlot < 0 ? 1 : 0);
    if (existing == null && indexTableId > 0) {
      indexCount = 1;
    }
    target.putLong(0, TABLE_MAGIC);
    target.putInt(8, TABLE_VERSION);
    target.putInt(12, tableId);
    target.putInt(16, name.length());
    target.putInt(20, columnCount);
    target.putInt(24, indexCount);
    target.putLong(28, notNullMask);
    target.putLong(36, defaultMask);
    target.putLong(44, varcharMask);
    boolean identity = definition != null
        ? definition.hasIdentity() : existing != null && existing.hasIdentity();
    target.putLong(52, identity ? 1 : 0);
    long checkMask = definition != null
        ? definition.checkMask() : existing != null ? existing.checkMask() : 0;
    target.putLong(TABLE_CHECK_MASK_OFFSET, checkMask);
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      boolean checked = (checkMask & 1L << index) != 0;
      int comparison = definition != null
          ? definition.checkComparison(index)
          : existing != null ? existing.checkComparison(index) : 0;
      long value = definition != null
          ? definition.checkValue(index)
          : existing != null ? existing.checkValue(index) : 0;
      target.putInt(
          TABLE_CHECKS_OFFSET + index * Integer.BYTES,
          checked ? comparison : 0);
      target.putLong(
          TABLE_CHECK_VALUES_OFFSET + index * Long.BYTES,
          checked ? value : 0);
    }
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      long value = definition != null
          ? definition.defaultValue(index)
          : existing != null ? existing.defaultValue(index) : 0;
      target.putLong(TABLE_DEFAULTS_OFFSET + index * Long.BYTES, value);
    }
    long referenceMask = definition != null
        ? definition.referenceMask()
        : existing != null ? existing.referenceMask() : 0;
    target.putLong(TABLE_REFERENCE_MASK_OFFSET, referenceMask);
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      boolean referenced = (referenceMask & 1L << index) != 0;
      int referencedTableId = definition != null
          ? definition.referenceTableId(index)
          : existing != null ? existing.referenceTableId(index) : 0;
      target.putInt(
          TABLE_REFERENCE_IDS_OFFSET + index * Integer.BYTES,
          referenced ? referencedTableId : 0);
    }
    for (int index = 0; index < indexCount; index++) {
      int output = TABLE_INDEXES_OFFSET + index * 16;
      boolean override = index == overrideSlot
          || existing == null && index == 0
          || existing != null && index == existingCount;
      target.putInt(
          output,
          override ? indexTableId : existing.uniqueIndexTableId(index));
      target.putInt(
          output + 4,
          override ? indexState : existing.uniqueIndexState(index));
      target.putInt(
          output + 8,
          override ? indexColumn : existing.uniqueIndexColumn(index));
      target.putInt(
          output + 12,
          ((override ? unique : existing.indexIsUnique(index)) ? 1 : 0)
              | ((override ? constraint : existing.indexIsConstraint(index)) ? 2 : 0));
    }
    int nameOffset = TABLE_INDEXES_OFFSET + indexCount * 16;
    for (int index = 0; index < name.length(); index++) {
      target.put(nameOffset + index, (byte) name.charAt(index));
    }
    return nameOffset + name.length();
  }

  private static int encodeColumn(ByteBuffer target, int offset, CharSequence name) {
    target.putInt(offset, name.length());
    offset += Integer.BYTES;
    for (int index = 0; index < name.length(); index++) {
      target.put(offset + index, (byte) name.charAt(index));
    }
    return offset + name.length();
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

  private static boolean validChecks(ByteBuffer source, int columns, long checkMask) {
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      int comparison = source.getInt(TABLE_CHECKS_OFFSET + index * Integer.BYTES);
      long value = source.getLong(TABLE_CHECK_VALUES_OFFSET + index * Long.BYTES);
      boolean checked = index < columns && (checkMask & 1L << index) != 0;
      if (checked
          ? !TableSchema.validCheckComparison(comparison)
          : comparison != 0 || value != 0) {
        return false;
      }
    }
    return true;
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
          ? referencedTableId <= 0
              || referencedTableId > RelationalKey.MAXIMUM_TABLE_ID
              || referencedTableId == tableId
          : referencedTableId != 0) {
        return false;
      }
    }
    return true;
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

  private static void clear(ByteBuffer target) {
    target.clear();
    for (int index = 0; index < target.capacity(); index++) {
      target.put(index, (byte) 0);
    }
  }

  private static boolean knownCatalogMagic(long magic) {
    return magic == SEQUENCE_MAGIC
        || magic == USER_SEQUENCE_MAGIC
        || magic == IDENTITY_SEQUENCE_MAGIC
        || magic == VIEW_MAGIC
        || magic == TABLE_MAGIC
        || magic == DROPPING_TABLE_MAGIC
        || magic == INDEX_MAGIC;
  }

  static final class IntResult {
    private int value;

    void set(int result) {
      value = result;
    }

    int value() {
      return value;
    }
  }

  static final class IndexResult {
    private int tableId;
    private int indexTableId;
    private int state;
    private boolean unique;
    private boolean constraint;

    void set(
        int ownerTableId,
        int storageTableId,
        int indexState,
        boolean isUnique,
        boolean isConstraint) {
      tableId = ownerTableId;
      indexTableId = storageTableId;
      state = indexState;
      unique = isUnique;
      constraint = isConstraint;
    }

    int tableId() {
      return tableId;
    }

    int indexTableId() {
      return indexTableId;
    }

    int state() {
      return state;
    }

    boolean isUnique() {
      return unique;
    }

    boolean isConstraint() {
      return constraint;
    }
  }

  static final class UserSequenceResult {
    private long nextValue;
    private long increment;
    private boolean exhausted;

    void set(long value, long step, boolean isExhausted) {
      nextValue = value;
      increment = step;
      exhausted = isExhausted;
    }

    long nextValue() {
      return nextValue;
    }

    long increment() {
      return increment;
    }

    boolean isExhausted() {
      return exhausted;
    }
  }
}
