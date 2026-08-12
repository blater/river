package io.riverdb.base.text;

/** Allocation-free lexical-rank collation for the first bounded ASCII text values. */
public final class PackedText {
  public static final int MAXIMUM_LENGTH = 7;

  private static final int FIRST_CHARACTER = 0x20;
  private static final int CHARACTER_COUNT = 0x7f - FIRST_CHARACTER;
  private static final long VALUE_BIAS = 35_288_320_813_248L;

  private PackedText() {
  }

  public static boolean isValid(CharSequence value) {
    if (value == null || value.length() > MAXIMUM_LENGTH) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x20 || character > 0x7e) {
        return false;
      }
    }
    return true;
  }

  public static long pack(CharSequence value) {
    if (!isValid(value)) {
      return 0;
    }
    long rank = 0;
    for (int index = 0; index < value.length(); index++) {
      rank += 1L
          + (value.charAt(index) - FIRST_CHARACTER)
              * subtreeCount(MAXIMUM_LENGTH - index - 1);
    }
    return rank - VALUE_BIAS;
  }

  public static long pack(char[] value, int offset, int length) {
    if (value == null
        || offset < 0
        || length < 0
        || length > MAXIMUM_LENGTH
        || offset > value.length - length) {
      return 0;
    }
    long rank = 0;
    for (int index = 0; index < length; index++) {
      char character = value[offset + index];
      if (character < FIRST_CHARACTER || character >= 0x7f) {
        return 0;
      }
      rank += 1L
          + (character - FIRST_CHARACTER)
              * subtreeCount(MAXIMUM_LENGTH - index - 1);
    }
    return rank - VALUE_BIAS;
  }

  public static int length(long packed) {
    long rank = packed + VALUE_BIAS;
    int length = 0;
    while (length < MAXIMUM_LENGTH && rank > 0) {
      rank--;
      rank %= subtreeCount(MAXIMUM_LENGTH - length - 1);
      length++;
    }
    return length;
  }

  public static char charAt(long packed, int index) {
    if (index < 0 || index >= MAXIMUM_LENGTH) {
      return 0;
    }
    long rank = packed + VALUE_BIAS;
    for (int position = 0; position <= index; position++) {
      if (rank <= 0) {
        return 0;
      }
      rank--;
      long subtree = subtreeCount(MAXIMUM_LENGTH - position - 1);
      int character = (int) (rank / subtree);
      if (position == index) {
        return (char) (character + FIRST_CHARACTER);
      }
      rank %= subtree;
    }
    return 0;
  }

  public static int copyTo(long packed, char[] destination, int offset) {
    int length = length(packed);
    if (destination == null || offset < 0 || offset > destination.length - length) {
      return -1;
    }
    long rank = packed + VALUE_BIAS;
    for (int index = 0; index < length; index++) {
      rank--;
      long subtree = subtreeCount(MAXIMUM_LENGTH - index - 1);
      destination[offset + index] =
          (char) (rank / subtree + FIRST_CHARACTER);
      rank %= subtree;
    }
    return length;
  }

  private static long subtreeCount(int remaining) {
    return switch (remaining) {
      case 0 -> 1L;
      case 1 -> 96L;
      case 2 -> 9_121L;
      case 3 -> 866_496L;
      case 4 -> 82_317_121L;
      case 5 -> 7_820_126_496L;
      case 6 -> 742_912_017_121L;
      default -> 70_576_641_626_496L;
    };
  }
}
