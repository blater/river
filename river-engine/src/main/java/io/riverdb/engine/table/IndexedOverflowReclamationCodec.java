package io.riverdb.engine.table;

import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Exact detached-reference identity carried by one logical WAL mutation. */
final class IndexedOverflowReclamationCodec {
  static final int BYTES = 32;

  private IndexedOverflowReclamationCodec() { }

  static void encode(ByteBuffer target, int pageId, int next, long keyId,
      long generation, long retirement) {
    FormatBytes.putInt(target, 0, pageId);
    FormatBytes.putInt(target, 4, next);
    FormatBytes.putLong(target, 8, keyId);
    FormatBytes.putLong(target, 16, generation);
    FormatBytes.putLong(target, 24, retirement);
  }

  static boolean valid(ByteBuffer source, int offset, int length) {
    return source != null && offset >= 0 && length == BYTES
        && offset <= source.limit() - length
        && pageId(source, offset) >= PageCodec.FIRST_ALLOCATABLE_PAGE_ID
        && (next(source, offset) == 0
            || next(source, offset) >= PageCodec.FIRST_ALLOCATABLE_PAGE_ID)
        && next(source, offset) != pageId(source, offset)
        && CatalogKeyspace.validKeyId(keyId(source, offset))
        && generation(source, offset) > 0 && retirement(source, offset) > 0;
  }

  static int pageId(ByteBuffer source, int offset) { return FormatBytes.getInt(source, offset); }
  static int next(ByteBuffer source, int offset) { return FormatBytes.getInt(source, offset + 4); }
  static long keyId(ByteBuffer source, int offset) { return FormatBytes.getLong(source, offset + 8); }
  static long generation(ByteBuffer source, int offset) {
    return FormatBytes.getLong(source, offset + 16);
  }
  static long retirement(ByteBuffer source, int offset) {
    return FormatBytes.getLong(source, offset + 24);
  }
}
