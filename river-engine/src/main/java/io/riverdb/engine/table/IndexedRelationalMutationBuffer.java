package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.engine.runtime.DatabaseResourceDefaults;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Caller-owned exact-count grouped relational mutation state. */
final class IndexedRelationalMutationBuffer {
  static final int DEFAULT_MUTATIONS =
      DatabaseResourceDefaults.ADDRESSABLE_TRANSACTION_WRITE_ENTRIES;
  static final int MAX_MUTATIONS = Integer.MAX_VALUE;
  static final int MAX_INDEX_DESCRIPTORS = Integer.MAX_VALUE;
  static final int BASE_INSERT = 1;
  static final int BASE_UPDATE = 2;
  static final int BASE_DELETE = 3;
  static final int TUPLE_INSERT = 4;
  static final int TUPLE_DELETE = 5;
  static final int SCALAR_INSERT = 6;
  static final int SCALAR_UPDATE = 7;
  static final int SCALAR_DELETE = 8;
  static final int SCALAR_SUBOPERATION = -2;
  static final int MAX_SUBOPERATIONS = Integer.MAX_VALUE;
  private final int maximumPayloadBytes;

  private final IndexedRelationalMutationEntries entries;
  private final IndexedRelationalMutationDescriptors descriptors;
  private final IndexedRelationalSuboperations suboperations;
  private final IndexedLogicalRowIdFloors logicalRowFloors;
  private int generation;
  private boolean sealed;

  IndexedRelationalMutationBuffer(
      int mutationCapacity, int descriptorCapacity, int descriptorPartCapacity) {
    if (mutationCapacity < 0 || descriptorCapacity < 0
        || descriptorPartCapacity < 0
        || (long) descriptorPartCapacity
            > (long) descriptorCapacity * TupleKeyCodec.MAX_INDEX_KEY_PARTS) {
      throw new IllegalArgumentException("invalid relational mutation capacity");
    }
    long payloadBudget = (long) mutationCapacity * HeapPage.MAXIMUM_ROW_BYTES;
    maximumPayloadBytes = payloadBudget > Integer.MAX_VALUE
        ? Integer.MAX_VALUE : (int) payloadBudget;
    entries = new IndexedRelationalMutationEntries(mutationCapacity, maximumPayloadBytes);
    descriptors = new IndexedRelationalMutationDescriptors(
        descriptorCapacity, descriptorPartCapacity);
    long suboperationCapacity = (long) mutationCapacity + descriptorCapacity;
    suboperations = new IndexedRelationalSuboperations(
        suboperationCapacity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) suboperationCapacity);
    logicalRowFloors = new IndexedLogicalRowIdFloors(mutationCapacity);
  }

  StatusCode reserve(
      int additionalMutations, int additionalDescriptors,
      int additionalDescriptorParts, int additionalPayloadBytes) {
    return reserve(
        additionalMutations, additionalDescriptors,
        additionalDescriptorParts, additionalPayloadBytes, 0);
  }

  StatusCode reserve(
      int additionalMutations, int additionalDescriptors,
      int additionalDescriptorParts, int additionalPayloadBytes,
      int additionalLogicalRowFloors) {
    if (sealed || additionalMutations < 0 || additionalDescriptors < 0
        || additionalDescriptorParts < 0 || additionalPayloadBytes < 0
        || additionalLogicalRowFloors < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (additionalMutations > entries.capacity() - entries.count()
        || additionalPayloadBytes > maximumPayloadBytes - entries.payloadBytes()
        || !descriptors.canReserve(additionalDescriptors, additionalDescriptorParts)
        || additionalLogicalRowFloors > mutationCapacity() - logicalRowFloors.count()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long suboperationAdditional = (long) additionalMutations + additionalDescriptors;
    if (suboperationAdditional > Integer.MAX_VALUE
        || suboperationAdditional > suboperations.capacity() - suboperations.count()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = descriptors.reserve(additionalDescriptors, additionalDescriptorParts);
    if (status.isOk()) status = entries.reserve(additionalMutations, additionalPayloadBytes);
    if (status.isOk()) status = suboperations.reserve((int) suboperationAdditional);
    if (status.isOk()) {
      status = logicalRowFloors.reserve(logicalRowFloors.count() + additionalLogicalRowFloors);
    }
    return status;
  }

  StatusCode appendDescriptor(
      long ownerObjectId, long keyId, long schemaId, long descriptorHash,
      int[] parts, int partOffset, int partCount) {
    if (sealed) return StatusCode.INVALID_EXTERNAL_INPUT;
    return descriptors.append(
        ownerObjectId, keyId, schemaId, descriptorHash, parts, partOffset, partCount);
  }

  StatusCode appendDecodedDescriptor(
      long ownerObjectId, long keyId, long schemaId, long descriptorHash,
      int[] parts, int partOffset, int partCount) {
    return sealed ? StatusCode.INVALID_EXTERNAL_INPUT : descriptors.append(
        ownerObjectId, keyId, schemaId, descriptorHash, parts, partOffset, partCount);
  }

  StatusCode appendLogicalRowFloor(long ownerObjectId, long nextLogicalRowId) {
    return sealed ? StatusCode.INVALID_EXTERNAL_INPUT
        : logicalRowFloors.record(ownerObjectId, nextLogicalRowId);
  }

  StatusCode appendSuboperation(
      long ownerObjectId, int descriptorOrdinal, int firstMutation, int mutationCount,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      long expectedGeneration, long resultingGeneration,
      long expectedHeapVersion, long resultingHeapVersion,
      int expectedRegistryState, int resultingRegistryState,
      long expectedPrivateOwner, long resultingPrivateOwner) {
    if (sealed || descriptorOrdinal >= descriptors.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (descriptorOrdinal >= 0
        && descriptors.ownerObjectIdAt(descriptorOrdinal) != ownerObjectId) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return suboperations.append(
        ownerObjectId,
        descriptorOrdinal < 0 ? 0 : descriptors.keyIdAt(descriptorOrdinal),
        descriptorOrdinal, firstMutation, mutationCount,
        expectedTupleRoot, resultingTupleRoot, expectedScalarRoot, resultingScalarRoot,
        expectedNextPage, resultingNextPage, expectedGeneration, resultingGeneration,
        expectedHeapVersion, resultingHeapVersion,
        expectedRegistryState, resultingRegistryState,
        expectedPrivateOwner, resultingPrivateOwner);
  }

  StatusCode appendSuboperation(
      long ownerObjectId, int descriptorOrdinal, int firstMutation, int mutationCount,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      long expectedGeneration, long resultingGeneration,
      long expectedHeapVersion, long resultingHeapVersion,
      int expectedRegistryState, int resultingRegistryState,
      long expectedPrivateOwner, long resultingPrivateOwner,
      int expectedCleanupCursor, int resultingCleanupCursor) {
    if (sealed || descriptorOrdinal >= descriptors.count()
        || descriptorOrdinal >= 0
            && descriptors.ownerObjectIdAt(descriptorOrdinal) != ownerObjectId) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return suboperations.append(
        ownerObjectId,
        descriptorOrdinal < 0 ? 0 : descriptors.keyIdAt(descriptorOrdinal),
        descriptorOrdinal, firstMutation, mutationCount,
        expectedTupleRoot, resultingTupleRoot, expectedScalarRoot, resultingScalarRoot,
        expectedNextPage, resultingNextPage, expectedGeneration, resultingGeneration,
        expectedHeapVersion, resultingHeapVersion,
        expectedRegistryState, resultingRegistryState,
        expectedPrivateOwner, resultingPrivateOwner,
        expectedCleanupCursor, resultingCleanupCursor);
  }

  StatusCode appendBase(
      int suboperationOrdinal, long ownerObjectId,
      int operation, long logicalRowId, long previousRowId,
      ByteBuffer source, int sourceOffset, int length) {
    if (sealed || !validBase(
        ownerObjectId, operation, logicalRowId, previousRowId,
        source, sourceOffset, length)
        || !suboperations.acceptsMutation(
            suboperationOrdinal, entries.count(), ownerObjectId, -1)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!entries.canAppend(length)) return StatusCode.RESOURCE_EXHAUSTED;
    entries.append(
        operation, -1, suboperationOrdinal, ownerObjectId,
        io.riverdb.format.catalog.CatalogKeyspace.relationalBaseRowSpace(ownerObjectId),
        logicalRowId, previousRowId, source, sourceOffset, length);
    return StatusCode.OK;
  }

  StatusCode appendScalar(
      int suboperationOrdinal, int operation, long space, long key, long previousRowId,
      ByteBuffer source, int sourceOffset, int length) {
    if (sealed || !validScalar(
        operation, space, key, previousRowId, source, sourceOffset, length)
        || !suboperations.acceptsMutation(
            suboperationOrdinal, entries.count(), 0, SCALAR_SUBOPERATION)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!entries.canAppend(length)) return StatusCode.RESOURCE_EXHAUSTED;
    entries.append(
        operation, SCALAR_SUBOPERATION, suboperationOrdinal, 0, space,
        key, previousRowId, source, sourceOffset, length);
    return StatusCode.OK;
  }

  StatusCode appendTuple(
      int suboperationOrdinal, long ownerObjectId,
      int operation, int descriptorOrdinal, long logicalRowId,
      ByteBuffer source, int sourceOffset, int length) {
    if (sealed || (operation != TUPLE_INSERT && operation != TUPLE_DELETE)
        || descriptorOrdinal < 0 || descriptorOrdinal >= descriptors.count()
        || descriptors.ownerObjectIdAt(descriptorOrdinal) != ownerObjectId
        || !suboperations.acceptsMutation(
            suboperationOrdinal, entries.count(), ownerObjectId, descriptorOrdinal)
        || logicalRowId <= 0 || source == null || sourceOffset < 0 || length <= 0
        || length > TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        || sourceOffset > source.limit() - length
        || !TupleKeyCodec.matchesPhysicalIndexKey(
            source, sourceOffset, length, descriptors.shapeAt(descriptorOrdinal))
        || TupleKeyCodec.logicalRowId(source, sourceOffset, length) != logicalRowId) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!entries.canAppend(length)) return StatusCode.RESOURCE_EXHAUSTED;
    entries.append(
        operation, descriptorOrdinal, suboperationOrdinal, ownerObjectId,
        io.riverdb.format.catalog.CatalogKeyspace.relationalIndexSpace(
            descriptors.keyIdAt(descriptorOrdinal)),
        logicalRowId, 0, source, sourceOffset, length);
    return StatusCode.OK;
  }

  StatusCode seal() {
    boolean floorOnly = logicalRowFloors.count() > 0
        && entries.count() == 0 && descriptors.count() == 0 && suboperations.count() == 0;
    if (sealed || !floorOnly && !suboperations.complete(entries.count())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int descriptor = 0; descriptor < descriptors.count(); descriptor++) {
      if (!descriptorReferenced(descriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int mutation = 0; mutation < entries.count(); mutation++) {
      if (entries.operationAt(mutation) == BASE_INSERT
          && !coveredByLogicalRowFloor(
              entries.ownerObjectIdAt(mutation), entries.logicalRowIdAt(mutation))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    sealed = true;
    return StatusCode.OK;
  }

  void reset() {
    entries.reset();
    descriptors.reset();
    suboperations.reset();
    logicalRowFloors.reset();
    sealed = false;
    generation++;
  }

  int mutationCapacity() { return entries.capacity(); }
  int descriptorCapacity() { return descriptors.capacity(); }
  int descriptorPartCapacity() { return descriptors.partCapacity(); }
  int mutationCount() { return sealed ? entries.count() : 0; }
  int descriptorCount() { return sealed ? descriptors.count() : 0; }
  int suboperationCount() { return sealed ? suboperations.count() : 0; }
  int logicalRowFloorCount() { return sealed ? logicalRowFloors.count() : 0; }
  int payloadBytes() { return sealed ? entries.payloadBytes() : 0; }
  boolean sealed() { return sealed; }
  int generation() { return generation; }
  long accountedBytes() {
    return entries.accountedBytes() + descriptors.accountedBytes()
        + suboperations.accountedBytes() + logicalRowFloors.accountedBytes();
  }
  long accountedBytesForReservation(
      int mutations, int descriptorCount, int descriptorParts, int payloadBytes,
      int logicalRowFloorCount) {
    if ((long) mutations + descriptorCount > Integer.MAX_VALUE) return -1;
    long entryBytes = entries.accountedBytesForReservation(mutations, payloadBytes);
    long descriptorBytes = descriptors.accountedBytesForReservation(
        descriptorCount, descriptorParts);
    long suboperationBytes = suboperations.accountedBytesForReservation(
        mutations + descriptorCount);
    long floorBytes = logicalRowFloors.accountedBytesForEntries(logicalRowFloorCount);
    if (entryBytes < 0 || descriptorBytes < 0 || suboperationBytes < 0 || floorBytes < 0
        || entryBytes > Long.MAX_VALUE - descriptorBytes
        || entryBytes + descriptorBytes > Long.MAX_VALUE - suboperationBytes
        || entryBytes + descriptorBytes + suboperationBytes
            > Long.MAX_VALUE - floorBytes) return -1;
    return entryBytes + descriptorBytes + suboperationBytes + floorBytes;
  }
  void release() {
    entries.release();
    descriptors.release();
    suboperations.release();
    logicalRowFloors.release();
    sealed = false;
  }
  int operationAt(int index) { return entries.operationAt(index); }
  int descriptorOrdinalAt(int index) { return entries.descriptorOrdinalAt(index); }
  int suboperationOrdinalAt(int index) { return entries.suboperationOrdinalAt(index); }
  long ownerObjectIdAt(int index) { return entries.ownerObjectIdAt(index); }
  long spaceAt(int index) { return entries.spaceAt(index); }
  long logicalRowIdAt(int index) { return entries.logicalRowIdAt(index); }
  long previousRowIdAt(int index) { return entries.previousRowIdAt(index); }
  int payloadLengthAt(int index) { return entries.payloadLengthAt(index); }
  long descriptorOwnerObjectIdAt(int ordinal) { return descriptors.ownerObjectIdAt(ordinal); }
  long keyIdAt(int ordinal) { return descriptors.keyIdAt(ordinal); }
  long schemaIdAt(int ordinal) { return descriptors.schemaIdAt(ordinal); }
  long descriptorHashAt(int ordinal) { return descriptors.hashAt(ordinal); }
  int descriptorPartCountAt(int ordinal) { return descriptors.partCountAt(ordinal); }
  TupleShape shapeAt(int ordinal) { return descriptors.shapeAt(ordinal); }
  int descriptorPartAt(int ordinal, int part) { return descriptors.partAt(ordinal, part); }
  long suboperationOwnerAt(int ordinal) { return suboperations.ownerAt(ordinal); }
  int suboperationDescriptorAt(int ordinal) { return suboperations.descriptorAt(ordinal); }
  int suboperationFirstMutationAt(int ordinal) { return suboperations.firstMutationAt(ordinal); }
  int suboperationMutationCountAt(int ordinal) { return suboperations.mutationCountAt(ordinal); }
  int expectedTupleRootAt(int ordinal) { return suboperations.expectedTupleRootAt(ordinal); }
  int resultingTupleRootAt(int ordinal) { return suboperations.resultingTupleRootAt(ordinal); }
  int expectedScalarRootAt(int ordinal) { return suboperations.expectedScalarRootAt(ordinal); }
  int resultingScalarRootAt(int ordinal) { return suboperations.resultingScalarRootAt(ordinal); }
  int expectedNextPageAt(int ordinal) { return suboperations.expectedNextPageAt(ordinal); }
  int resultingNextPageAt(int ordinal) { return suboperations.resultingNextPageAt(ordinal); }
  long expectedGenerationAt(int ordinal) { return suboperations.expectedGenerationAt(ordinal); }
  long resultingGenerationAt(int ordinal) { return suboperations.resultingGenerationAt(ordinal); }
  long expectedHeapVersionAt(int ordinal) { return suboperations.expectedHeapVersionAt(ordinal); }
  long resultingHeapVersionAt(int ordinal) { return suboperations.resultingHeapVersionAt(ordinal); }
  int expectedRegistryStateAt(int ordinal) {
    return suboperations.expectedRegistryStateAt(ordinal);
  }
  int resultingRegistryStateAt(int ordinal) {
    return suboperations.resultingRegistryStateAt(ordinal);
  }
  long expectedPrivateOwnerAt(int ordinal) {
    return suboperations.expectedPrivateOwnerAt(ordinal);
  }
  long resultingPrivateOwnerAt(int ordinal) {
    return suboperations.resultingPrivateOwnerAt(ordinal);
  }
  long logicalRowFloorObjectIdAt(int ordinal) {
    return logicalRowFloors.objectIdAt(ordinal);
  }
  long logicalRowFloorNextAt(int ordinal) { return logicalRowFloors.nextAt(ordinal); }
  int expectedCleanupCursorAt(int ordinal) {
    return suboperations.expectedCleanupCursorAt(ordinal);
  }
  int resultingCleanupCursorAt(int ordinal) {
    return suboperations.resultingCleanupCursorAt(ordinal);
  }
  void copyPayloadTo(int mutation, ByteBuffer target, int targetOffset) {
    entries.copyPayloadTo(mutation, target, targetOffset);
  }
  byte payloadByteAt(int mutation, int index) { return entries.payloadByteAt(mutation, index); }

  private boolean descriptorReferenced(int descriptor) {
    for (int operation = 0; operation < suboperations.count(); operation++) {
      if (suboperations.descriptorAt(operation) == descriptor) return true;
    }
    return false;
  }

  private boolean coveredByLogicalRowFloor(long objectId, long logicalRowId) {
    for (int index = 0; index < logicalRowFloors.count(); index++) {
      if (logicalRowFloors.objectIdAt(index) == objectId
          && logicalRowFloors.nextAt(index) > logicalRowId) return true;
    }
    return false;
  }

  private static boolean validBase(
      long ownerObjectId, int operation, long logicalRowId, long previousRowId,
      ByteBuffer source, int sourceOffset, int length) {
    if (!io.riverdb.format.catalog.CatalogKeyspace.validObjectHead(ownerObjectId)
        || logicalRowId <= 0 || previousRowId < 0) return false;
    if (operation == BASE_INSERT) {
      return previousRowId == 0 && validPayload(source, sourceOffset, length);
    }
    if (operation == BASE_UPDATE) {
      return previousRowId > 0 && validPayload(source, sourceOffset, length);
    }
    return operation == BASE_DELETE && previousRowId > 0 && length == 0
        && sourceOffset >= 0 && (source == null ? sourceOffset == 0 : sourceOffset <= source.limit());
  }

  private static boolean validPayload(ByteBuffer source, int offset, int length) {
    return source != null && offset >= 0 && length > 0
        && length <= HeapPage.MAXIMUM_ROW_BYTES
        && offset <= source.limit() - length;
  }

  private static boolean validScalar(
      int operation, long space, long key, long previousRowId,
      ByteBuffer source, int sourceOffset, int length) {
    if (!io.riverdb.base.key.OrderedKey.isFiniteSpace(space) || previousRowId < 0) {
      return false;
    }
    if (operation == SCALAR_INSERT) {
      return previousRowId == 0 && validPayload(source, sourceOffset, length);
    }
    if (operation == SCALAR_UPDATE) {
      return previousRowId > 0 && validPayload(source, sourceOffset, length);
    }
    return operation == SCALAR_DELETE && previousRowId > 0 && length == 0
        && sourceOffset >= 0 && (source == null ? sourceOffset == 0 : sourceOffset <= source.limit());
  }
}
