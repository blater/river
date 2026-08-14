package io.riverdb.inspect;

/** Recognizes versioned WAL and page file names without allocating substrings. */
final class OfflinePhysicalFileNames {
  static final String WAL_FILE = "river.wal";
  static final String PAGE_FILE = "river.indexed.pages";

  private OfflinePhysicalFileNames() { }

  static boolean matches(String name, String base) {
    if (base.equals(name)) {
      return true;
    }
    String prefix = base + '.';
    if (!name.startsWith(prefix) || name.length() == prefix.length()) {
      return false;
    }
    for (int index = prefix.length(); index < name.length(); index++) {
      char value = name.charAt(index);
      if (value < '0' || value > '9') {
        return PAGE_FILE.equals(base) && name.startsWith(base + ".checkpoint.")
            && decimalSuffix(name, base.length() + ".checkpoint.".length());
      }
    }
    return true;
  }

  static long generation(String name, String base) {
    int start;
    if (base.equals(name)) {
      return 0;
    }
    if (PAGE_FILE.equals(base) && name.startsWith(base + ".checkpoint.")) {
      start = base.length() + ".checkpoint.".length();
    } else {
      start = base.length() + 1;
    }
    long generation = 0;
    for (int index = start; index < name.length(); index++) {
      int digit = name.charAt(index) - '0';
      if (generation > (Long.MAX_VALUE - digit) / 10) {
        return -1;
      }
      generation = generation * 10 + digit;
    }
    return generation;
  }

  private static boolean decimalSuffix(String value, int start) {
    if (start >= value.length()) {
      return false;
    }
    for (int index = start; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < '0' || character > '9') {
        return false;
      }
    }
    return true;
  }
}
