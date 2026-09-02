package io.riverdb.engine.table;

import io.riverdb.format.btree.TupleIndexRootRecordCodec;

/** Read-only primitive view of retained relational suboperations. */
class IndexedRelationalSuboperationView {
  static final int REGISTRY_ABSENT = 0;
  static final int REGISTRY_BUILDING = TupleIndexRootRecordCodec.STATE_BUILDING;
  static final int REGISTRY_READY = TupleIndexRootRecordCodec.STATE_READY;
  static final int REGISTRY_DROPPING = TupleIndexRootRecordCodec.STATE_DROPPING;

  final IndexedRelationalSuboperationColumns columns;
  int count;

  IndexedRelationalSuboperationView(int capacity) {
    columns = new IndexedRelationalSuboperationColumns(capacity);
  }

  boolean acceptsMutation(int index, int mutation, long owner, int descriptor) {
    return index >= 0 && index < count && ownerAt(index) == owner
        && descriptorAt(index) == descriptor && mutation >= firstMutationAt(index)
        && mutation < firstMutationAt(index) + mutationCountAt(index);
  }
  int capacity() { return columns.capacity(); }
  int count() { return count; }
  long ownerAt(int i) { return longAt(IndexedRelationalSuboperationColumns.OWNER, i); }
  int descriptorAt(int i) { return intAt(IndexedRelationalSuboperationColumns.DESCRIPTOR, i); }
  int firstMutationAt(int i) { return intAt(IndexedRelationalSuboperationColumns.FIRST_MUTATION, i); }
  int mutationCountAt(int i) { return intAt(IndexedRelationalSuboperationColumns.MUTATION_COUNT, i); }
  int expectedTupleRootAt(int i) { return intAt(IndexedRelationalSuboperationColumns.EXPECTED_TUPLE_ROOT, i); }
  int resultingTupleRootAt(int i) { return intAt(IndexedRelationalSuboperationColumns.RESULTING_TUPLE_ROOT, i); }
  int expectedScalarRootAt(int i) { return intAt(IndexedRelationalSuboperationColumns.EXPECTED_SCALAR_ROOT, i); }
  int resultingScalarRootAt(int i) { return intAt(IndexedRelationalSuboperationColumns.RESULTING_SCALAR_ROOT, i); }
  int expectedNextPageAt(int i) { return intAt(IndexedRelationalSuboperationColumns.EXPECTED_NEXT_PAGE, i); }
  int resultingNextPageAt(int i) { return intAt(IndexedRelationalSuboperationColumns.RESULTING_NEXT_PAGE, i); }
  long expectedGenerationAt(int i) { return longAt(IndexedRelationalSuboperationColumns.EXPECTED_GENERATION, i); }
  long resultingGenerationAt(int i) { return longAt(IndexedRelationalSuboperationColumns.RESULTING_GENERATION, i); }
  long expectedHeapVersionAt(int i) { return longAt(IndexedRelationalSuboperationColumns.EXPECTED_HEAP_VERSION, i); }
  long resultingHeapVersionAt(int i) { return longAt(IndexedRelationalSuboperationColumns.RESULTING_HEAP_VERSION, i); }
  int expectedRegistryStateAt(int i) { return intAt(IndexedRelationalSuboperationColumns.EXPECTED_REGISTRY_STATE, i); }
  int resultingRegistryStateAt(int i) { return intAt(IndexedRelationalSuboperationColumns.RESULTING_REGISTRY_STATE, i); }
  long expectedPrivateOwnerAt(int i) { return longAt(IndexedRelationalSuboperationColumns.EXPECTED_PRIVATE_OWNER, i); }
  long resultingPrivateOwnerAt(int i) { return longAt(IndexedRelationalSuboperationColumns.RESULTING_PRIVATE_OWNER, i); }
  int expectedCleanupCursorAt(int i) { return intAt(IndexedRelationalSuboperationColumns.EXPECTED_CLEANUP_CURSOR, i); }
  int resultingCleanupCursorAt(int i) { return intAt(IndexedRelationalSuboperationColumns.RESULTING_CLEANUP_CURSOR, i); }

  private long longAt(int column, int index) { return columns.getLong(column, index); }
  private int intAt(int column, int index) { return columns.getInt(column, index); }
}
