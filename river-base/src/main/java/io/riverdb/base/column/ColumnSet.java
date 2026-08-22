package io.riverdb.base.column;

/** Caller-owned fixed column bitset for the 255-column relational boundary. */
public final class ColumnSet {
  public static final int MAXIMUM_COLUMNS = 255;
  public static final int WORD_COUNT = 4;

  private long word0;
  private long word1;
  private long word2;
  private long word3;

  public void reset() {
    word0 = 0;
    word1 = 0;
    word2 = 0;
    word3 = 0;
  }

  public void copyFrom(ColumnSet source) {
    if (source == null) {
      reset();
      return;
    }
    word0 = source.word0;
    word1 = source.word1;
    word2 = source.word2;
    word3 = source.word3;
  }

  public boolean isEmpty() {
    return (word0 | word1 | word2 | word3) == 0;
  }

  public boolean contains(int column) {
    return validColumn(column) && (word(column >>> 6) & bit(column)) != 0;
  }

  public boolean add(int column) {
    if (!validColumn(column)) return false;
    int word = column >>> 6;
    setWord(word, word(word) | bit(column));
    return true;
  }

  public boolean remove(int column) {
    if (!validColumn(column)) return false;
    int word = column >>> 6;
    setWord(word, word(word) & ~bit(column));
    return true;
  }

  public long word(int index) {
    return switch (index) {
      case 0 -> word0;
      case 1 -> word1;
      case 2 -> word2;
      case 3 -> word3;
      default -> 0;
    };
  }

  public boolean setWord(int index, long value) {
    if (index < 0 || index >= WORD_COUNT || index == 3 && value < 0) {
      return false;
    }
    switch (index) {
      case 0 -> word0 = value;
      case 1 -> word1 = value;
      case 2 -> word2 = value;
      default -> word3 = value;
    }
    return true;
  }

  public boolean isValidFor(int columnCount) {
    if (columnCount < 0 || columnCount > MAXIMUM_COLUMNS) return false;
    int fullWords = columnCount >>> 6;
    int remaining = columnCount & 63;
    for (int index = fullWords + (remaining == 0 ? 0 : 1); index < WORD_COUNT; index++) {
      if (word(index) != 0) return false;
    }
    if (remaining == 0) return true;
    long allowed = (1L << remaining) - 1;
    return (word(fullWords) & ~allowed) == 0;
  }

  public static boolean validColumn(int column) {
    return column >= 0 && column < MAXIMUM_COLUMNS;
  }

  private static long bit(int column) {
    return 1L << (column & 63);
  }
}
