package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Byte-exact indexed-table WAL layout without WAL publication or table-state semantics. */
final class IndexedWalCodec {
  static final int FORMAT_ID = 1002;
  static final int FORMAT_VERSION = 5;
  static final long MAX_LOGICAL_ROW_ID = 0xFFFF_FFFEL;

  static final long OPERATION_MAGIC = 0x5249564552494458L; // RIVERIDX
  static final int OPERATION_TYPE_PAGE_IMAGES = 1;
  static final int OPERATION_TYPE_INSERT = 2;
  static final int OPERATION_TYPE_INSERT_BATCH = 3;
  static final int OPERATION_TYPE_MUTATION_BATCH = 4;
  static final int OPERATION_TYPE_VACUUM_CHUNK = 5;
  static final int OPERATION_TYPE_VACUUM_COMMIT = 6;

  static final int MUTATION_INSERT = 1;
  static final int MUTATION_UPDATE = 2;
  static final int MUTATION_DELETE = 3;

  static final int PAGE_OPERATION_HEADER_BYTES = 24;
  static final int PAGE_OPERATION_VERSION_BYTES = 8;
  static final int INSERT_OPERATION_HEADER_BYTES = 40;
  static final int INSERT_BATCH_HEADER_BYTES = 24;
  static final int INSERT_BATCH_ENTRY_BYTES = 20;
  static final int MUTATION_BATCH_HEADER_BYTES = 24;
  static final int MUTATION_BATCH_ENTRY_BYTES = 28;
  static final int VACUUM_CHUNK_HEADER_BYTES = 40;
  static final int VACUUM_ENTRY_BYTES = 24;
  static final int VACUUM_COMMIT_PAYLOAD_BYTES = 32;

  private static final int COMMON_MAGIC_OFFSET = 0;
  private static final int COMMON_VERSION_OFFSET = 8;
  private static final int COMMON_TYPE_OFFSET = 12;
  private static final int COMMON_HEADER_BYTES = 16;
  private static final int LONG_HIGH_WORD_OFFSET = 4;

  private static final int PAGE_COUNT_OFFSET = COMMON_HEADER_BYTES;
  private static final int PAGE_VERSION_COUNT_OFFSET = 20;
  private static final int PAGE_VERSION_PREVIOUS_ROW_ID_OFFSET = 0;
  private static final int PAGE_VERSION_DELETED_OFFSET = 4;

  private static final int INSERT_KEY_OFFSET = COMMON_HEADER_BYTES;
  private static final int INSERT_ROW_ID_OFFSET = 24;
  private static final int INSERT_ROW_BYTES_OFFSET = 28;
  private static final int INSERT_SPACE_OFFSET = 32;
  private static final int INSERT_RESERVED_OFFSET = 36;

  private static final int BATCH_ENTRY_COUNT_OFFSET = COMMON_HEADER_BYTES;
  private static final int BATCH_RESERVED_OFFSET = 20;
  private static final int INSERT_BATCH_KEY_OFFSET = 0;
  private static final int INSERT_BATCH_ROW_ID_OFFSET = 8;
  private static final int INSERT_BATCH_ROW_BYTES_OFFSET = 12;
  private static final int INSERT_BATCH_SPACE_OFFSET = 16;

  private static final int MUTATION_OPERATION_OFFSET = 0;
  private static final int MUTATION_KEY_OFFSET = 4;
  private static final int MUTATION_ROW_ID_OFFSET = 12;
  private static final int MUTATION_PREVIOUS_ROW_ID_OFFSET = 16;
  private static final int MUTATION_ROW_BYTES_OFFSET = 20;
  private static final int MUTATION_SPACE_OFFSET = 24;

  private static final int VACUUM_RETAINED_ROWS_OFFSET = COMMON_HEADER_BYTES;
  private static final int VACUUM_FIRST_ROW_OFFSET = 20;
  private static final int VACUUM_ROW_COUNT_OFFSET = 24;
  private static final int VACUUM_CHUNK_OFFSET = 28;
  private static final int VACUUM_CHUNK_COUNT_OFFSET = 32;
  private static final int VACUUM_RESERVED_OFFSET = 36;
  private static final int VACUUM_ENTRY_KEY_OFFSET = 0;
  private static final int VACUUM_ENTRY_ROW_ID_OFFSET = 8;
  private static final int VACUUM_ENTRY_ROW_BYTES_OFFSET = 12;
  private static final int VACUUM_ENTRY_DELETED_OFFSET = 16;
  private static final int VACUUM_ENTRY_SPACE_OFFSET = 20;

  private static final int VACUUM_COMMIT_RETAINED_ROWS_OFFSET = COMMON_HEADER_BYTES;
  private static final int VACUUM_COMMIT_CHUNK_COUNT_OFFSET = 20;
  private static final int VACUUM_COMMIT_ROWS_BEFORE_OFFSET = 24;
  private static final int VACUUM_COMMIT_RESERVED_OFFSET = 28;

  private IndexedWalCodec() {
  }

  static int operationType(ByteBuffer payload) {
    return hasCommonHeader(payload) ? getInt(payload, COMMON_TYPE_OFFSET) : 0;
  }

  static boolean hasCommonHeader(ByteBuffer payload) {
    return payload != null
        && payload.limit() >= PAGE_OPERATION_HEADER_BYTES
        && getLong(payload, COMMON_MAGIC_OFFSET) == OPERATION_MAGIC
        && getInt(payload, COMMON_VERSION_OFFSET) == FORMAT_VERSION;
  }

  static int pageOperationBytes(int pageCount, int versionCount) {
    if (pageCount <= 0 || versionCount < 0) {
      return 0;
    }
    long bytes = (long) PAGE_OPERATION_HEADER_BYTES
        + (long) pageCount * PageCodec.PAGE_BYTES
        + (long) versionCount * PAGE_OPERATION_VERSION_BYTES;
    return bytes <= Integer.MAX_VALUE ? (int) bytes : 0;
  }

  static int insertOperationBytes(int rowBytes) {
    return checkedVariableBytes(INSERT_OPERATION_HEADER_BYTES, rowBytes);
  }

  static int insertBatchEntryBytes(int rowBytes) {
    return checkedVariableBytes(INSERT_BATCH_ENTRY_BYTES, rowBytes);
  }

  static int mutationBatchEntryBytes(int rowBytes) {
    return checkedVariableBytes(MUTATION_BATCH_ENTRY_BYTES, rowBytes);
  }

  static int vacuumEntryBytes(int rowBytes) {
    return checkedVariableBytes(VACUUM_ENTRY_BYTES, rowBytes);
  }

  static void encodePageOperationHeader(
      ByteBuffer target,
      int pageCount,
      int versionCount) {
    encodeCommonHeader(target, OPERATION_TYPE_PAGE_IMAGES);
    putInt(target, PAGE_COUNT_OFFSET, pageCount);
    putInt(target, PAGE_VERSION_COUNT_OFFSET, versionCount);
  }

  static void encodePageOperationVersion(
      ByteBuffer target,
      int offset,
      long previousRowId,
      boolean deleted) {
    putInt(target, offset + PAGE_VERSION_PREVIOUS_ROW_ID_OFFSET, (int) previousRowId);
    putInt(target, offset + PAGE_VERSION_DELETED_OFFSET, deleted ? 1 : 0);
  }

  static void encodeInsertHeader(
      ByteBuffer target,
      int space,
      long key,
      long rowId,
      int rowBytes) {
    encodeCommonHeader(target, OPERATION_TYPE_INSERT);
    putLong(target, INSERT_KEY_OFFSET, key);
    putInt(target, INSERT_ROW_ID_OFFSET, (int) rowId);
    putInt(target, INSERT_ROW_BYTES_OFFSET, rowBytes);
    putInt(target, INSERT_SPACE_OFFSET, space);
    putInt(target, INSERT_RESERVED_OFFSET, 0);
  }

  static void encodeInsertBatchHeader(ByteBuffer target, int entryCount) {
    encodeCommonHeader(target, OPERATION_TYPE_INSERT_BATCH);
    putInt(target, BATCH_ENTRY_COUNT_OFFSET, entryCount);
    putInt(target, BATCH_RESERVED_OFFSET, 0);
  }

  static void encodeInsertBatchEntry(
      ByteBuffer target,
      int offset,
      int space,
      long key,
      long rowId,
      int rowBytes) {
    putLong(target, offset + INSERT_BATCH_KEY_OFFSET, key);
    putInt(target, offset + INSERT_BATCH_ROW_ID_OFFSET, (int) rowId);
    putInt(target, offset + INSERT_BATCH_ROW_BYTES_OFFSET, rowBytes);
    putInt(target, offset + INSERT_BATCH_SPACE_OFFSET, space);
  }

  static void encodeMutationBatchHeader(ByteBuffer target, int entryCount) {
    encodeCommonHeader(target, OPERATION_TYPE_MUTATION_BATCH);
    putInt(target, BATCH_ENTRY_COUNT_OFFSET, entryCount);
    putInt(target, BATCH_RESERVED_OFFSET, 0);
  }

  static void encodeMutationBatchEntry(
      ByteBuffer target,
      int offset,
      int operation,
      int space,
      long key,
      long rowId,
      long previousRowId,
      int rowBytes) {
    putInt(target, offset + MUTATION_OPERATION_OFFSET, operation);
    putLong(target, offset + MUTATION_KEY_OFFSET, key);
    putInt(target, offset + MUTATION_ROW_ID_OFFSET, (int) rowId);
    putInt(target, offset + MUTATION_PREVIOUS_ROW_ID_OFFSET, (int) previousRowId);
    putInt(target, offset + MUTATION_ROW_BYTES_OFFSET, rowBytes);
    putInt(target, offset + MUTATION_SPACE_OFFSET, space);
  }

  static void encodeVacuumChunkHeader(
      ByteBuffer target,
      long retainedRows,
      long firstRow,
      int rowCount,
      int chunk,
      int chunkCount) {
    encodeCommonHeader(target, OPERATION_TYPE_VACUUM_CHUNK);
    putInt(target, VACUUM_RETAINED_ROWS_OFFSET, (int) retainedRows);
    putInt(target, VACUUM_FIRST_ROW_OFFSET, (int) firstRow);
    putInt(target, VACUUM_ROW_COUNT_OFFSET, rowCount);
    putInt(target, VACUUM_CHUNK_OFFSET, chunk);
    putInt(target, VACUUM_CHUNK_COUNT_OFFSET, chunkCount);
    putInt(target, VACUUM_RESERVED_OFFSET, 0);
  }

  static void encodeVacuumEntry(
      ByteBuffer target,
      int offset,
      int space,
      long key,
      long rowId,
      int rowBytes,
      boolean deleted) {
    putLong(target, offset + VACUUM_ENTRY_KEY_OFFSET, key);
    putInt(target, offset + VACUUM_ENTRY_ROW_ID_OFFSET, (int) rowId);
    putInt(target, offset + VACUUM_ENTRY_ROW_BYTES_OFFSET, rowBytes);
    putInt(target, offset + VACUUM_ENTRY_DELETED_OFFSET, deleted ? 1 : 0);
    putInt(target, offset + VACUUM_ENTRY_SPACE_OFFSET, space);
  }

  static void encodeVacuumCommit(
      ByteBuffer target,
      long retainedRows,
      int chunkCount,
      long rowsBefore) {
    encodeCommonHeader(target, OPERATION_TYPE_VACUUM_COMMIT);
    putInt(target, VACUUM_COMMIT_RETAINED_ROWS_OFFSET, (int) retainedRows);
    putInt(target, VACUUM_COMMIT_CHUNK_COUNT_OFFSET, chunkCount);
    putInt(target, VACUUM_COMMIT_ROWS_BEFORE_OFFSET, (int) rowsBefore);
    putInt(target, VACUUM_COMMIT_RESERVED_OFFSET, 0);
  }

  static StatusCode validatePageOperation(
      ByteBuffer payload,
      int maximumPageCount,
      int maximumVersionCount) {
    if (!hasOperationType(payload, OPERATION_TYPE_PAGE_IMAGES)) {
      return StatusCode.CORRUPTION;
    }
    int pageCount = getInt(payload, PAGE_COUNT_OFFSET);
    int versionCount = getInt(payload, PAGE_VERSION_COUNT_OFFSET);
    int expectedBytes = pageOperationBytes(pageCount, versionCount);
    if (pageCount > maximumPageCount
        || versionCount > maximumVersionCount
        || expectedBytes == 0
        || payload.limit() != expectedBytes) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode validateInsert(ByteBuffer payload) {
    if (!hasOperationType(payload, OPERATION_TYPE_INSERT)
        || payload.limit() < INSERT_OPERATION_HEADER_BYTES
        || !validLogicalRowId(unsignedInt(payload, INSERT_ROW_ID_OFFSET), false)
        || getInt(payload, INSERT_ROW_BYTES_OFFSET) <= 0
        || !OrderedKey.isFiniteSpace(getInt(payload, INSERT_SPACE_OFFSET))
        || getInt(payload, INSERT_RESERVED_OFFSET) != 0
        || payload.limit() != insertOperationBytes(getInt(payload, INSERT_ROW_BYTES_OFFSET))) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode validateInsertBatch(ByteBuffer payload, int maximumEntries) {
    if (!hasOperationType(payload, OPERATION_TYPE_INSERT_BATCH)
        || payload.limit() < INSERT_BATCH_HEADER_BYTES
        || getInt(payload, BATCH_RESERVED_OFFSET) != 0) {
      return StatusCode.CORRUPTION;
    }
    int entryCount = getInt(payload, BATCH_ENTRY_COUNT_OFFSET);
    if (entryCount <= 1 || entryCount > maximumEntries) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode validateMutationBatch(ByteBuffer payload, int maximumEntries) {
    if (!hasOperationType(payload, OPERATION_TYPE_MUTATION_BATCH)
        || payload.limit() < MUTATION_BATCH_HEADER_BYTES
        || getInt(payload, BATCH_RESERVED_OFFSET) != 0) {
      return StatusCode.CORRUPTION;
    }
    int entryCount = getInt(payload, BATCH_ENTRY_COUNT_OFFSET);
    if (entryCount <= 0 || entryCount > maximumEntries) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode validateVacuumChunk(
      ByteBuffer payload,
      long maximumRows,
      int maximumChunks) {
    if (!hasOperationType(payload, OPERATION_TYPE_VACUUM_CHUNK)
        || payload.limit() < VACUUM_CHUNK_HEADER_BYTES
        || getInt(payload, VACUUM_RESERVED_OFFSET) != 0) {
      return StatusCode.CORRUPTION;
    }
    long retainedRows = unsignedInt(payload, VACUUM_RETAINED_ROWS_OFFSET);
    long firstRow = unsignedInt(payload, VACUUM_FIRST_ROW_OFFSET);
    int rowCount = getInt(payload, VACUUM_ROW_COUNT_OFFSET);
    int chunk = getInt(payload, VACUUM_CHUNK_OFFSET);
    int chunkCount = getInt(payload, VACUUM_CHUNK_COUNT_OFFSET);
    if (retainedRows <= 0
        || retainedRows > maximumRows
        || firstRow < 0
        || rowCount <= 0
        || firstRow > retainedRows - rowCount
        || chunk < 0
        || chunk >= chunkCount
        || chunkCount > maximumChunks) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static boolean validInsertBatchEntry(ByteBuffer payload, int offset) {
    if (payload == null || offset < 0 || payload.limit() - offset < INSERT_BATCH_ENTRY_BYTES) {
      return false;
    }
    int rowBytes = getInt(payload, offset + INSERT_BATCH_ROW_BYTES_OFFSET);
    int entryBytes = insertBatchEntryBytes(rowBytes);
    return validLogicalRowId(
            unsignedInt(payload, offset + INSERT_BATCH_ROW_ID_OFFSET), false)
        && OrderedKey.isFiniteSpace(getInt(payload, offset + INSERT_BATCH_SPACE_OFFSET))
        && entryBytes > 0
        && payload.limit() - offset >= entryBytes;
  }

  static boolean validMutationBatchEntry(ByteBuffer payload, int offset) {
    if (payload == null || offset < 0 || payload.limit() - offset < MUTATION_BATCH_ENTRY_BYTES) {
      return false;
    }
    int rowBytes = getInt(payload, offset + MUTATION_ROW_BYTES_OFFSET);
    int entryBytes = mutationBatchEntryBytes(rowBytes);
    return isMutation(getInt(payload, offset + MUTATION_OPERATION_OFFSET))
        && validLogicalRowId(unsignedInt(payload, offset + MUTATION_ROW_ID_OFFSET), false)
        && validLogicalRowId(
            unsignedInt(payload, offset + MUTATION_PREVIOUS_ROW_ID_OFFSET), true)
        && OrderedKey.isFiniteSpace(getInt(payload, offset + MUTATION_SPACE_OFFSET))
        && entryBytes > 0
        && payload.limit() - offset >= entryBytes;
  }

  static boolean validVacuumEntry(ByteBuffer payload, int offset) {
    if (payload == null || offset < 0 || payload.limit() - offset < VACUUM_ENTRY_BYTES) {
      return false;
    }
    int rowBytes = getInt(payload, offset + VACUUM_ENTRY_ROW_BYTES_OFFSET);
    int deleted = getInt(payload, offset + VACUUM_ENTRY_DELETED_OFFSET);
    int entryBytes = vacuumEntryBytes(rowBytes);
    return validLogicalRowId(
            unsignedInt(payload, offset + VACUUM_ENTRY_ROW_ID_OFFSET), false)
        && (deleted == 0 || deleted == 1)
        && OrderedKey.isFiniteSpace(getInt(payload, offset + VACUUM_ENTRY_SPACE_OFFSET))
        && entryBytes > 0
        && payload.limit() - offset >= entryBytes;
  }

  static boolean validPageOperationVersion(ByteBuffer payload, int offset) {
    if (payload == null
        || offset < 0
        || payload.limit() - offset < PAGE_OPERATION_VERSION_BYTES) {
      return false;
    }
    int deleted = getInt(payload, offset + PAGE_VERSION_DELETED_OFFSET);
    return validLogicalRowId(
            unsignedInt(payload, offset + PAGE_VERSION_PREVIOUS_ROW_ID_OFFSET), true)
        && (deleted == 0 || deleted == 1);
  }

  static int pageOperationPageCount(ByteBuffer payload) {
    return getInt(payload, BATCH_ENTRY_COUNT_OFFSET);
  }

  static int pageOperationVersionCount(ByteBuffer payload) {
    return getInt(payload, PAGE_VERSION_COUNT_OFFSET);
  }

  static int pageOperationPageOffset(int index) {
    return PAGE_OPERATION_HEADER_BYTES + index * PageCodec.PAGE_BYTES;
  }

  static int pageOperationVersionsOffset(int pageCount) {
    return PAGE_OPERATION_HEADER_BYTES + pageCount * PageCodec.PAGE_BYTES;
  }

  static long insertKey(ByteBuffer payload) {
    return getLong(payload, INSERT_KEY_OFFSET);
  }

  static int insertSpace(ByteBuffer payload) {
    return getInt(payload, INSERT_SPACE_OFFSET);
  }

  static long insertRowId(ByteBuffer payload) {
    return unsignedInt(payload, 24);
  }

  static int insertRowBytes(ByteBuffer payload) {
    return getInt(payload, INSERT_ROW_BYTES_OFFSET);
  }

  static int batchEntryCount(ByteBuffer payload) {
    return getInt(payload, BATCH_ENTRY_COUNT_OFFSET);
  }

  static long insertBatchKey(ByteBuffer payload, int offset) {
    return getLong(payload, offset + INSERT_BATCH_KEY_OFFSET);
  }

  static int insertBatchSpace(ByteBuffer payload, int offset) {
    return getInt(payload, offset + INSERT_BATCH_SPACE_OFFSET);
  }

  static long insertBatchRowId(ByteBuffer payload, int offset) {
    return unsignedInt(payload, offset + INSERT_BATCH_ROW_ID_OFFSET);
  }

  static int insertBatchRowBytes(ByteBuffer payload, int offset) {
    return getInt(payload, offset + INSERT_BATCH_ROW_BYTES_OFFSET);
  }

  static int mutationOperation(ByteBuffer payload, int offset) {
    return getInt(payload, offset + MUTATION_OPERATION_OFFSET);
  }

  static long mutationKey(ByteBuffer payload, int offset) {
    return getLong(payload, offset + MUTATION_KEY_OFFSET);
  }

  static int mutationSpace(ByteBuffer payload, int offset) {
    return getInt(payload, offset + MUTATION_SPACE_OFFSET);
  }

  static long mutationRowId(ByteBuffer payload, int offset) {
    return unsignedInt(payload, offset + MUTATION_ROW_ID_OFFSET);
  }

  static long mutationPreviousRowId(ByteBuffer payload, int offset) {
    return unsignedInt(payload, offset + MUTATION_PREVIOUS_ROW_ID_OFFSET);
  }

  static int mutationRowBytes(ByteBuffer payload, int offset) {
    return getInt(payload, offset + MUTATION_ROW_BYTES_OFFSET);
  }

  static int encodedRowBytes(ByteBuffer payload, int entryOffset, int rowLengthOffset) {
    return getInt(payload, entryOffset + rowLengthOffset);
  }

  static long pageVersionPreviousRowId(ByteBuffer payload, int offset) {
    return unsignedInt(payload, offset);
  }

  static boolean pageVersionDeleted(ByteBuffer payload, int offset) {
    return getInt(payload, offset + PAGE_VERSION_DELETED_OFFSET) == 1;
  }

  static long vacuumRetainedRows(ByteBuffer payload) {
    return unsignedInt(payload, 16);
  }

  static long vacuumFirstRow(ByteBuffer payload) {
    return unsignedInt(payload, 20);
  }

  static int vacuumRowCount(ByteBuffer payload) {
    return getInt(payload, VACUUM_ROW_COUNT_OFFSET);
  }

  static int vacuumChunk(ByteBuffer payload) {
    return getInt(payload, VACUUM_CHUNK_OFFSET);
  }

  static int vacuumChunkCount(ByteBuffer payload) {
    return getInt(payload, VACUUM_CHUNK_COUNT_OFFSET);
  }

  static long vacuumEntryKey(ByteBuffer payload, int offset) {
    return getLong(payload, offset + VACUUM_ENTRY_KEY_OFFSET);
  }

  static int vacuumEntrySpace(ByteBuffer payload, int offset) {
    return getInt(payload, offset + VACUUM_ENTRY_SPACE_OFFSET);
  }

  static long vacuumEntryRowId(ByteBuffer payload, int offset) {
    return unsignedInt(payload, offset + VACUUM_ENTRY_ROW_ID_OFFSET);
  }

  static int vacuumEntryRowBytes(ByteBuffer payload, int offset) {
    return getInt(payload, offset + VACUUM_ENTRY_ROW_BYTES_OFFSET);
  }

  static boolean vacuumEntryDeleted(ByteBuffer payload, int offset) {
    return getInt(payload, offset + VACUUM_ENTRY_DELETED_OFFSET) == 1;
  }

  static long vacuumCommitRowsBefore(ByteBuffer payload) {
    return unsignedInt(payload, 24);
  }

  static int vacuumCommitChunkCount(ByteBuffer payload) {
    return getInt(payload, VACUUM_COMMIT_CHUNK_COUNT_OFFSET);
  }

  static StatusCode validateVacuumCommit(
      ByteBuffer payload,
      long maximumRows,
      int maximumChunks) {
    if (!hasOperationType(payload, OPERATION_TYPE_VACUUM_COMMIT)
        || payload.limit() != VACUUM_COMMIT_PAYLOAD_BYTES
        || unsignedInt(payload, 16) > maximumRows
        || getInt(payload, VACUUM_COMMIT_CHUNK_COUNT_OFFSET) <= 0
        || getInt(payload, VACUUM_COMMIT_CHUNK_COUNT_OFFSET) > maximumChunks
        || unsignedInt(payload, 24) < unsignedInt(payload, 16)
        || getInt(payload, VACUUM_COMMIT_RESERVED_OFFSET) != 0) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static boolean containsEarlierPageId(int[] pageIds, int count, int pageId) {
    for (int index = 0; index < count; index++) {
      if (pageIds[index] == pageId) {
        return true;
      }
    }
    return false;
  }

  static void putInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) value);
    target.put(offset + 1, (byte) (value >>> 8));
    target.put(offset + 2, (byte) (value >>> 16));
    target.put(offset + 3, (byte) (value >>> 24));
  }

  private static int getInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset))
        | Byte.toUnsignedInt(source.get(offset + 1)) << 8
        | Byte.toUnsignedInt(source.get(offset + 2)) << 16
        | Byte.toUnsignedInt(source.get(offset + 3)) << 24;
  }

  private static long unsignedInt(ByteBuffer source, int offset) {
    return Integer.toUnsignedLong(getInt(source, offset));
  }

  static void putLong(ByteBuffer target, int offset, long value) {
    putInt(target, offset, (int) value);
    putInt(target, offset + LONG_HIGH_WORD_OFFSET, (int) (value >>> 32));
  }

  private static long getLong(ByteBuffer source, int offset) {
    return Integer.toUnsignedLong(getInt(source, offset))
        | Integer.toUnsignedLong(getInt(source, offset + LONG_HIGH_WORD_OFFSET)) << 32;
  }

  private static void encodeCommonHeader(ByteBuffer target, int operationType) {
    putLong(target, COMMON_MAGIC_OFFSET, OPERATION_MAGIC);
    putInt(target, COMMON_VERSION_OFFSET, FORMAT_VERSION);
    putInt(target, COMMON_TYPE_OFFSET, operationType);
  }

  private static boolean hasOperationType(ByteBuffer payload, int operationType) {
    return hasCommonHeader(payload) && getInt(payload, COMMON_TYPE_OFFSET) == operationType;
  }

  private static boolean isMutation(int operation) {
    return operation == MUTATION_INSERT
        || operation == MUTATION_UPDATE
        || operation == MUTATION_DELETE;
  }

  private static boolean validLogicalRowId(long rowId, boolean allowZero) {
    return (allowZero ? rowId >= 0 : rowId > 0) && rowId <= MAX_LOGICAL_ROW_ID;
  }

  private static int checkedVariableBytes(int fixedBytes, int variableBytes) {
    return variableBytes > 0 && variableBytes <= Integer.MAX_VALUE - fixedBytes
        ? fixedBytes + variableBytes : 0;
  }
}
