package io.riverdb.engine.table;

/** Checked aggregate measurement for scalar, tuple, floor, and compilation workspaces. */
final class IndexedWriteWorkspaceAccounting {
  private IndexedWriteWorkspaceAccounting() {}

  static long scalar(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors,
      int rowBytes) {
    long scalarBytes = pending.accountedBytesForReservation(1, rowBytes);
    return combineScalarAndTuple(
        scalarBytes, tuples, lifecycle, floors.accountedBytes(),
        pending.count() + 1, add(pending.payloadBytes(), rowBytes),
        0, 0, 0, floors.count());
  }

  static long scalar(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors,
      int[] rowLengths, int start, int count) {
    long scalarBytes = pending.accountedBytesForReservation(rowLengths, start, count);
    long additionalPayload = payloadBytes(rowLengths, start, count);
    return combineScalarAndTuple(
        scalarBytes, tuples, lifecycle, floors.accountedBytes(),
        add(pending.count(), count), add(pending.payloadBytes(), additionalPayload),
        0, 0, 0, floors.count());
  }

  static long tuples(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors,
      int mutations, int descriptors, int payloadBytes) {
    return combineScalarAndTuple(
        pending.accountedBytes(), tuples, lifecycle, floors.accountedBytes(),
        pending.count(), pending.payloadBytes(), mutations, descriptors, payloadBytes,
        floors.count());
  }

  static long combined(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors,
      int[] rowLengths, int start, int scalarRows,
      int tupleMutations, int descriptors, int tuplePayloadBytes) {
    long scalarBytes = scalarRows == 0 ? pending.accountedBytes()
        : pending.accountedBytesForReservation(rowLengths, start, scalarRows);
    long additionalPayload = scalarRows == 0 ? 0
        : payloadBytes(rowLengths, start, scalarRows);
    return combineScalarAndTuple(
        scalarBytes, tuples, lifecycle, floors.accountedBytes(),
        add(pending.count(), scalarRows), add(pending.payloadBytes(), additionalPayload),
        tupleMutations, descriptors, tuplePayloadBytes, floors.count());
  }

  static long current(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors) {
    return combineScalarAndTuple(
        pending.accountedBytes(), tuples, lifecycle, floors.accountedBytes(),
        pending.count(), pending.payloadBytes(), 0, 0, 0, floors.count());
  }

  static long lifecycle(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors,
      int additional) {
    if (additional <= 0 || lifecycle.count() > Integer.MAX_VALUE - additional) return -1;
    int descriptors = lifecycle.count() + additional;
    long parts = (long) lifecycle.partCount()
        + (long) additional * io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS;
    if (parts > Integer.MAX_VALUE) return -1;
    long tupleBytes = tuples.accountedBytesForLifecycleReservation(
        pending.count(), pending.payloadBytes(), 0, 0, 0,
        descriptors, (int) parts, floors.count());
    return add(
        add(add(pending.accountedBytes(), tupleBytes),
            lifecycle.accountedBytesForReservation(additional)),
        floors.accountedBytes());
  }

  static long floor(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors,
      long objectId) {
    int floorCount = floors.countAfterRecord(objectId);
    long floorBytes = floors.accountedBytesForRecord(objectId);
    if (floorCount < 0 || floorBytes < 0) return -1;
    return combineScalarAndTuple(
        pending.accountedBytes(), tuples, lifecycle, floorBytes,
        pending.count(), pending.payloadBytes(), 0, 0, 0, floorCount);
  }

  private static long combineScalarAndTuple(
      long scalarBytes, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, long floorBytes,
      long scalarMutations, long scalarPayloadBytes,
      int tupleMutations, int descriptors, int tuplePayloadBytes,
      int logicalRowFloors) {
    if (scalarMutations < 0 || scalarMutations > Integer.MAX_VALUE
        || scalarPayloadBytes < 0 || scalarPayloadBytes > Integer.MAX_VALUE
        || logicalRowFloors < 0) return -1;
    long tupleBytes = lifecycle.active()
        ? tuples.accountedBytesForLifecycleReservation(
            (int) scalarMutations, (int) scalarPayloadBytes,
            tupleMutations, descriptors, tuplePayloadBytes,
            lifecycle.count(), lifecycle.partCount(), logicalRowFloors)
        : tuples.accountedBytesForReservation(
            (int) scalarMutations, (int) scalarPayloadBytes,
            tupleMutations, descriptors, tuplePayloadBytes, logicalRowFloors);
    return add(add(add(scalarBytes, tupleBytes), lifecycle.accountedBytes()), floorBytes);
  }

  private static long payloadBytes(int[] lengths, int start, int count) {
    if (lengths == null || start < 0 || count < 0 || start > lengths.length - count) return -1;
    long total = 0;
    for (int index = 0; index < count; index++) total = add(total, lengths[start + index]);
    return total;
  }

  private static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }
}
