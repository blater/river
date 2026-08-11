package io.riverdb.base.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PackedTextTest {
  @Test
  void packsBoundedAsciiInLexicalLongOrder() {
    assertTrue(PackedText.isValid(""));
    assertTrue(PackedText.isValid("river12"));
    assertFalse(PackedText.isValid("too-long!"));
    assertFalse(PackedText.isValid(
        new String(new char[] {'c', 'a', 'f', (char) 0xe9})));
    long prefix = PackedText.pack("abc");
    long longer = PackedText.pack("abcd");
    long next = PackedText.pack("abd");
    assertTrue(prefix < longer);
    assertTrue(longer < next);
    assertTrue(PackedText.pack("       ") > -(1L << 46));
    assertTrue(PackedText.pack("~~~~~~~") < (1L << 46) - 2);
    assertEquals(3, PackedText.length(prefix));
    assertEquals('b', PackedText.charAt(prefix, 1));
    char[] output = new char[8];
    assertEquals(4, PackedText.copyTo(longer, output, 2));
    assertEquals('a', output[2]);
    assertEquals('d', output[5]);
    assertEquals(0, PackedText.charAt(prefix, 3));
    long maximum = PackedText.pack("~~~~~~~");
    assertEquals(7, PackedText.copyTo(maximum, output, 0));
    for (int index = 0; index < 7; index++) {
      assertEquals('~', output[index]);
    }
  }
}
