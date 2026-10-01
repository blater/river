package io.riverdb.base.text;

/** Decodes canonical ASCII longs for one record; any invalid field keeps the record invalid. */
public final class CanonicalLongParser {
  private boolean valid = true;

  public boolean isValid() { return valid; }

  public long signed(String text) {
    if (text == null || text.isEmpty()) return invalid();
    boolean negative = text.charAt(0) == '-';
    int start = negative ? 1 : 0;
    if (start == text.length()) return invalid();
    if (text.charAt(start) == '0' && (negative || text.length() - start > 1)) return invalid();
    long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
    long value = 0;
    for (int index = start; index < text.length(); index++) {
      int digit = text.charAt(index) - '0';
      if (digit < 0 || digit > 9 || value < limit / 10) return invalid();
      value *= 10;
      if (value < limit + digit) return invalid();
      value -= digit;
    }
    return negative ? value : -value;
  }

  public long positive(String text) {
    long value = signed(text);
    return value > 0 ? value : invalid();
  }

  private long invalid() {
    valid = false;
    return 0;
  }
}
