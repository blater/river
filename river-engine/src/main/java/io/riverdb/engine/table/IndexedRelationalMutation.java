package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Caller-owned, reusable description of one atomic base-and-index mutation group. */
public final class IndexedRelationalMutation {
  public static final int BASE_INSERT = IndexedRelationalMutationBuffer.BASE_INSERT;
  public static final int BASE_UPDATE = IndexedRelationalMutationBuffer.BASE_UPDATE;
  public static final int BASE_DELETE = IndexedRelationalMutationBuffer.BASE_DELETE;
  public static final int TUPLE_INSERT = IndexedRelationalMutationBuffer.TUPLE_INSERT;
  public static final int TUPLE_DELETE = IndexedRelationalMutationBuffer.TUPLE_DELETE;
  public static final int SCALAR_INSERT = IndexedRelationalMutationBuffer.SCALAR_INSERT;
  public static final int SCALAR_UPDATE = IndexedRelationalMutationBuffer.SCALAR_UPDATE;
  public static final int SCALAR_DELETE = IndexedRelationalMutationBuffer.SCALAR_DELETE;
  public static final int SCALAR_SUBOPERATION =
      IndexedRelationalMutationBuffer.SCALAR_SUBOPERATION;
  public static final int REGISTRY_ABSENT = IndexedRelationalSuboperations.REGISTRY_ABSENT;
  public static final int REGISTRY_BUILDING = IndexedRelationalSuboperations.REGISTRY_BUILDING;
  public static final int REGISTRY_READY = IndexedRelationalSuboperations.REGISTRY_READY;
  public static final int REGISTRY_DROPPING = IndexedRelationalSuboperations.REGISTRY_DROPPING;

  private final IndexedRelationalMutationBuffer buffer;

  public IndexedRelationalMutation(
      int mutationCapacity, int descriptorCapacity, int descriptorPartCapacity) {
    buffer = new IndexedRelationalMutationBuffer(
        mutationCapacity, descriptorCapacity, descriptorPartCapacity);
  }

  public StatusCode reserve(
      int mutations, int descriptors, int descriptorParts, int payloadBytes) {
    return buffer.reserve(mutations, descriptors, descriptorParts, payloadBytes);
  }

  public StatusCode appendDescriptor(
      long ownerObjectId, long keyId, long schemaId, long descriptorHash,
      int[] parts, int partOffset, int partCount) {
    return buffer.appendDescriptor(
        ownerObjectId, keyId, schemaId, descriptorHash, parts, partOffset, partCount);
  }

  /** Covers committed base-row identities through the next exclusive value. */
  public StatusCode appendLogicalRowFloor(long ownerObjectId, long nextLogicalRowId) {
    return buffer.appendLogicalRowFloor(ownerObjectId, nextLogicalRowId);
  }

  public StatusCode appendSuboperation(
      long ownerObjectId, int descriptorOrdinal, int firstMutation, int mutationCount,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      long expectedGeneration, long resultingGeneration,
      long expectedHeapVersion, long resultingHeapVersion,
      int expectedRegistryState, int resultingRegistryState,
      long expectedPrivateOwner, long resultingPrivateOwner) {
    return buffer.appendSuboperation(
        ownerObjectId, descriptorOrdinal, firstMutation, mutationCount,
        expectedTupleRoot, resultingTupleRoot, expectedScalarRoot, resultingScalarRoot,
        expectedNextPage, resultingNextPage, expectedGeneration, resultingGeneration,
        expectedHeapVersion, resultingHeapVersion,
        expectedRegistryState, resultingRegistryState,
        expectedPrivateOwner, resultingPrivateOwner);
  }

  public StatusCode appendSuboperation(
      long ownerObjectId, int descriptorOrdinal, int firstMutation, int mutationCount,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      long expectedGeneration, long resultingGeneration,
      long expectedHeapVersion, long resultingHeapVersion,
      int expectedRegistryState, int resultingRegistryState,
      long expectedPrivateOwner, long resultingPrivateOwner,
      int expectedCleanupCursor, int resultingCleanupCursor) {
    return buffer.appendSuboperation(
        ownerObjectId, descriptorOrdinal, firstMutation, mutationCount,
        expectedTupleRoot, resultingTupleRoot, expectedScalarRoot, resultingScalarRoot,
        expectedNextPage, resultingNextPage, expectedGeneration, resultingGeneration,
        expectedHeapVersion, resultingHeapVersion,
        expectedRegistryState, resultingRegistryState,
        expectedPrivateOwner, resultingPrivateOwner,
        expectedCleanupCursor, resultingCleanupCursor);
  }

  public StatusCode appendBase(
      int suboperation, long ownerObjectId, int operation,
      long logicalRowId, long previousRowId,
      ByteBuffer source, int sourceOffset, int length) {
    return buffer.appendBase(
        suboperation, ownerObjectId, operation, logicalRowId, previousRowId,
        source, sourceOffset, length);
  }

  public StatusCode appendTuple(
      int suboperation, long ownerObjectId, int operation, int descriptorOrdinal,
      long logicalRowId, ByteBuffer source, int sourceOffset, int length) {
    return buffer.appendTuple(
        suboperation, ownerObjectId, operation, descriptorOrdinal,
        logicalRowId, source, sourceOffset, length);
  }

  public StatusCode appendScalar(
      int suboperation, int operation, long space, long key, long previousRowId,
      ByteBuffer source, int sourceOffset, int length) {
    return buffer.appendScalar(
        suboperation, operation, space, key, previousRowId,
        source, sourceOffset, length);
  }

  public StatusCode seal() { return buffer.seal(); }
  public void reset() { buffer.reset(); }
  public boolean sealed() { return buffer.sealed(); }
  public int mutationCapacity() { return buffer.mutationCapacity(); }
  public int descriptorCapacity() { return buffer.descriptorCapacity(); }
  public int descriptorPartCapacity() { return buffer.descriptorPartCapacity(); }

  IndexedRelationalMutationBuffer buffer() { return buffer; }
}
