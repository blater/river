package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Reusable borrowed contiguous tuple input; reset does not change buffer position or limit. */
public final class ByteBufferTupleInput implements TupleInput {
  private ByteBuffer source;
  private int offset;
  private int length;

  public StatusCode reset(ByteBuffer buffer, int start, int bytes) {
    clear();
    if (buffer == null || start < 0 || bytes < 0 || buffer.limit() - start < bytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    source = buffer;
    offset = start;
    length = bytes;
    return StatusCode.OK;
  }

  public void clear() {
    source = null;
    offset = 0;
    length = 0;
  }

  @Override
  public int byteLength() {
    return length;
  }

  @Override
  public int byteAt(int index) {
    return source == null || index < 0 || index >= length
        ? -1 : Byte.toUnsignedInt(source.get(offset + index));
  }
}
