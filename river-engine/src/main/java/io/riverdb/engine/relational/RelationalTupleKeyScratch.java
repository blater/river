package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Reusable canonical-key byte scratch, grown only during admission. */
final class RelationalTupleKeyScratch {
  private static final ByteBuffer EMPTY_BYTES = ByteBuffer.allocate(0);
  private static final int INITIAL_BYTES = 64;

  private ByteBuffer bytes = EMPTY_BYTES;

  StatusCode reserve(KeyDescriptor key, boolean physical) {
    int requested = physical
        ? key.shape().maximumPhysicalEncodedBytes() : key.maximumEncodedBytes();
    return reserveBytes(requested);
  }

  ByteBuffer prepare() {
    bytes.clear();
    return bytes;
  }

  ByteBuffer bytes(int length) {
    bytes.position(0).limit(length);
    return bytes;
  }

  void clear(int length) {
    for (int index = 0; index < length; index++) bytes.put(index, (byte) 0);
  }

  private StatusCode reserveBytes(int requested) {
    if (requested <= bytes.capacity()) return StatusCode.OK;
    int maximum = TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES;
    if (requested < 0 || requested > maximum) return StatusCode.RESOURCE_EXHAUSTED;
    int capacity = BoundedArrayGrowth.capacity(
        bytes.capacity(), requested, maximum, INITIAL_BYTES);
    try {
      bytes = ByteBuffer.allocate(capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

}
