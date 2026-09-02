package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;

/** Reusable first-pass measurements for a relational WAL stream. */
final class IndexedRelationalWalSizing {
  private long digest;
  private long streamBytes;
  private long payloadBytes;
  private int items;
  private int chunks;

  StatusCode measure(IndexedRelationalMutationBuffer source) {
    reset();
    long itemCount = (long) source.descriptorCount()
        + source.logicalRowFloorCount() + source.suboperationCount() + source.mutationCount();
    if (itemCount < 1 || itemCount > Integer.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    items = (int) itemCount;
    digest = IndexedRelationalWalCodec.INITIAL_DIGEST;
    int packed = 0;
    for (int item = 0; item < items; item++) {
      int bytes = IndexedRelationalWalCodec.itemBytes(source, item);
      if (bytes <= 0 || bytes > maximumChunkStreamBytes()
          || streamBytes > Long.MAX_VALUE - bytes) {
        reset();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      if (packed > maximumChunkStreamBytes() - bytes) {
        if (chunks == Integer.MAX_VALUE) {
          reset();
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        chunks++;
        packed = 0;
      }
      packed += bytes;
      streamBytes += bytes;
      digest = IndexedRelationalWalCodec.digestItem(source, item, digest);
    }
    chunks++;
    for (int mutation = 0; mutation < source.mutationCount(); mutation++) {
      int length = source.payloadLengthAt(mutation);
      if (payloadBytes > Long.MAX_VALUE - length) {
        reset();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      payloadBytes += length;
    }
    return StatusCode.OK;
  }

  void reset() {
    digest = streamBytes = payloadBytes = 0;
    items = chunks = 0;
  }

  long digest() { return digest; }
  long streamBytes() { return streamBytes; }
  long payloadBytes() { return payloadBytes; }
  int items() { return items; }
  int chunks() { return chunks; }

  static int maximumChunkStreamBytes() {
    return WalRecordCodec.MAX_PAYLOAD_BYTES - IndexedRelationalWalCodec.HEADER_BYTES;
  }
}
