package io.riverdb.engine.table;

/** Checked aggregate measurement for scalar, tuple-intent, and compilation workspaces. */
final class IndexedWriteWorkspaceAccounting {
  private IndexedWriteWorkspaceAccounting() {}

  static long scalar(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, int rowBytes) {
    long scalarBytes = pending.accountedBytesForReservation(1, rowBytes);
    return combineScalarAndTuple(
        scalarBytes, tuples, lifecycle, pending.count() + 1,
        add(pending.payloadBytes(), rowBytes), 0, 0, 0);
  }

  static long scalar(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle,
      int[] rowLengths, int start, int count) {
    long scalarBytes = pending.accountedBytesForReservation(rowLengths, start, count);
    long additionalPayload = payloadBytes(rowLengths, start, count);
    return combineScalarAndTuple(
        scalarBytes, tuples, lifecycle, add(pending.count(), count),
        add(pending.payloadBytes(), additionalPayload), 0, 0, 0);
  }

  static long tuples(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle,
      int mutations, int descriptors, int payloadBytes) {
    return combineScalarAndTuple(
        pending.accountedBytes(), tuples, lifecycle,
        pending.count(), pending.payloadBytes(), mutations, descriptors, payloadBytes);
  }

  static long combined(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle,
      int[] rowLengths, int start, int scalarRows,
      int tupleMutations, int descriptors, int tuplePayloadBytes) {
    long scalarBytes = scalarRows == 0 ? pending.accountedBytes()
        : pending.accountedBytesForReservation(rowLengths, start, scalarRows);
    long additionalPayload = scalarRows == 0 ? 0
        : payloadBytes(rowLengths, start, scalarRows);
    return combineScalarAndTuple(
        scalarBytes, tuples, lifecycle, add(pending.count(), scalarRows),
        add(pending.payloadBytes(), additionalPayload),
        tupleMutations, descriptors, tuplePayloadBytes);
  }

  static long current(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle) {
    return combineScalarAndTuple(
        pending.accountedBytes(), tuples, lifecycle,
        pending.count(), pending.payloadBytes(), 0, 0, 0);
  }

  static long lifecycle(
      PendingMutationBuffer pending, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle, int additional) {
    if (additional <= 0 || lifecycle.count() > Integer.MAX_VALUE - additional) return -1;
    int descriptors = lifecycle.count() + additional;
    long parts = (long) lifecycle.partCount()
        + (long) additional * io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS;
    if (parts > Integer.MAX_VALUE) return -1;
    long tupleBytes = tuples.accountedBytesForLifecycleReservation(
        pending.count(), pending.payloadBytes(), 0, 0, 0,
        descriptors, (int) parts);
    return add(add(pending.accountedBytes(), tupleBytes),
        lifecycle.accountedBytesForReservation(additional));
  }

  private static long combineScalarAndTuple(
      long scalarBytes, IndexedTupleIntentJournal tuples,
      IndexedTupleIndexLifecycleBatch lifecycle,
      long scalarMutations, long scalarPayloadBytes,
      int tupleMutations, int descriptors, int tuplePayloadBytes) {
    if (scalarMutations < 0 || scalarMutations > Integer.MAX_VALUE
        || scalarPayloadBytes < 0 || scalarPayloadBytes > Integer.MAX_VALUE) return -1;
    long tupleBytes = lifecycle.active()
        ? tuples.accountedBytesForLifecycleReservation(
            (int) scalarMutations, (int) scalarPayloadBytes,
            tupleMutations, descriptors, tuplePayloadBytes,
            lifecycle.count(), lifecycle.partCount())
        : tuples.accountedBytesForReservation(
            (int) scalarMutations, (int) scalarPayloadBytes,
            tupleMutations, descriptors, tuplePayloadBytes);
    return add(add(scalarBytes, tupleBytes), lifecycle.accountedBytes());
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
