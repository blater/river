package io.riverdb.engine.table;

import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Validates one v6 vacuum entry without enlarging the WAL facade. */
final class IndexedWalEntryValidator {
  private IndexedWalEntryValidator() {
  }

  static boolean validVacuumEntry(ByteBuffer payload, int offset) {
    if (payload == null || offset < 0
        || payload.limit() - offset < IndexedWalCodec.VACUUM_ENTRY_BYTES) {
      return false;
    }
    int rowBytes = FormatBytes.getInt(payload, offset + 12);
    int deleted = FormatBytes.getInt(payload, offset + 16);
    int reserved = FormatBytes.getInt(payload, offset + 20);
    long rowId = Integer.toUnsignedLong(FormatBytes.getInt(payload, offset + 8));
    long space = FormatBytes.getLong(payload, offset + 24);
    int entryBytes = IndexedWalCodec.vacuumEntryBytes(rowBytes);
    return rowId > 0
        && rowId <= IndexedWalCodec.MAX_LOGICAL_ROW_ID
        && (deleted == 0 || deleted == 1)
        && reserved == 0
        && OrderedKey.isFiniteSpace(space)
        && entryBytes > 0
        && payload.limit() - offset >= entryBytes;
  }
}
