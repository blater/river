package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Chunked primitive columns backing relational recovery suboperations. */
final class IndexedRelationalSuboperationColumns {
  static final int OWNER = 0;
  static final int KEY_ID = 1;
  static final int EXPECTED_GENERATION = 2;
  static final int RESULTING_GENERATION = 3;
  static final int EXPECTED_HEAP_VERSION = 4;
  static final int RESULTING_HEAP_VERSION = 5;
  static final int EXPECTED_PRIVATE_OWNER = 6;
  static final int RESULTING_PRIVATE_OWNER = 7;

  static final int DESCRIPTOR = 0;
  static final int FIRST_MUTATION = 1;
  static final int MUTATION_COUNT = 2;
  static final int EXPECTED_TUPLE_ROOT = 3;
  static final int RESULTING_TUPLE_ROOT = 4;
  static final int EXPECTED_SCALAR_ROOT = 5;
  static final int RESULTING_SCALAR_ROOT = 6;
  static final int EXPECTED_NEXT_PAGE = 7;
  static final int RESULTING_NEXT_PAGE = 8;
  static final int EXPECTED_REGISTRY_STATE = 9;
  static final int RESULTING_REGISTRY_STATE = 10;
  static final int EXPECTED_CLEANUP_CURSOR = 11;
  static final int RESULTING_CLEANUP_CURSOR = 12;

  private final IndexedLongChunks[] longs = new IndexedLongChunks[8];
  private final IndexedIntChunks[] ints = new IndexedIntChunks[13];

  IndexedRelationalSuboperationColumns(int capacity) {
    for (int index = 0; index < longs.length; index++) longs[index] = new IndexedLongChunks(capacity);
    for (int index = 0; index < ints.length; index++) ints[index] = new IndexedIntChunks(capacity);
  }

  StatusCode reserve(int required) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < longs.length; index++) {
      status = longs[index].reserve(required);
    }
    for (int index = 0; status.isOk() && index < ints.length; index++) {
      status = ints[index].reserve(required);
    }
    return status;
  }

  int capacity() { return longs[OWNER].capacity(); }
  int allocatedCapacity() { return longs[OWNER].allocatedCapacity(); }
  long getLong(int column, int index) { return longs[column].get(index); }
  int getInt(int column, int index) { return ints[column].get(index); }
  void setLong(int column, int index, long value) { longs[column].set(index, value); }
  void setInt(int column, int index, int value) { ints[column].set(index, value); }

  void append(
      int index, long owner, long keyId, int descriptor, int firstMutation, int mutationCount,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      long expectedGeneration, long resultingGeneration,
      long expectedHeapVersion, long resultingHeapVersion,
      int expectedRegistryState, int resultingRegistryState,
      long expectedPrivateOwner, long resultingPrivateOwner,
      int expectedCleanupCursor, int resultingCleanupCursor) {
    setLong(OWNER, index, owner); setLong(KEY_ID, index, keyId);
    setInt(DESCRIPTOR, index, descriptor); setInt(FIRST_MUTATION, index, firstMutation);
    setInt(MUTATION_COUNT, index, mutationCount);
    setInt(EXPECTED_TUPLE_ROOT, index, expectedTupleRoot);
    setInt(RESULTING_TUPLE_ROOT, index, resultingTupleRoot);
    setInt(EXPECTED_SCALAR_ROOT, index, expectedScalarRoot);
    setInt(RESULTING_SCALAR_ROOT, index, resultingScalarRoot);
    setInt(EXPECTED_NEXT_PAGE, index, expectedNextPage);
    setInt(RESULTING_NEXT_PAGE, index, resultingNextPage);
    setLong(EXPECTED_GENERATION, index, expectedGeneration);
    setLong(RESULTING_GENERATION, index, resultingGeneration);
    setLong(EXPECTED_HEAP_VERSION, index, expectedHeapVersion);
    setLong(RESULTING_HEAP_VERSION, index, resultingHeapVersion);
    setInt(EXPECTED_REGISTRY_STATE, index, expectedRegistryState);
    setInt(RESULTING_REGISTRY_STATE, index, resultingRegistryState);
    setLong(EXPECTED_PRIVATE_OWNER, index, expectedPrivateOwner);
    setLong(RESULTING_PRIVATE_OWNER, index, resultingPrivateOwner);
    setInt(EXPECTED_CLEANUP_CURSOR, index, expectedCleanupCursor);
    setInt(RESULTING_CLEANUP_CURSOR, index, resultingCleanupCursor);
  }

  long allocatedBytes() {
    long bytes = 64L;
    for (IndexedLongChunks column : longs) bytes += column.allocatedBytes();
    for (IndexedIntChunks column : ints) bytes += column.allocatedBytes();
    return bytes;
  }

  long accountedBytesForCapacity(int required) {
    long longBytes = longs[OWNER].accountedBytesForCapacity(required);
    long intBytes = ints[DESCRIPTOR].accountedBytesForCapacity(required);
    return longBytes < 0 || intBytes < 0 ? -1 : 8L * longBytes + 12L * intBytes + 64L;
  }

  void release() {
    for (IndexedLongChunks column : longs) column.release();
    for (IndexedIntChunks column : ints) column.release();
  }
}
