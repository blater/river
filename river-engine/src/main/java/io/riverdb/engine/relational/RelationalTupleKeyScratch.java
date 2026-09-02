package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Reusable canonical-key byte and text scratch, grown only during admission. */
final class RelationalTupleKeyScratch {
  private static final ByteBuffer EMPTY_BYTES = ByteBuffer.allocate(0);
  private static final char[] EMPTY_CHARS = new char[0];
  private static final int INITIAL_BYTES = 64;
  private static final int INITIAL_CHARS = 32;

  private final TextSlice text = new TextSlice();
  private ByteBuffer bytes = EMPTY_BYTES;
  private char[] characters = EMPTY_CHARS;

  StatusCode reserve(
      KeyDescriptor key, SqlValueBuffer values, int parts, boolean physical) {
    int requested = physical
        ? key.shape().maximumPhysicalEncodedBytes() : key.maximumEncodedBytes();
    StatusCode status = reserveBytes(requested);
    return status.isOk() ? reserveCharacters(key, values, parts) : status;
  }

  ByteBuffer prepare() {
    bytes.clear();
    return bytes;
  }

  ByteBuffer bytes(int length) {
    bytes.position(0).limit(length);
    return bytes;
  }

  StatusCode copyText(SqlValueBuffer values, int column) {
    int count = values.copyTextChars(column, characters, 0);
    if (count < 0) return StatusCode.INVARIANT_BROKEN;
    text.reset(characters, count);
    return StatusCode.OK;
  }

  CharSequence text() { return text; }

  void clear(int length) {
    for (int index = 0; index < length; index++) bytes.put(index, (byte) 0);
    text.reset(EMPTY_CHARS, 0);
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

  private StatusCode reserveCharacters(
      KeyDescriptor key, SqlValueBuffer values, int parts) {
    int requested = maximumTextBytes(key, values, parts);
    if (requested <= characters.length) return StatusCode.OK;
    if (requested > SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int capacity = BoundedArrayGrowth.capacity(
        characters.length, requested,
        SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES, INITIAL_CHARS);
    try {
      characters = new char[capacity];
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static int maximumTextBytes(
      KeyDescriptor key, SqlValueBuffer values, int parts) {
    int requested = 0;
    for (int part = 0; part < parts; part++) {
      int column = key.columnOrdinalAt(part);
      if (!values.isNull(column)
          && SqlTypeDescriptor.typeId(key.typeDescriptorAt(part))
              == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        requested = Math.max(requested, values.textByteLengthAt(column));
      }
    }
    return requested;
  }

  private static final class TextSlice implements CharSequence {
    private char[] value = EMPTY_CHARS;
    private int length;

    void reset(char[] source, int count) {
      value = source;
      length = count;
    }

    @Override
    public int length() { return length; }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
      return value[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException("tuple encoding does not slice text");
    }
  }
}
