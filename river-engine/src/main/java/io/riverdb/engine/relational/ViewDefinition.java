package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

/** Caller-owned bounded SQL text for one durable logical view definition. */
public final class ViewDefinition implements CharSequence {
  public static final int MAXIMUM_QUERY_LENGTH = 768;

  private final char[] query = new char[MAXIMUM_QUERY_LENGTH];
  private int length;
  private int tableCount;
  private int baseTableId;
  private int joinTableId;

  public void reset() {
    length = 0;
    tableCount = 0;
    baseTableId = 0;
    joinTableId = 0;
  }

  void setLineage(int count, int baseId, int joinId) {
    tableCount = count;
    baseTableId = baseId;
    joinTableId = joinId;
  }

  public int tableCount() {
    return tableCount;
  }

  public int baseTableId() {
    return baseTableId;
  }

  public int joinTableId() {
    return joinTableId;
  }

  void append(char character) {
    query[length++] = character;
  }

  StatusCode setUtf8(ByteBuffer source, int offset, int bytes) {
    reset();
    int decoded = Utf8Text.decode(source, offset, bytes, query, 0);
    if (decoded <= 0 || decoded > query.length) {
      reset();
      return StatusCode.CORRUPTION;
    }
    length = decoded;
    return StatusCode.OK;
  }

  @Override
  public int length() {
    return length;
  }

  @Override
  public char charAt(int index) {
    if (index < 0 || index >= length) {
      throw new IndexOutOfBoundsException(index);
    }
    return query[index];
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    if (start < 0 || end < start || end > length) {
      throw new IndexOutOfBoundsException(start);
    }
    return new String(query, start, end - start);
  }
}
