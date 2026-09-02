package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Validated mutation of the reciprocal leaf link stored outside entry bytes. */
final class TupleBTreePageLinks {
  private TupleBTreePageLinks() { }

  static StatusCode replaceLeft(
      ByteBuffer page, int start, int expectedPageId, int replacementPageId) {
    if (!TupleBTreePageBytes.validPayload(page, start, true)
        || expectedPageId < 0 || replacementPageId <= 0
        || FormatBytes.getLong(page, start) != TupleBTreePageCodec.MAGIC
        || FormatBytes.getInt(page, start + 8) != TupleBTreePageCodec.VERSION
        || FormatBytes.getInt(page, start + 12) != TupleBTreePageCodec.TYPE_LEAF
        || FormatBytes.getInt(page, start + 64) != expectedPageId
        || FormatBytes.getInt(page, start + 68) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putInt(page, start + 64, replacementPageId);
    return StatusCode.OK;
  }
}
