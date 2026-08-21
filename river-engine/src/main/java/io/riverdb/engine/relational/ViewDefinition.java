package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

/** Caller-owned bounded SQL text for one durable logical view definition. */
public final class ViewDefinition implements CharSequence {
  public static final int MAXIMUM_QUERY_LENGTH = 768;
  public static final int MAXIMUM_LINEAGE_TABLES = 32;

  private final char[] query = new char[MAXIMUM_QUERY_LENGTH];
  private final int[] tableIds = new int[MAXIMUM_LINEAGE_TABLES];
  private int length;
  private int tableCount;

  public void reset() {
    length = 0;
    for (int index = 0; index < tableCount; index++) tableIds[index] = 0;
    tableCount = 0;
  }

  void setLineage(ByteBuffer source, int offset, int count) {
    tableCount = count;
    for (int index = 0; index < count; index++) {
      tableIds[index] = source.getInt(offset + index * Integer.BYTES);
    }
  }

  public int tableCount() {
    return tableCount;
  }

  public int tableId(int index) {
    return index >= 0 && index < tableCount ? tableIds[index] : 0;
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
