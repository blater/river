package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Stateful, allocation-free decoder for the ordered items in a WAL group. */
final class IndexedRelationalWalItems {
  private final IndexedRelationalWalDescriptorDecoder descriptors;
  private final IndexedRelationalWalFloorDecoder floors;
  private final IndexedRelationalWalSuboperationDecoder suboperations;
  private final IndexedRelationalWalMutationDecoder mutations;
  private int descriptorCount;
  private int floorCount;
  private int suboperationCount;
  private int decodedItems;
  private long rollingDigest;

  IndexedRelationalWalItems(IndexedRelationalMutationBuffer destination) {
    descriptors = new IndexedRelationalWalDescriptorDecoder(destination);
    floors = new IndexedRelationalWalFloorDecoder(destination);
    suboperations = new IndexedRelationalWalSuboperationDecoder(destination);
    mutations = new IndexedRelationalWalMutationDecoder(destination);
  }

  void begin(
      int descriptorItems, int floorItems, int suboperationItems, long payloadBytes) {
    descriptorCount = descriptorItems;
    floorCount = floorItems;
    suboperationCount = suboperationItems;
    decodedItems = 0;
    rollingDigest = IndexedRelationalWalCodec.INITIAL_DIGEST;
    mutations.begin(descriptorItems, payloadBytes);
  }

  StatusCode decodeChunk(
      ByteBuffer source, int cursor, int streamBytes, int chunkItems,
      long resultingDigest) {
    int end = cursor + streamBytes;
    for (int item = 0; item < chunkItems; item++) {
      if (end - cursor < 8) return StatusCode.CORRUPTION;
      int itemBytes = FormatBytes.getInt(source, cursor + 4);
      if (itemBytes < 8 || itemBytes > end - cursor
          || !IndexedRelationalWalValidation.validItemHeader(source, cursor)) {
        return StatusCode.CORRUPTION;
      }
      rollingDigest = IndexedRelationalWalCodec.digestBytes(
          source, cursor, itemBytes, rollingDigest);
      StatusCode status = decodeItem(source, cursor, itemBytes);
      if (!status.isOk()) return status;
      cursor += itemBytes;
      decodedItems++;
    }
    return cursor == end && rollingDigest == resultingDigest
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  int decodedItems() { return decodedItems; }

  long rollingDigest() { return rollingDigest; }

  long decodedPayloadBytes() { return mutations.decodedPayloadBytes(); }

  private StatusCode decodeItem(ByteBuffer source, int offset, int itemBytes) {
    int expectedType = decodedItems < descriptorCount
        ? IndexedRelationalWalCodec.DESCRIPTOR_ITEM
        : decodedItems < descriptorCount + floorCount
            ? IndexedRelationalWalCodec.LOGICAL_ROW_FLOOR_ITEM
        : decodedItems < descriptorCount + floorCount + suboperationCount
            ? IndexedRelationalWalCodec.SUBOPERATION_ITEM
            : IndexedRelationalWalCodec.MUTATION_ITEM;
    int type = Byte.toUnsignedInt(source.get(offset));
    if (type != expectedType) return StatusCode.CORRUPTION;
    if (type == IndexedRelationalWalCodec.DESCRIPTOR_ITEM) {
      return descriptors.decode(source, offset, itemBytes, decodedItems);
    }
    if (type == IndexedRelationalWalCodec.LOGICAL_ROW_FLOOR_ITEM) {
      return floors.decode(source, offset, itemBytes, decodedItems - descriptorCount);
    }
    if (type == IndexedRelationalWalCodec.SUBOPERATION_ITEM) {
      return suboperations.decode(
          source, offset, itemBytes,
          decodedItems - descriptorCount - floorCount, descriptorCount);
    }
    return mutations.decode(
        source, offset, itemBytes,
        decodedItems - descriptorCount - floorCount - suboperationCount);
  }
}
