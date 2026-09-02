package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;

/** One bounded lazy compilation workspace retained by its session or store owner. */
final class IndexedRelationalCompilationBuffer {
  private final IndexedRelationalMutation mutation;

  IndexedRelationalCompilationBuffer() {
    this(
        IndexedRelationalMutationBuffer.MAX_MUTATIONS,
        IndexedRelationalMutationBuffer.MAX_INDEX_DESCRIPTORS,
        maximumDescriptorParts(IndexedRelationalMutationBuffer.MAX_INDEX_DESCRIPTORS));
  }

  IndexedRelationalCompilationBuffer(
      int maximumMutations, int maximumDescriptors, int maximumDescriptorParts) {
    mutation = new IndexedRelationalMutation(
        maximumMutations, maximumDescriptors, maximumDescriptorParts);
  }

  StatusCode prepare(
      int mutations, int descriptors, int parts, int payloadBytes, int logicalRowFloors,
      IndexedRelationalMutation[] result) {
    if (result == null || result.length == 0 || mutations < 0
        || descriptors < 0 || parts < 0 || payloadBytes < 0
        || logicalRowFloors < 0
        || mutations == 0 && descriptors == 0 && logicalRowFloors == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result[0] = null;
    mutation.reset();
    StatusCode status = mutation.reserve(mutations, descriptors, parts, payloadBytes);
    if (status.isOk()) result[0] = mutation;
    return status;
  }

  void reset() { mutation.reset(); }
  void release() { mutation.buffer().release(); }
  long accountedBytes() { return mutation.buffer().accountedBytes(); }

  long accountedBytesForReservation(
      int mutations, int descriptors, int parts, int payloadBytes) {
    if (mutations < 0 || descriptors < 0 || parts < 0 || payloadBytes < 0) return -1;
    return mutation.buffer().accountedBytesForReservation(
        mutations, descriptors, parts, payloadBytes);
  }

  private static int maximumDescriptorParts(int descriptors) {
    long maximum = (long) descriptors * TupleKeyCodec.MAX_INDEX_KEY_PARTS;
    return (int) Math.min(Integer.MAX_VALUE, maximum);
  }
}
