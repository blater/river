package io.riverdb.format.btree;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;

/** Allocation-free identity and descriptor validation for tuple-root records. */
final class TupleIndexRootRecordValidation {
  private TupleIndexRootRecordValidation() { }

  static boolean identity(
      int state, int root, long key, long object,
      long schema, long hash, long owner, long generation, int cleanupCursor) {
    if (!CatalogKeyspace.validKeyId(key) || !CatalogKeyspace.validObjectHead(object)
        || schema <= 0 || hash == 0 || root < 0 || generation <= 0) return false;
    if (state == TupleIndexRootRecordCodec.STATE_ABSENT) {
      return root == 0 && owner == 0 && cleanupCursor == 0;
    }
    if (state == TupleIndexRootRecordCodec.STATE_BUILDING) {
      return owner > 0 && cleanupCursor == 0;
    }
    if (state == TupleIndexRootRecordCodec.STATE_READY) {
      return root > 0 && owner == 0 && cleanupCursor == 0;
    }
    return state == TupleIndexRootRecordCodec.STATE_DROPPING && owner > 0
        && (root > 0 ? cleanupCursor == 0 : cleanupCursor >= 4);
  }

  static boolean descriptors(int[] values, int offset, int count, long expectedHash) {
    if (values == null || offset < 0 || count <= 0
        || count > TupleKeyCodec.MAX_INDEX_KEY_PARTS || offset > values.length - count) {
      return false;
    }
    long hash = mix(0xcbf29ce484222325L, count);
    for (int index = 0; index < count; index++) {
      int descriptor = values[offset + index];
      if (!SqlTypeDescriptor.isValid(descriptor)) return false;
      hash = mix(hash, descriptor);
    }
    return hash == expectedHash;
  }

  static boolean encoded(
      ByteBuffer source, int start, int count, int descriptorOffset, long expectedHash) {
    if (count <= 0 || count > TupleKeyCodec.MAX_INDEX_KEY_PARTS) return false;
    long hash = mix(0xcbf29ce484222325L, count);
    for (int index = 0; index < TupleKeyCodec.MAX_INDEX_KEY_PARTS; index++) {
      int descriptor = FormatBytes.getInt(
          source, start + descriptorOffset + index * Integer.BYTES);
      if (index < count) {
        if (!SqlTypeDescriptor.isValid(descriptor)) return false;
        hash = mix(hash, descriptor);
      } else if (descriptor != 0) return false;
    }
    return hash == expectedHash;
  }

  private static long mix(long hash, int value) {
    for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
      hash ^= value >>> shift & 0xff;
      hash *= 0x100000001b3L;
    }
    return hash;
  }
}
