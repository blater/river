package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Stateful all-or-nothing decoder for contiguous grouped relational WAL chunks. */
final class IndexedRelationalWalDecoder {
  private final IndexedRelationalMutationBuffer destination;
  private final IndexedRelationalWalChunkHeader header = new IndexedRelationalWalChunkHeader();
  private final IndexedRelationalWalItems items;
  private long transactionId;
  private long operationId;
  private long wholeDigest;
  private long totalStreamBytes;
  private long totalPayloadBytes;
  private long decodedStreamBytes;
  private int chunkCount;
  private int decodedChunks;
  private int totalItems;
  private int descriptorCount;
  private int logicalRowFloorCount;
  private int suboperationCount;
  private int mutationCount;
  private boolean complete;

  IndexedRelationalWalDecoder(IndexedRelationalMutationBuffer decodedMutations) {
    if (decodedMutations == null) throw new IllegalArgumentException("decoded mutations required");
    destination = decodedMutations;
    items = new IndexedRelationalWalItems(destination);
  }

  StatusCode decode(ByteBuffer payload, long walTransactionId, int decisionCode) {
    if (payload == null || walTransactionId <= 0 || complete) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = payload.position();
    if (payload.remaining() < IndexedRelationalWalCodec.HEADER_BYTES
        || !header.readAndValidate(payload, start, walTransactionId, decisionCode)) {
      return fail(StatusCode.CORRUPTION);
    }
    StatusCode status = decodedChunks == 0 ? begin() : validateGroup();
    if (!status.isOk()) return fail(status);
    if (header.ordinal != decodedChunks || header.firstItem != items.decodedItems()
        || header.priorDigest != items.rollingDigest()) {
      return fail(StatusCode.CORRUPTION);
    }
    status = items.decodeChunk(
        payload, start + IndexedRelationalWalCodec.HEADER_BYTES,
        header.streamBytes, header.chunkItems, header.resultingDigest);
    if (!status.isOk()) return fail(status == StatusCode.RESOURCE_EXHAUSTED
        ? status : StatusCode.CORRUPTION);
    decodedStreamBytes += header.streamBytes;
    decodedChunks++;
    if (decodedChunks != chunkCount) return StatusCode.OK;
    return finish();
  }

  void reset() {
    destination.reset();
    clearState();
  }

  boolean complete() { return complete; }

  boolean active() { return decodedChunks > 0 && !complete; }

  long transactionId() { return complete ? transactionId : 0; }

  long operationId() { return complete ? operationId : 0; }

  private StatusCode begin() {
    destination.reset();
    if (header.payloadBytes > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    long maximumDescriptorParts = (long) header.descriptors
        * io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS;
    if (maximumDescriptorParts > Integer.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = destination.reserve(
        header.mutations, header.descriptors, (int) maximumDescriptorParts,
        (int) header.payloadBytes);
    if (!status.isOk()) return status;
    transactionId = header.transactionId;
    operationId = header.operationId;
    chunkCount = header.chunkCount;
    totalItems = header.totalItems;
    descriptorCount = header.descriptors;
    logicalRowFloorCount = header.logicalRowFloors;
    suboperationCount = header.suboperations;
    mutationCount = header.mutations;
    totalStreamBytes = header.totalStreamBytes;
    totalPayloadBytes = header.payloadBytes;
    wholeDigest = header.wholeDigest;
    items.begin(descriptorCount, logicalRowFloorCount, suboperationCount, totalPayloadBytes);
    return StatusCode.OK;
  }

  private StatusCode validateGroup() {
    return header.transactionId == transactionId && header.operationId == operationId
        && header.chunkCount == chunkCount && header.totalItems == totalItems
        && header.descriptors == descriptorCount
        && header.logicalRowFloors == logicalRowFloorCount
        && header.suboperations == suboperationCount
        && header.mutations == mutationCount
        && header.totalStreamBytes == totalStreamBytes
        && header.payloadBytes == totalPayloadBytes && header.wholeDigest == wholeDigest
            ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode finish() {
    if (items.decodedItems() != totalItems || decodedStreamBytes != totalStreamBytes
        || items.decodedPayloadBytes() != totalPayloadBytes
        || items.rollingDigest() != wholeDigest) {
      return fail(StatusCode.CORRUPTION);
    }
    StatusCode status = destination.seal();
    if (!status.isOk()) return fail(StatusCode.CORRUPTION);
    complete = true;
    return StatusCode.OK;
  }

  private StatusCode fail(StatusCode status) {
    destination.reset();
    clearState();
    return status;
  }

  private void clearState() {
    transactionId = 0;
    operationId = 0;
    wholeDigest = 0;
    totalStreamBytes = 0;
    totalPayloadBytes = 0;
    decodedStreamBytes = 0;
    chunkCount = 0;
    decodedChunks = 0;
    totalItems = 0;
    descriptorCount = 0;
    logicalRowFloorCount = 0;
    suboperationCount = 0;
    mutationCount = 0;
    complete = false;
    items.begin(0, 0, 0, 0);
  }
}
