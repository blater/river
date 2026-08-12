package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Byte-exact indexed-table WAL layout without WAL publication or table-state semantics. */
final class IndexedWalCodec {
  static final int FORMAT_ID = 1002;
  static final int FORMAT_VERSION = 3;

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
  static final int INSERT_BATCH_ENTRY_BYTES = 16;
  static final int MUTATION_BATCH_HEADER_BYTES = 24;
  static final int MUTATION_BATCH_ENTRY_BYTES = 24;
  static final int VACUUM_CHUNK_HEADER_BYTES = 40;
  static final int VACUUM_ENTRY_BYTES = 24;
  static final int VACUUM_COMMIT_PAYLOAD_BYTES = 32;

  private static final int MAGIC_OFFSET = 0;
  private static final int VERSION_OFFSET = 8;
  private static final int TYPE_OFFSET = 12;

  private IndexedWalCodec() {
  }

  static int operationType(ByteBuffer payload) {
    return hasCommonHeader(payload) ? getInt(payload, TYPE_OFFSET) : 0;
  }

  static boolean hasCommonHeader(ByteBuffer payload) {
    return payload != null
        && payload.limit() >= PAGE_OPERATION_HEADER_BYTES
        && getLong(payload, MAGIC_OFFSET) == OPERATION_MAGIC
        && getInt(payload, VERSION_OFFSET) == FORMAT_VERSION;
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
    putInt(target, 16, pageCount);
    putInt(target, 20, versionCount);
  }

  static void encodePageOperationVersion(
      ByteBuffer target,
      int offset,
      int previousRowId,
      boolean deleted) {
    putInt(target, offset, previousRowId);
    putInt(target, offset + 4, deleted ? 1 : 0);
  }

  static void encodeInsertHeader(
      ByteBuffer target,
      long key,
      int rowId,
      int rowBytes) {
    encodeCommonHeader(target, OPERATION_TYPE_INSERT);
    putLong(target, 16, key);
    putInt(target, 24, rowId);
    putInt(target, 28, rowBytes);
    putLong(target, 32, 0);
  }

  static void encodeInsertBatchHeader(ByteBuffer target, int entryCount) {
    encodeCommonHeader(target, OPERATION_TYPE_INSERT_BATCH);
    putInt(target, 16, entryCount);
    putInt(target, 20, 0);
  }

  static void encodeInsertBatchEntry(
      ByteBuffer target,
      int offset,
      long key,
      int rowId,
      int rowBytes) {
    putLong(target, offset, key);
    putInt(target, offset + 8, rowId);
    putInt(target, offset + 12, rowBytes);
  }

  static void encodeMutationBatchHeader(ByteBuffer target, int entryCount) {
    encodeCommonHeader(target, OPERATION_TYPE_MUTATION_BATCH);
    putInt(target, 16, entryCount);
    putInt(target, 20, 0);
  }

  static void encodeMutationBatchEntry(
      ByteBuffer target,
      int offset,
      int operation,
      long key,
      int rowId,
      int previousRowId,
      int rowBytes) {
    putInt(target, offset, operation);
    putLong(target, offset + 4, key);
    putInt(target, offset + 12, rowId);
    putInt(target, offset + 16, previousRowId);
    putInt(target, offset + 20, rowBytes);
  }

  static void encodeVacuumChunkHeader(
      ByteBuffer target,
      int retainedRows,
      int firstRow,
      int rowCount,
      int chunk,
      int chunkCount) {
    encodeCommonHeader(target, OPERATION_TYPE_VACUUM_CHUNK);
    putInt(target, 16, retainedRows);
    putInt(target, 20, firstRow);
    putInt(target, 24, rowCount);
    putInt(target, 28, chunk);
    putInt(target, 32, chunkCount);
    putInt(target, 36, 0);
  }

  static void encodeVacuumEntry(
      ByteBuffer target,
      int offset,
      long key,
      int rowId,
      int rowBytes,
      boolean deleted) {
    putLong(target, offset, key);
    putInt(target, offset + 8, rowId);
    putInt(target, offset + 12, rowBytes);
    putInt(target, offset + 16, deleted ? 1 : 0);
    putInt(target, offset + 20, 0);
  }

  static void encodeVacuumCommit(
      ByteBuffer target,
      int retainedRows,
      int chunkCount,
      int rowsBefore) {
    encodeCommonHeader(target, OPERATION_TYPE_VACUUM_COMMIT);
    putInt(target, 16, retainedRows);
    putInt(target, 20, chunkCount);
    putInt(target, 24, rowsBefore);
    putInt(target, 28, 0);
  }

  static StatusCode validatePageOperation(
      ByteBuffer payload,
      int maximumPageCount,
      int maximumVersionCount) {
    if (!hasOperationType(payload, OPERATION_TYPE_PAGE_IMAGES)) {
      return StatusCode.CORRUPTION;
    }
    int pageCount = getInt(payload, 16);
    int versionCount = getInt(payload, 20);
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
        || getInt(payload, 24) <= 0
        || getInt(payload, 28) <= 0
        || getLong(payload, 32) != 0
        || payload.limit() != insertOperationBytes(getInt(payload, 28))) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode validateInsertBatch(ByteBuffer payload, int maximumEntries) {
    if (!hasOperationType(payload, OPERATION_TYPE_INSERT_BATCH)
        || payload.limit() < INSERT_BATCH_HEADER_BYTES
        || getInt(payload, 20) != 0) {
      return StatusCode.CORRUPTION;
    }
    int entryCount = getInt(payload, 16);
    if (entryCount <= 1 || entryCount > maximumEntries) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode validateMutationBatch(ByteBuffer payload, int maximumEntries) {
    if (!hasOperationType(payload, OPERATION_TYPE_MUTATION_BATCH)
        || payload.limit() < MUTATION_BATCH_HEADER_BYTES
        || getInt(payload, 20) != 0) {
      return StatusCode.CORRUPTION;
    }
    int entryCount = getInt(payload, 16);
    if (entryCount <= 0 || entryCount > maximumEntries) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  static StatusCode validateVacuumChunk(
      ByteBuffer payload,
      int maximumRows,
      int maximumChunks) {
    if (!hasOperationType(payload, OPERATION_TYPE_VACUUM_CHUNK)
        || payload.limit() < VACUUM_CHUNK_HEADER_BYTES
        || getInt(payload, 36) != 0) {
      return StatusCode.CORRUPTION;
    }
    int retainedRows = getInt(payload, 16);
    int firstRow = getInt(payload, 20);
    int rowCount = getInt(payload, 24);
    int chunk = getInt(payload, 28);
    int chunkCount = getInt(payload, 32);
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
    int rowBytes = getInt(payload, offset + 12);
    int entryBytes = insertBatchEntryBytes(rowBytes);
    return getInt(payload, offset + 8) > 0
        && entryBytes > 0
        && payload.limit() - offset >= entryBytes;
  }

  static boolean validMutationBatchEntry(ByteBuffer payload, int offset) {
    if (payload == null || offset < 0 || payload.limit() - offset < MUTATION_BATCH_ENTRY_BYTES) {
      return false;
    }
    int rowBytes = getInt(payload, offset + 20);
    int entryBytes = mutationBatchEntryBytes(rowBytes);
    return isMutation(getInt(payload, offset))
        && getInt(payload, offset + 12) > 0
        && getInt(payload, offset + 16) >= 0
        && entryBytes > 0
        && payload.limit() - offset >= entryBytes;
  }

  static boolean validVacuumEntry(ByteBuffer payload, int offset) {
    if (payload == null || offset < 0 || payload.limit() - offset < VACUUM_ENTRY_BYTES) {
      return false;
    }
    int rowBytes = getInt(payload, offset + 12);
    int deleted = getInt(payload, offset + 16);
    int entryBytes = vacuumEntryBytes(rowBytes);
    return getInt(payload, offset + 8) > 0
        && (deleted == 0 || deleted == 1)
        && getInt(payload, offset + 20) == 0
        && entryBytes > 0
        && payload.limit() - offset >= entryBytes;
  }

  static boolean validPageOperationVersion(ByteBuffer payload, int offset) {
    if (payload == null
        || offset < 0
        || payload.limit() - offset < PAGE_OPERATION_VERSION_BYTES) {
      return false;
    }
    int deleted = getInt(payload, offset + 4);
    return getInt(payload, offset) >= 0 && (deleted == 0 || deleted == 1);
  }

  static int pageOperationPageCount(ByteBuffer payload) {
    return getInt(payload, 16);
  }

  static int pageOperationVersionCount(ByteBuffer payload) {
    return getInt(payload, 20);
  }

  static int pageOperationPageOffset(int index) {
    return PAGE_OPERATION_HEADER_BYTES + index * PageCodec.PAGE_BYTES;
  }

  static int pageOperationVersionsOffset(int pageCount) {
    return PAGE_OPERATION_HEADER_BYTES + pageCount * PageCodec.PAGE_BYTES;
  }

  static long insertKey(ByteBuffer payload) {
    return getLong(payload, 16);
  }

  static int insertRowId(ByteBuffer payload) {
    return getInt(payload, 24);
  }

  static int insertRowBytes(ByteBuffer payload) {
    return getInt(payload, 28);
  }

  static int batchEntryCount(ByteBuffer payload) {
    return getInt(payload, 16);
  }

  static long insertBatchKey(ByteBuffer payload, int offset) {
    return getLong(payload, offset);
  }

  static int insertBatchRowId(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 8);
  }

  static int insertBatchRowBytes(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 12);
  }

  static int mutationOperation(ByteBuffer payload, int offset) {
    return getInt(payload, offset);
  }

  static long mutationKey(ByteBuffer payload, int offset) {
    return getLong(payload, offset + 4);
  }

  static int mutationRowId(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 12);
  }

  static int mutationPreviousRowId(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 16);
  }

  static int mutationRowBytes(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 20);
  }

  static int encodedRowBytes(ByteBuffer payload, int entryOffset, int rowLengthOffset) {
    return getInt(payload, entryOffset + rowLengthOffset);
  }

  static int pageVersionPreviousRowId(ByteBuffer payload, int offset) {
    return getInt(payload, offset);
  }

  static boolean pageVersionDeleted(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 4) == 1;
  }

  static int vacuumRetainedRows(ByteBuffer payload) {
    return getInt(payload, 16);
  }

  static int vacuumFirstRow(ByteBuffer payload) {
    return getInt(payload, 20);
  }

  static int vacuumRowCount(ByteBuffer payload) {
    return getInt(payload, 24);
  }

  static int vacuumChunk(ByteBuffer payload) {
    return getInt(payload, 28);
  }

  static int vacuumChunkCount(ByteBuffer payload) {
    return getInt(payload, 32);
  }

  static long vacuumEntryKey(ByteBuffer payload, int offset) {
    return getLong(payload, offset);
  }

  static int vacuumEntryRowId(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 8);
  }

  static int vacuumEntryRowBytes(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 12);
  }

  static boolean vacuumEntryDeleted(ByteBuffer payload, int offset) {
    return getInt(payload, offset + 16) == 1;
  }

  static int vacuumCommitRowsBefore(ByteBuffer payload) {
    return getInt(payload, 24);
  }

  static int vacuumCommitChunkCount(ByteBuffer payload) {
    return getInt(payload, 20);
  }

  static StatusCode validateVacuumCommit(
      ByteBuffer payload,
      int maximumRows,
      int maximumChunks) {
    if (!hasOperationType(payload, OPERATION_TYPE_VACUUM_COMMIT)
        || payload.limit() != VACUUM_COMMIT_PAYLOAD_BYTES
        || getInt(payload, 16) < 0
        || getInt(payload, 16) > maximumRows
        || getInt(payload, 20) <= 0
        || getInt(payload, 20) > maximumChunks
        || getInt(payload, 24) < getInt(payload, 16)
        || getInt(payload, 28) != 0) {
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

  static void putLong(ByteBuffer target, int offset, long value) {
    putInt(target, offset, (int) value);
    putInt(target, offset + 4, (int) (value >>> 32));
  }

  private static long getLong(ByteBuffer source, int offset) {
    return Integer.toUnsignedLong(getInt(source, offset))
        | Integer.toUnsignedLong(getInt(source, offset + 4)) << 32;
  }

  private static void encodeCommonHeader(ByteBuffer target, int operationType) {
    putLong(target, MAGIC_OFFSET, OPERATION_MAGIC);
    putInt(target, VERSION_OFFSET, FORMAT_VERSION);
    putInt(target, TYPE_OFFSET, operationType);
  }

  private static boolean hasOperationType(ByteBuffer payload, int operationType) {
    return hasCommonHeader(payload) && getInt(payload, TYPE_OFFSET) == operationType;
  }

  private static boolean isMutation(int operation) {
    return operation == MUTATION_INSERT
        || operation == MUTATION_UPDATE
        || operation == MUTATION_DELETE;
  }

  private static int checkedVariableBytes(int fixedBytes, int variableBytes) {
    return variableBytes > 0 && variableBytes <= Integer.MAX_VALUE - fixedBytes
        ? fixedBytes + variableBytes : 0;
  }
}
