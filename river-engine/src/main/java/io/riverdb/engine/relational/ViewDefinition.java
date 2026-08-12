package io.riverdb.engine.relational;

/** Caller-owned bounded SQL text for one durable logical view definition. */
public final class ViewDefinition implements CharSequence {
  public static final int MAXIMUM_QUERY_LENGTH = 768;

  private final char[] query = new char[MAXIMUM_QUERY_LENGTH];
  private int length;
  private int baseTableId;

  public void reset() {
    length = 0;
    baseTableId = 0;
  }

  void setBaseTableId(int tableId) {
    baseTableId = tableId;
  }

  public int baseTableId() {
    return baseTableId;
  }

  void append(char character) {
    query[length++] = character;
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
