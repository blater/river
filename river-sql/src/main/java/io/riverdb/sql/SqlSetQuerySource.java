package io.riverdb.sql;

/** Allocation-free source window preserving parameter ordinals from the original SQL text. */
final class SqlSetQuerySource implements CharSequence, SqlParameterOrdinalSource {
  private CharSequence source;
  private int start;
  private int length;
  private SqlParameterMarkers parameterMarkers;

  void parameterMarkers(SqlParameterMarkers markers) {
    parameterMarkers = markers;
  }

  void set(CharSequence value, int from, int to) {
    source = value;
    start = from;
    length = to - from;
  }

  @Override public int length() { return length; }

  @Override public char charAt(int index) {
    if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
    return source.charAt(start + index);
  }

  @Override public int parameterOrdinal(int offset) {
    if (offset < 0 || offset >= length || charAt(offset) != '?') return -1;
    return SqlParameterOrdinalSource.originalOrdinal(
        source, start + offset, parameterMarkers);
  }

  @Override public CharSequence subSequence(int from, int to) {
    throw new UnsupportedOperationException();
  }
}
