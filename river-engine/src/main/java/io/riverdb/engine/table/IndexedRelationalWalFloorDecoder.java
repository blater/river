package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Validates and appends one logical-row high-water floor from relational WAL. */
final class IndexedRelationalWalFloorDecoder {
  private final IndexedRelationalMutationBuffer destination;

  IndexedRelationalWalFloorDecoder(IndexedRelationalMutationBuffer target) {
    destination = target;
  }

  StatusCode decode(ByteBuffer source, int offset, int bytes, int ordinal) {
    if (bytes != IndexedRelationalWalCodec.LOGICAL_ROW_FLOOR_ITEM_BYTES
        || FormatBytes.getInt(source, offset + 8) != ordinal
        || FormatBytes.getInt(source, offset + 12) != 0) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = destination.appendLogicalRowFloor(
        FormatBytes.getLong(source, offset + 16),
        FormatBytes.getLong(source, offset + 24));
    return status.isOk() ? status : StatusCode.CORRUPTION;
  }
}
