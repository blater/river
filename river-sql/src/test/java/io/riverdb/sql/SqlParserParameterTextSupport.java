package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class SqlParserParameterTextSupport {
  static void assertText(
      String expected, SqlCommand command, long handle) {
    ByteBuffer bytes = ByteBuffer.allocate(64);
    int length = command.copyText(handle, bytes);
    assertTrue(length >= 0);
    bytes.flip();
    byte[] encoded = new byte[length];
    bytes.get(encoded);
    assertEquals(expected, new String(encoded, StandardCharsets.UTF_8));
  }

  static void assertText(String expected, CharSequence actual) {
    assertEquals(expected.length(), actual.length());
    for (int index = 0; index < expected.length(); index++) {
      assertEquals(expected.charAt(index), actual.charAt(index));
    }
  }

  static final class TestParameters implements SqlParameterSource {
    final int[] descriptors;
    final long[] values;
    final boolean[] nulls;
    final String[] texts;

    TestParameters(
        int[] valueDescriptors,
        long[] fixedValues,
        boolean[] nullValues,
        String[] textValues) {
      descriptors = valueDescriptors;
      values = fixedValues;
      nulls = nullValues;
      texts = textValues;
    }

    static TestParameters empty() {
      return new TestParameters(new int[0], new long[0], new boolean[0], new String[0]);
    }

    static TestParameters fixed(int descriptor, long value) {
      return new TestParameters(
          new int[] {descriptor},
          new long[] {value},
          new boolean[] {false},
          new String[] {null});
    }

    @Override
    public int count() {
      return descriptors.length;
    }

    @Override
    public boolean isNull(int index) {
      return nulls[index];
    }

    @Override
    public int typeDescriptorAt(int index) {
      return descriptors[index];
    }

    @Override
    public long valueAt(int index) {
      return values[index];
    }

    @Override
    public int copyTextAt(int index, char[] target, int offset) {
      String value = texts[index];
      if (value == null) {
        return -1;
      }
      value.getChars(0, value.length(), target, offset);
      return value.length();
    }
  }
  static void assertName(String expected, SqlIdentifier actual) {
    assertText(expected, actual);
  }
}
