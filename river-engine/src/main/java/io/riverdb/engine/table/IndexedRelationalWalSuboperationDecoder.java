package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Decodes one fixed-width grouped relational suboperation. */
final class IndexedRelationalWalSuboperationDecoder {
  private final IndexedRelationalMutationBuffer destination;

  IndexedRelationalWalSuboperationDecoder(IndexedRelationalMutationBuffer target) {
    destination = target;
  }

  StatusCode decode(
      ByteBuffer source, int offset, int itemBytes, int ordinal, int descriptors) {
    if (itemBytes != IndexedRelationalWalCodec.SUBOPERATION_ITEM_BYTES) {
      return StatusCode.CORRUPTION;
    }
    int itemOrdinal = FormatBytes.getInt(source, offset + 8);
    int descriptor = FormatBytes.getInt(source, offset + 12);
    long keyId = FormatBytes.getLong(source, offset + 80);
    if (itemOrdinal != ordinal
        || descriptor < IndexedRelationalMutationBuffer.SCALAR_SUBOPERATION
        || descriptor >= descriptors
        || keyId != (descriptor < 0 ? 0 : destination.keyIdAt(descriptor))) {
      return StatusCode.CORRUPTION;
    }
    return destination.appendSuboperation(
        FormatBytes.getLong(source, offset + 56), descriptor,
        FormatBytes.getInt(source, offset + 16),
        FormatBytes.getInt(source, offset + 20),
        FormatBytes.getInt(source, offset + 24),
        FormatBytes.getInt(source, offset + 28),
        FormatBytes.getInt(source, offset + 32),
        FormatBytes.getInt(source, offset + 36),
        FormatBytes.getInt(source, offset + 40),
        FormatBytes.getInt(source, offset + 44),
        FormatBytes.getLong(source, offset + 64),
        FormatBytes.getLong(source, offset + 72),
        FormatBytes.getLong(source, offset + 88),
        FormatBytes.getLong(source, offset + 96),
        FormatBytes.getInt(source, offset + 48),
        FormatBytes.getInt(source, offset + 52),
        FormatBytes.getLong(source, offset + 104),
        FormatBytes.getLong(source, offset + 112),
        FormatBytes.getInt(source, offset + 120),
        FormatBytes.getInt(source, offset + 124));
  }
}
