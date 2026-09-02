package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Versioned grouped logical row/tuple WAL encoding. */
final class IndexedRelationalWalCodec {
  static final int WAL_FORMAT_ID = 1003;
  static final int WAL_FORMAT_VERSION = 1;
  static final long MAGIC = 0x314c41574c455252L;
  static final int VERSION = 6;
  static final int HEADER_BYTES = 128;
  static final int DESCRIPTOR_ITEM = 1;
  static final int SUBOPERATION_ITEM = 2;
  static final int MUTATION_ITEM = 3;
  static final int LOGICAL_ROW_FLOOR_ITEM = 4;
  static final int DESCRIPTOR_ITEM_BYTES = 48;
  static final int SUBOPERATION_ITEM_BYTES = 128;
  static final int MUTATION_ITEM_BYTES = 64;
  static final int LOGICAL_ROW_FLOOR_ITEM_BYTES = 32;
  static final long INITIAL_DIGEST = 0xcbf29ce484222325L;

  private IndexedRelationalWalCodec() { }

  static StatusCode encode(
      IndexedRelationalWalPlan plan, int chunkOrdinal, ByteBuffer target) {
    if (plan == null || !plan.valid() || target == null || target.isReadOnly()
        || chunkOrdinal < 0 || chunkOrdinal >= plan.batchChunkCount()
        || target.remaining() < plan.payloadBytesAt(chunkOrdinal)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = target.position();
    IndexedRelationalMutationBuffer source = plan.mutations();
    putHeader(plan, chunkOrdinal, target, start, source);
    int cursor = start + HEADER_BYTES;
    int firstItem = plan.firstItemAt(chunkOrdinal);
    int itemEnd = firstItem + plan.itemCountAt(chunkOrdinal);
    for (int item = firstItem; item < itemEnd; item++) {
      cursor = encodeItem(source, item, target, cursor);
    }
    if (cursor != start + plan.payloadBytesAt(chunkOrdinal)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    target.position(cursor);
    return StatusCode.OK;
  }

  static int itemBytes(IndexedRelationalMutationBuffer source, int item) {
    int descriptors = source.descriptorCount();
    if (item < descriptors) {
      return DESCRIPTOR_ITEM_BYTES + source.descriptorPartCountAt(item) * Integer.BYTES;
    }
    int floors = source.logicalRowFloorCount();
    if (item < descriptors + floors) return LOGICAL_ROW_FLOOR_ITEM_BYTES;
    int suboperations = source.suboperationCount();
    if (item < descriptors + floors + suboperations) return SUBOPERATION_ITEM_BYTES;
    int mutation = item - descriptors - floors - suboperations;
    return mutation >= 0 && mutation < source.mutationCount()
        ? MUTATION_ITEM_BYTES + source.payloadLengthAt(mutation) : 0;
  }

  static int copiedPayloadBytes(
      IndexedRelationalWalPlan plan, int chunkOrdinal) {
    IndexedRelationalMutationBuffer source = plan.mutations();
    int mutationBase = source.descriptorCount()
        + source.logicalRowFloorCount() + source.suboperationCount();
    int first = plan.firstItemAt(chunkOrdinal);
    int end = first + plan.itemCountAt(chunkOrdinal);
    int bytes = 0;
    for (int item = Math.max(first, mutationBase); item < end; item++) {
      bytes += source.payloadLengthAt(item - mutationBase);
    }
    return bytes;
  }

  static long digestItem(
      IndexedRelationalMutationBuffer source, int item, long digest) {
    int descriptors = source.descriptorCount();
    int bytes = itemBytes(source, item);
    if (item < descriptors) {
      digest = mixByte(digest, DESCRIPTOR_ITEM);
      digest = mixZeroes(digest, 3);
      digest = mixInt(digest, bytes);
      digest = mixInt(digest, item);
      int parts = source.descriptorPartCountAt(item);
      digest = mixInt(digest, parts);
      digest = mixLong(digest, source.keyIdAt(item));
      digest = mixLong(digest, source.schemaIdAt(item));
      digest = mixLong(digest, source.descriptorHashAt(item));
      digest = mixLong(digest, source.descriptorOwnerObjectIdAt(item));
      for (int part = 0; part < parts; part++) {
        digest = mixInt(digest, source.descriptorPartAt(item, part));
      }
      return digest;
    }
    int floors = source.logicalRowFloorCount();
    if (item < descriptors + floors) {
      int floor = item - descriptors;
      digest = mixByte(digest, LOGICAL_ROW_FLOOR_ITEM);
      digest = mixZeroes(digest, 3);
      digest = mixInt(digest, LOGICAL_ROW_FLOOR_ITEM_BYTES);
      digest = mixInt(digest, floor);
      digest = mixInt(digest, 0);
      digest = mixLong(digest, source.logicalRowFloorObjectIdAt(floor));
      return mixLong(digest, source.logicalRowFloorNextAt(floor));
    }
    int suboperations = source.suboperationCount();
    if (item < descriptors + floors + suboperations) {
      int operation = item - descriptors - floors;
      digest = mixByte(digest, SUBOPERATION_ITEM);
      digest = mixZeroes(digest, 3);
      digest = mixInt(digest, bytes);
      digest = mixInt(digest, operation);
      digest = mixInt(digest, source.suboperationDescriptorAt(operation));
      digest = mixInt(digest, source.suboperationFirstMutationAt(operation));
      digest = mixInt(digest, source.suboperationMutationCountAt(operation));
      digest = mixInt(digest, source.expectedTupleRootAt(operation));
      digest = mixInt(digest, source.resultingTupleRootAt(operation));
      digest = mixInt(digest, source.expectedScalarRootAt(operation));
      digest = mixInt(digest, source.resultingScalarRootAt(operation));
      digest = mixInt(digest, source.expectedNextPageAt(operation));
      digest = mixInt(digest, source.resultingNextPageAt(operation));
      digest = mixInt(digest, source.expectedRegistryStateAt(operation));
      digest = mixInt(digest, source.resultingRegistryStateAt(operation));
      digest = mixLong(digest, source.suboperationOwnerAt(operation));
      digest = mixLong(digest, source.expectedGenerationAt(operation));
      digest = mixLong(digest, source.resultingGenerationAt(operation));
      int descriptor = source.suboperationDescriptorAt(operation);
      digest = mixLong(digest, descriptor < 0 ? 0 : source.keyIdAt(descriptor));
      digest = mixLong(digest, source.expectedHeapVersionAt(operation));
      digest = mixLong(digest, source.resultingHeapVersionAt(operation));
      digest = mixLong(digest, source.expectedPrivateOwnerAt(operation));
      digest = mixLong(digest, source.resultingPrivateOwnerAt(operation));
      digest = mixInt(digest, source.expectedCleanupCursorAt(operation));
      return mixInt(digest, source.resultingCleanupCursorAt(operation));
    }
    int mutation = item - descriptors - floors - suboperations;
    digest = mixByte(digest, MUTATION_ITEM);
    digest = mixZeroes(digest, 3);
    digest = mixInt(digest, bytes);
    digest = mixInt(digest, mutation);
    digest = mixByte(digest, source.operationAt(mutation));
    digest = mixZeroes(digest, 3);
    digest = mixInt(digest, source.descriptorOrdinalAt(mutation));
    digest = mixInt(digest, source.suboperationOrdinalAt(mutation));
    int payloadBytes = source.payloadLengthAt(mutation);
    digest = mixInt(digest, payloadBytes);
    digest = mixInt(digest, 0);
    digest = mixLong(digest, source.logicalRowIdAt(mutation));
    digest = mixLong(digest, source.previousRowIdAt(mutation));
    digest = mixLong(digest, source.ownerObjectIdAt(mutation));
    digest = mixLong(digest, source.spaceAt(mutation));
    for (int index = 0; index < payloadBytes; index++) {
      digest = mixByte(digest, source.payloadByteAt(mutation, index));
    }
    return digest;
  }

  static long digestBytes(ByteBuffer source, int offset, int length, long digest) {
    for (int index = 0; index < length; index++) {
      digest = mixByte(digest, source.get(offset + index));
    }
    return digest;
  }

  private static void putHeader(
      IndexedRelationalWalPlan plan,
      int chunk,
      ByteBuffer target,
      int start,
      IndexedRelationalMutationBuffer source) {
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, HEADER_BYTES);
    FormatBytes.putLong(target, start + 16, plan.transactionId());
    FormatBytes.putLong(target, start + 24, plan.operationId());
    FormatBytes.putInt(target, start + 32, plan.chunkOrdinalAt(chunk));
    FormatBytes.putInt(target, start + 36, plan.chunkCount());
    FormatBytes.putInt(target, start + 40, plan.firstItemAt(chunk));
    FormatBytes.putInt(target, start + 44, plan.itemCountAt(chunk));
    FormatBytes.putInt(target, start + 48, plan.totalItems());
    FormatBytes.putInt(target, start + 52, source.descriptorCount());
    FormatBytes.putInt(target, start + 56, source.suboperationCount());
    FormatBytes.putInt(target, start + 60, source.mutationCount());
    FormatBytes.putLong(target, start + 64, plan.totalStreamBytes());
    FormatBytes.putInt(target, start + 72, plan.streamBytesAt(chunk));
    FormatBytes.putInt(target, start + 76, source.logicalRowFloorCount());
    FormatBytes.putLong(target, start + 80, plan.priorDigestAt(chunk));
    FormatBytes.putLong(target, start + 88, plan.rollingDigestAt(chunk));
    FormatBytes.putLong(target, start + 96, plan.wholeDigest());
    FormatBytes.putLong(target, start + 104, plan.totalPayloadBytes());
    for (int offset = 112; offset < HEADER_BYTES; offset += Integer.BYTES) {
      FormatBytes.putInt(target, start + offset, 0);
    }
  }

  private static int encodeItem(
      IndexedRelationalMutationBuffer source, int item, ByteBuffer target, int offset) {
    int descriptors = source.descriptorCount();
    if (item < descriptors) return encodeDescriptor(source, item, target, offset);
    int floors = source.logicalRowFloorCount();
    if (item < descriptors + floors) {
      return encodeLogicalRowFloor(source, item - descriptors, target, offset);
    }
    int suboperations = source.suboperationCount();
    if (item < descriptors + floors + suboperations) {
      return encodeSuboperation(source, item - descriptors - floors, target, offset);
    }
    return encodeMutation(source, item - descriptors - floors - suboperations, target, offset);
  }

  private static int encodeLogicalRowFloor(
      IndexedRelationalMutationBuffer source, int ordinal, ByteBuffer target, int offset) {
    putItemHeader(target, offset, LOGICAL_ROW_FLOOR_ITEM, LOGICAL_ROW_FLOOR_ITEM_BYTES);
    FormatBytes.putInt(target, offset + 8, ordinal);
    FormatBytes.putInt(target, offset + 12, 0);
    FormatBytes.putLong(target, offset + 16, source.logicalRowFloorObjectIdAt(ordinal));
    FormatBytes.putLong(target, offset + 24, source.logicalRowFloorNextAt(ordinal));
    return offset + LOGICAL_ROW_FLOOR_ITEM_BYTES;
  }

  private static int encodeDescriptor(
      IndexedRelationalMutationBuffer source, int ordinal, ByteBuffer target, int offset) {
    int parts = source.descriptorPartCountAt(ordinal);
    int bytes = DESCRIPTOR_ITEM_BYTES + parts * Integer.BYTES;
    putItemHeader(target, offset, DESCRIPTOR_ITEM, bytes);
    FormatBytes.putInt(target, offset + 8, ordinal);
    FormatBytes.putInt(target, offset + 12, parts);
    FormatBytes.putLong(target, offset + 16, source.keyIdAt(ordinal));
    FormatBytes.putLong(target, offset + 24, source.schemaIdAt(ordinal));
    FormatBytes.putLong(target, offset + 32, source.descriptorHashAt(ordinal));
    FormatBytes.putLong(target, offset + 40, source.descriptorOwnerObjectIdAt(ordinal));
    for (int part = 0; part < parts; part++) {
      FormatBytes.putInt(target, offset + DESCRIPTOR_ITEM_BYTES + part * Integer.BYTES,
          source.descriptorPartAt(ordinal, part));
    }
    return offset + bytes;
  }

  private static int encodeSuboperation(
      IndexedRelationalMutationBuffer source, int ordinal, ByteBuffer target, int offset) {
    putItemHeader(target, offset, SUBOPERATION_ITEM, SUBOPERATION_ITEM_BYTES);
    FormatBytes.putInt(target, offset + 8, ordinal);
    int descriptor = source.suboperationDescriptorAt(ordinal);
    FormatBytes.putInt(target, offset + 12, descriptor);
    FormatBytes.putInt(target, offset + 16, source.suboperationFirstMutationAt(ordinal));
    FormatBytes.putInt(target, offset + 20, source.suboperationMutationCountAt(ordinal));
    FormatBytes.putInt(target, offset + 24, source.expectedTupleRootAt(ordinal));
    FormatBytes.putInt(target, offset + 28, source.resultingTupleRootAt(ordinal));
    FormatBytes.putInt(target, offset + 32, source.expectedScalarRootAt(ordinal));
    FormatBytes.putInt(target, offset + 36, source.resultingScalarRootAt(ordinal));
    FormatBytes.putInt(target, offset + 40, source.expectedNextPageAt(ordinal));
    FormatBytes.putInt(target, offset + 44, source.resultingNextPageAt(ordinal));
    FormatBytes.putInt(target, offset + 48, source.expectedRegistryStateAt(ordinal));
    FormatBytes.putInt(target, offset + 52, source.resultingRegistryStateAt(ordinal));
    FormatBytes.putLong(target, offset + 56, source.suboperationOwnerAt(ordinal));
    FormatBytes.putLong(target, offset + 64, source.expectedGenerationAt(ordinal));
    FormatBytes.putLong(target, offset + 72, source.resultingGenerationAt(ordinal));
    FormatBytes.putLong(target, offset + 80, descriptor < 0 ? 0 : source.keyIdAt(descriptor));
    FormatBytes.putLong(target, offset + 88, source.expectedHeapVersionAt(ordinal));
    FormatBytes.putLong(target, offset + 96, source.resultingHeapVersionAt(ordinal));
    FormatBytes.putLong(target, offset + 104, source.expectedPrivateOwnerAt(ordinal));
    FormatBytes.putLong(target, offset + 112, source.resultingPrivateOwnerAt(ordinal));
    FormatBytes.putInt(target, offset + 120, source.expectedCleanupCursorAt(ordinal));
    FormatBytes.putInt(target, offset + 124, source.resultingCleanupCursorAt(ordinal));
    return offset + SUBOPERATION_ITEM_BYTES;
  }

  private static int encodeMutation(
      IndexedRelationalMutationBuffer source, int mutation, ByteBuffer target, int offset) {
    int payloadBytes = source.payloadLengthAt(mutation);
    int bytes = MUTATION_ITEM_BYTES + payloadBytes;
    putItemHeader(target, offset, MUTATION_ITEM, bytes);
    FormatBytes.putInt(target, offset + 8, mutation);
    target.put(offset + 12, (byte) source.operationAt(mutation));
    target.put(offset + 13, (byte) 0);
    target.put(offset + 14, (byte) 0);
    target.put(offset + 15, (byte) 0);
    FormatBytes.putInt(target, offset + 16, source.descriptorOrdinalAt(mutation));
    FormatBytes.putInt(target, offset + 20, source.suboperationOrdinalAt(mutation));
    FormatBytes.putInt(target, offset + 24, payloadBytes);
    FormatBytes.putInt(target, offset + 28, 0);
    FormatBytes.putLong(target, offset + 32, source.logicalRowIdAt(mutation));
    FormatBytes.putLong(target, offset + 40, source.previousRowIdAt(mutation));
    FormatBytes.putLong(target, offset + 48, source.ownerObjectIdAt(mutation));
    FormatBytes.putLong(target, offset + 56, source.spaceAt(mutation));
    source.copyPayloadTo(mutation, target, offset + MUTATION_ITEM_BYTES);
    return offset + bytes;
  }

  private static void putItemHeader(
      ByteBuffer target, int offset, int type, int itemBytes) {
    target.put(offset, (byte) type);
    target.put(offset + 1, (byte) 0);
    target.put(offset + 2, (byte) 0);
    target.put(offset + 3, (byte) 0);
    FormatBytes.putInt(target, offset + 4, itemBytes);
  }

  private static long mixZeroes(long digest, int count) {
    for (int index = 0; index < count; index++) digest = mixByte(digest, 0);
    return digest;
  }

  private static long mixInt(long digest, int value) {
    for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
      digest = mixByte(digest, value >>> shift);
    }
    return digest;
  }

  private static long mixLong(long digest, long value) {
    for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
      digest = mixByte(digest, (int) (value >>> shift));
    }
    return digest;
  }

  private static long mixByte(long digest, int value) {
    return (digest ^ value & 0xffL) * 0x100000001b3L;
  }
}
