package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Decodes descriptor items into a pre-reserved relational mutation buffer. */
final class IndexedRelationalWalDescriptorDecoder {
  private final IndexedRelationalMutationBuffer destination;
  private final int[] parts = new int[TupleKeyCodec.MAX_INDEX_KEY_PARTS];

  IndexedRelationalWalDescriptorDecoder(IndexedRelationalMutationBuffer target) {
    destination = target;
  }

  StatusCode decode(ByteBuffer source, int offset, int itemBytes, int ordinal) {
    if (itemBytes < IndexedRelationalWalCodec.DESCRIPTOR_ITEM_BYTES) {
      return StatusCode.CORRUPTION;
    }
    int itemOrdinal = FormatBytes.getInt(source, offset + 8);
    int count = FormatBytes.getInt(source, offset + 12);
    if (itemOrdinal != ordinal || count < 1 || count > parts.length
        || itemBytes != IndexedRelationalWalCodec.DESCRIPTOR_ITEM_BYTES
            + count * Integer.BYTES) {
      return StatusCode.CORRUPTION;
    }
    for (int part = 0; part < count; part++) {
      parts[part] = FormatBytes.getInt(
          source, offset + IndexedRelationalWalCodec.DESCRIPTOR_ITEM_BYTES
              + part * Integer.BYTES);
    }
    return destination.appendDecodedDescriptor(
        FormatBytes.getLong(source, offset + 40),
        FormatBytes.getLong(source, offset + 16),
        FormatBytes.getLong(source, offset + 24),
        FormatBytes.getLong(source, offset + 32),
        parts, 0, count);
  }
}
