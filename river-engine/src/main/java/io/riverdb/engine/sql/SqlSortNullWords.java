package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Reusable bounded row-major null words shared by in-memory and spilled sort state. */
final class SqlSortNullWords implements SqlNullWords {
  private int maximumRows;
  private final SqlRetainedArrayAllocator allocator;
  private long[] words = new long[0];
  private long[] selectedWords = new long[0];
  private int wordCount;

  SqlSortNullWords(int rows) { this(rows, SqlRetainedArrayAllocator.STANDARD); }

  SqlSortNullWords(int rows, SqlRetainedArrayAllocator retainedAllocator) {
    maximumRows = rows;
    allocator = retainedAllocator;
  }

  void maximumRows(int rows) { maximumRows = rows; }

  long retainedBytes() {
    return (long) (words.length + selectedWords.length) * Long.BYTES;
  }

  long requiredBytes(int columns, int maximumColumns) {
    int requiredWords = (columns + Long.SIZE - 1) >>> 6;
    int maximumWordCount = (maximumColumns + Long.SIZE - 1) >>> 6;
    long requiredLong = (long) maximumRows * requiredWords;
    if (requiredLong > Integer.MAX_VALUE) return Long.MAX_VALUE;
    int capacity = BoundedArrayGrowth.capacity(
        words.length, (int) requiredLong, Integer.MAX_VALUE, maximumRows);
    int selected = BoundedArrayGrowth.capacity(
        selectedWords.length, requiredWords, maximumWordCount, 1);
    return capacity < 0 || selected < 0 ? Long.MAX_VALUE
        : (long) (capacity + selected) * Long.BYTES;
  }

  StatusCode reserve(int columns, int maximumColumns) {
    if (columns <= 0 || columns > maximumColumns || maximumRows <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int requiredWords = (columns + Long.SIZE - 1) >>> 6;
    int maximumWordCount = (maximumColumns + Long.SIZE - 1) >>> 6;
    long requiredLong = (long) maximumRows * requiredWords;
    if (requiredLong > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int capacity = BoundedArrayGrowth.capacity(
        words.length, (int) requiredLong, Integer.MAX_VALUE, maximumRows);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    int selectedCapacity = BoundedArrayGrowth.capacity(
        selectedWords.length, requiredWords, maximumWordCount, 1);
    if (selectedCapacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      long[] nextWords = capacity == words.length
          ? words : allocator.longs(capacity);
      long[] nextSelected = selectedCapacity == selectedWords.length
          ? selectedWords : allocator.longs(selectedCapacity);
      words = nextWords;
      selectedWords = nextSelected;
      wordCount = requiredWords;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode copyFrom(int row, SqlNullWords source) {
    if (!validRow(row) || source == null || source.nullWordCount() != wordCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = row * wordCount;
    for (int word = 0; word < wordCount; word++) words[start + word] = source.nullWord(word);
    return StatusCode.OK;
  }

  void select(int row) {
    if (!validRow(row)) return;
    int start = row * wordCount;
    System.arraycopy(words, start, selectedWords, 0, wordCount);
  }

  boolean nullAt(int row, int column) {
    if (!validRow(row) || column < 0) return false;
    int word = column >>> 6;
    return word < wordCount
        && (words[row * wordCount + word] & 1L << (column & 63)) != 0;
  }

  void swap(int left, int right) {
    int leftStart = left * wordCount;
    int rightStart = right * wordCount;
    for (int word = 0; word < wordCount; word++) {
      long value = words[leftStart + word];
      words[leftStart + word] = words[rightStart + word];
      words[rightStart + word] = value;
    }
  }

  void write(ByteBuffer target, int row) {
    int start = row * wordCount;
    for (int word = 0; word < wordCount; word++) target.putLong(words[start + word]);
  }

  StatusCode read(ByteBuffer source, int row, int columns) {
    if (!validRow(row)) return StatusCode.CORRUPTION;
    int start = row * wordCount;
    for (int word = 0; word < wordCount; word++) words[start + word] = source.getLong();
    int trailing = columns & 63;
    if (trailing == 0) return StatusCode.OK;
    long allowed = (1L << trailing) - 1;
    return (words[start + wordCount - 1] & ~allowed) == 0
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  @Override public int nullWordCount() { return wordCount; }

  @Override public long nullWord(int word) {
    return word >= 0 && word < wordCount ? selectedWords[word] : 0;
  }

  private boolean validRow(int row) { return row >= 0 && row < maximumRows; }
}
