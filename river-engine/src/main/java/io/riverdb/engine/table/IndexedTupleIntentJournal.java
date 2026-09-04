package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;

/** Session-owned semantic tuple deltas; durable evidence is derived only at commit. */
final class IndexedTupleIntentJournal {
  static final int MAX_MUTATIONS = IndexedRelationalMutationBuffer.DEFAULT_MUTATIONS;
  static final int MAX_DESCRIPTORS = MAX_MUTATIONS;
  private final int maximumMutations;
  private final int maximumPayloadBytes;
  private final IndexedTupleIntentDescriptors descriptors;
  private final IndexedTupleIntentEntries entries;
  private final IndexedRelationalCompilationBuffer compilation;
  private long generation = 1;

  IndexedTupleIntentJournal() {
    this(MAX_MUTATIONS, MAX_DESCRIPTORS,
        maximumPayloadBytes(MAX_MUTATIONS));
  }

  IndexedTupleIntentJournal(int maximumMutations, int maximumDescriptors,
      int maximumPayloadBytes) {
    if (maximumMutations <= 0 || maximumDescriptors <= 0 || maximumPayloadBytes <= 0) {
      throw new IllegalArgumentException("tuple intent capacity must be positive");
    }
    this.maximumMutations = maximumMutations;
    this.maximumPayloadBytes = maximumPayloadBytes;
    descriptors = new IndexedTupleIntentDescriptors(maximumDescriptors);
    entries = new IndexedTupleIntentEntries(maximumMutations, maximumPayloadBytes);
    compilation = new IndexedRelationalCompilationBuffer(
        maximumMutations, maximumDescriptors, maximumDescriptorParts(maximumDescriptors));
  }

  StatusCode reserve(
      int scalarMutations, int mutations, int descriptorCount, int payloadBytes) {
    StatusCode status = descriptors.reserve(descriptorCount);
    return status.isOk()
        ? entries.reserve(scalarMutations, mutations, payloadBytes) : status;
  }

  long accountedBytesForReservation(
      int scalarMutations, int scalarPayloadBytes,
      int mutations, int descriptorCount, int payloadBytes,
      int logicalRowFloors) {
    long entryBytes = entries.accountedBytesForReservation(mutations, payloadBytes);
    long descriptorBytes = descriptors.accountedBytesForReservation(descriptorCount);
    long totalMutations = (long) scalarMutations + entries.count() + mutations;
    long totalDescriptors = (long) descriptors.count() + descriptorCount;
    long totalPayload = (long) scalarPayloadBytes + entries.payloadBytes() + payloadBytes;
    long totalParts = totalDescriptors * TupleKeyCodec.MAX_INDEX_KEY_PARTS;
    if (entryBytes < 0 || descriptorBytes < 0 || scalarMutations < 0
        || scalarPayloadBytes < 0 || totalMutations > Integer.MAX_VALUE
        || totalDescriptors > Integer.MAX_VALUE || totalPayload > Integer.MAX_VALUE
        || totalParts > Integer.MAX_VALUE) return -1;
    long compilationBytes = compilation.accountedBytesForReservation(
        (int) totalMutations, (int) totalDescriptors, (int) totalParts,
        (int) totalPayload, logicalRowFloors);
    if (compilationBytes < 0 || entryBytes > Long.MAX_VALUE - descriptorBytes
        || entryBytes + descriptorBytes > Long.MAX_VALUE - compilationBytes) return -1;
    return entryBytes + descriptorBytes + compilationBytes;
  }

  long accountedBytesForLifecycleReservation(
      int scalarMutations, int scalarPayloadBytes,
      int mutations, int descriptorAdds, int payloadBytes,
      int lifecycleDescriptors, int lifecycleParts, int logicalRowFloors) {
    long entryBytes = entries.accountedBytesForReservation(mutations, payloadBytes);
    long descriptorBytes = descriptors.accountedBytesForReservation(descriptorAdds);
    long totalMutations = (long) scalarMutations + entries.count() + mutations;
    long totalPayload = (long) scalarPayloadBytes + entries.payloadBytes() + payloadBytes;
    long totalDescriptors = (long) lifecycleDescriptors + descriptors.count() + descriptorAdds;
    long totalParts = (long) lifecycleParts + descriptorParts()
        + (long) descriptorAdds * TupleKeyCodec.MAX_INDEX_KEY_PARTS;
    if (entryBytes < 0 || descriptorBytes < 0 || scalarMutations < 0
        || scalarPayloadBytes < 0 || lifecycleDescriptors < 0 || lifecycleParts < 0
        || totalMutations > Integer.MAX_VALUE || totalPayload > Integer.MAX_VALUE
        || totalDescriptors > Integer.MAX_VALUE || totalParts > Integer.MAX_VALUE) return -1;
    long compilationBytes = compilation.accountedBytesForReservation(
        (int) totalMutations, (int) totalDescriptors, (int) totalParts,
        (int) totalPayload, logicalRowFloors);
    if (compilationBytes < 0 || entryBytes > Long.MAX_VALUE - descriptorBytes
        || entryBytes + descriptorBytes > Long.MAX_VALUE - compilationBytes) return -1;
    return entryBytes + descriptorBytes + compilationBytes;
  }

  long accountedBytes() {
    long entriesBytes = entries.accountedBytesForReservation(0, 0);
    long descriptorsBytes = descriptors.accountedBytesForReservation(0);
    long compilationBytes = compilation.accountedBytes();
    return entriesBytes < 0 || descriptorsBytes < 0
        || entriesBytes > Long.MAX_VALUE - descriptorsBytes
        || entriesBytes + descriptorsBytes > Long.MAX_VALUE - compilationBytes
            ? -1 : entriesBytes + descriptorsBytes + compilationBytes;
  }

  void release() {
    boolean changed = entries.count() != 0 || descriptors.count() != 0;
    entries.release();
    descriptors.release();
    compilation.release();
    if (changed) changeGeneration();
  }

  StatusCode append(
      int operation, long owner, long keyId, long schemaId, TupleShape shape,
      long logicalRowId, ByteBuffer key, int offset, int length) {
    if (!valid(operation, owner, keyId, schemaId, shape, logicalRowId, key, offset, length)
        || length > entriesPayloadRemaining() || !entries.canAppend(length)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int descriptor = descriptors.register(owner, keyId, schemaId, shape);
    if (descriptor < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    entries.append(operation, descriptor, logicalRowId, key, offset, length);
    changeGeneration();
    return StatusCode.OK;
  }

  void truncate(int mutations, int descriptorCount, int payloadBytes) {
    boolean changed = mutations != entries.count() || descriptorCount != descriptors.count();
    entries.truncate(mutations, payloadBytes);
    descriptors.truncate(descriptorCount);
    if (changed) changeGeneration();
  }

  void reset() {
    truncate(0, 0, 0);
    compilation.reset();
  }
  int mutationCount() { return entries.count(); }
  long generation() { return generation; }
  int activeMutationCount() { return entries.activeCount(); }
  int mutationCapacity() { return maximumMutations; }
  int descriptorCount() { return descriptors.count(); }
  int payloadBytes() { return entries.payloadBytes(); }
  int operationAt(int index) { return entries.operationAt(index); }
  int descriptorAt(int index) { return entries.descriptorAt(index); }
  long logicalRowIdAt(int index) { return entries.logicalRowIdAt(index); }
  long ownerAt(int descriptor) { return descriptors.ownerAt(descriptor); }
  long keyIdAt(int descriptor) { return descriptors.keyIdAt(descriptor); }
  long schemaIdAt(int descriptor) { return descriptors.schemaIdAt(descriptor); }
  long hashAt(int descriptor) { return descriptors.hashAt(descriptor); }
  TupleShape shapeAt(int descriptor) { return descriptors.shapeAt(descriptor); }
  int payloadLengthAt(int index) { return entries.payloadLengthAt(index); }
  boolean activeAt(int index) { return entries.activeAt(index); }
  StatusCode descriptorStatus(
      long owner, long keyId, long schemaId, TupleShape shape) {
    return descriptors.status(owner, keyId, schemaId, shape);
  }

  StatusCode uniquePrefixStatus(
      long keyId, TupleShape shape, ByteBuffer key, int offset, int length,
      long logicalRowId, boolean committedFound, long committedRowId) {
    int descriptor = descriptors.findDescriptor(keyId);
    if (descriptor >= 0 && entries.hasActiveInsertPrefix(
        descriptor, key, offset, length, shape.partCount(), logicalRowId)) {
      return StatusCode.UNIQUE_VIOLATION;
    }
    if (!committedFound || committedRowId == logicalRowId) return StatusCode.OK;
    return descriptor >= 0 && entries.hasActiveDeletePrefix(
        descriptor, key, offset, length, shape.partCount(), committedRowId)
            ? StatusCode.OK : StatusCode.UNIQUE_VIOLATION;
  }

  StatusCode appendOnlyUniquePrefixStatus(
      long keyId, TupleShape shape, ByteBuffer key, int offset, int length,
      long logicalRowId, boolean committedFound, long committedRowId) {
    int descriptor = descriptors.findDescriptor(keyId);
    if (descriptor >= 0 && entries.hasAppendOnlyInsertPrefix(
        descriptor, key, offset, length, shape.partCount(), logicalRowId)) {
      return StatusCode.UNIQUE_VIOLATION;
    }
    return !committedFound || committedRowId == logicalRowId
        ? StatusCode.OK : StatusCode.UNIQUE_VIOLATION;
  }

  StatusCode resolveUniquePrefix(
      long keyId, TupleShape shape, ByteBuffer key, int offset, int length,
      boolean committedFound, long committedRowId, IndexedTupleProbeResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int descriptor = descriptors.findDescriptor(keyId);
    long pendingRowId = descriptor < 0 ? 0 : entries.activeInsertPrefixRowId(
        descriptor, key, offset, length, shape.partCount());
    if (pendingRowId < 0) return StatusCode.INVARIANT_BROKEN;
    if (pendingRowId > 0) {
      result.set(pendingRowId);
      return StatusCode.OK;
    }
    if (committedFound && (descriptor < 0 || !entries.hasActiveDeletePrefix(
        descriptor, key, offset, length, shape.partCount(), committedRowId))) {
      result.set(committedRowId);
    }
    return StatusCode.OK;
  }

  boolean deletesUniquePrefix(
      long keyId, TupleShape shape, ByteBuffer key, int offset, int length) {
    int descriptor = descriptors.findDescriptor(keyId);
    return descriptor >= 0 && entries.endsWithDeletePrefix(
        descriptor, key, offset, length, shape.partCount());
  }

  long anyInsertPrefixRowId(
      long keyId, TupleShape shape, ByteBuffer key, int offset, int length) {
    return anyInsertPrefixRowId(keyId, shape, key, offset, length, 0);
  }

  long anyInsertPrefixRowId(
      long keyId, TupleShape shape, ByteBuffer key, int offset, int length,
      long excludedRowId) {
    int descriptor = descriptors.findDescriptor(keyId);
    return descriptor < 0 ? 0 : entries.anyActiveInsertPrefixRowId(
        descriptor, key, offset, length, shape.partCount(), excludedRowId);
  }

  boolean deletesPrefixRow(
      long keyId, TupleShape shape, ByteBuffer key, int offset, int length,
      long logicalRowId) {
    int descriptor = descriptors.findDescriptor(keyId);
    return descriptor >= 0 && entries.hasActiveDeletePrefix(
        descriptor, key, offset, length, shape.partCount(), logicalRowId);
  }
  void copyPayloadTo(int index, ByteBuffer target, int offset) {
    entries.copyPayloadTo(index, target, offset);
  }

  int collect(long keyId, io.riverdb.storage.btree.TupleBTreeScanBounds bounds, int[] ordinals) {
    if (bounds == null || ordinals == null) return -1;
    int descriptor = descriptors.findDescriptor(keyId);
    if (descriptor < 0) return 0;
    int found = 0;
    for (int index = 0; index < entries.count(); index++) {
      if (entries.activeAt(index) && entries.descriptorAt(index) == descriptor
          && entries.within(index, bounds)) {
        if (found == ordinals.length) return -1;
        ordinals[found++] = index;
      }
    }
    return found;
  }

  int compare(int left, int right) { return entries.compare(left, right); }
  int compare(int intent, ByteBuffer key, int offset, int length) {
    return entries.compare(intent, key, offset, length);
  }

  StatusCode prepareCompilation(
      int scalarMutations, int scalarPayloadBytes, int logicalRowFloors,
      IndexedRelationalMutation[] result) {
    int parts = descriptorParts();
    if (scalarPayloadBytes < 0 || scalarPayloadBytes > Integer.MAX_VALUE - payloadBytes()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return compilation.prepare(
        scalarMutations + entries.activeCount(), descriptorCount(), parts,
        scalarPayloadBytes + payloadBytes(), logicalRowFloors, result);
  }

  StatusCode prepareLifecycleCompilation(
      int mutations, int descriptorCount, int descriptorParts,
      int payloadBytes, int logicalRowFloors, IndexedRelationalMutation[] result) {
    return compilation.prepare(
        mutations, descriptorCount, descriptorParts, payloadBytes, logicalRowFloors, result);
  }

  StatusCode reserveCompilation(IndexedHybridLogicalSizing sizing) {
    return sizing == null ? StatusCode.INVALID_EXTERNAL_INPUT : compilation.reserve(
        sizing.mutations(), sizing.descriptors(), sizing.descriptorParts(),
        sizing.payloadBytes(), sizing.logicalRowFloors());
  }

  private int descriptorParts() {
    int parts = 0;
    for (int descriptor = 0; descriptor < descriptorCount(); descriptor++) {
      parts += shapeAt(descriptor).partCount();
    }
    return parts;
  }

  private int entriesPayloadRemaining() {
    return maximumPayloadBytes - payloadBytes();
  }

  private static int maximumDescriptorParts(int descriptors) {
    long maximum = (long) descriptors * TupleKeyCodec.MAX_INDEX_KEY_PARTS;
    return (int) Math.min(Integer.MAX_VALUE, maximum);
  }

  private static int maximumPayloadBytes(int mutations) {
    long maximum = (long) mutations * TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES;
    return (int) Math.min(Integer.MAX_VALUE, maximum);
  }

  private static boolean valid(
      int operation, long owner, long keyId, long schemaId, TupleShape shape,
      long logicalRowId, ByteBuffer key, int offset, int length) {
    return (operation == IndexedRelationalMutation.TUPLE_INSERT
            || operation == IndexedRelationalMutation.TUPLE_DELETE)
        && CatalogKeyspace.validObjectHead(owner) && CatalogKeyspace.validKeyId(keyId)
        && schemaId > 0 && shape != null && shape.partCount() > 0
        && shape.partCount() <= TupleKeyCodec.MAX_INDEX_KEY_PARTS
        && shape.maximumPhysicalEncodedBytes() <= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && logicalRowId > 0 && key != null && offset >= 0 && length > 0
        && length <= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && offset <= key.limit() - length
        && TupleKeyCodec.matchesPhysicalIndexKey(key, offset, length, shape)
        && TupleKeyCodec.logicalRowId(key, offset, length) == logicalRowId;
  }

  private void changeGeneration() {
    generation = generation == Long.MAX_VALUE ? 0 : generation + 1;
  }
}
