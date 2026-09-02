package io.riverdb.engine.table;

import io.riverdb.format.FormatBytes;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Stateless structural validation for relational WAL headers and item framing. */
final class IndexedRelationalWalValidation {
  private IndexedRelationalWalValidation() { }

  static boolean validHeader(
      ByteBuffer source,
      int start,
      long walTransactionId,
      long headerTransactionId,
      long operationId,
      int chunk,
      int chunks,
      int firstItem,
      int chunkItems,
      int totalItems,
      int descriptors,
      int logicalRowFloors,
      int suboperations,
      int mutations,
      long totalStreamBytes,
      int chunkStreamBytes,
      long payloadBytes,
      int decisionCode) {
    if (FormatBytes.getLong(source, start) != IndexedRelationalWalCodec.MAGIC
        || FormatBytes.getInt(source, start + 8) != IndexedRelationalWalCodec.VERSION
        || FormatBytes.getInt(source, start + 12) != IndexedRelationalWalCodec.HEADER_BYTES
        || headerTransactionId != walTransactionId || operationId <= 0
        || chunk < 0 || chunks < 1
        || chunk >= chunks || firstItem < 0 || chunkItems < 1
        || descriptors < 0
        || logicalRowFloors < 0
        || logicalRowFloors > IndexedRelationalMutationBuffer.MAX_MUTATIONS
        || suboperations < 0
        || suboperations > IndexedRelationalMutationBuffer.MAX_SUBOPERATIONS
        || mutations < 0
        || mutations > IndexedRelationalMutationBuffer.MAX_MUTATIONS
        || totalItems != (long) descriptors + logicalRowFloors + suboperations + mutations
        || totalItems < 1
        || suboperations == 0
            && (logicalRowFloors == 0 || descriptors != 0 || mutations != 0)
        || firstItem > totalItems - chunkItems
        || totalStreamBytes < chunkStreamBytes
        || totalStreamBytes > (long) chunks
            * (WalRecordCodec.MAX_PAYLOAD_BYTES - IndexedRelationalWalCodec.HEADER_BYTES)
        || payloadBytes < 0 || payloadBytes > totalStreamBytes
        || payloadBytes > (long) mutations * io.riverdb.base.sql.SqlShapeLimits.MAX_STORED_ROW_BYTES
        || chunkStreamBytes <= 0
        || chunkStreamBytes > WalRecordCodec.MAX_PAYLOAD_BYTES
            - IndexedRelationalWalCodec.HEADER_BYTES
        || source.remaining() != IndexedRelationalWalCodec.HEADER_BYTES + chunkStreamBytes
        || !reservedZero(source, start + 112, 16)) {
      return false;
    }
    return decisionCode == (chunk == chunks - 1 ? 1 : 0);
  }

  static boolean validItemHeader(ByteBuffer source, int offset) {
    return source.get(offset + 1) == 0 && source.get(offset + 2) == 0
        && source.get(offset + 3) == 0;
  }

  private static boolean reservedZero(ByteBuffer source, int offset, int length) {
    for (int index = 0; index < length; index++) {
      if (source.get(offset + index) != 0) return false;
    }
    return true;
  }
}
