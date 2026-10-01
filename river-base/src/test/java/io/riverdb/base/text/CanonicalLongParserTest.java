package io.riverdb.base.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class CanonicalLongParserTest {
  @Test
  void acceptsCanonicalSignedLongsIncludingBothExtremes() {
    CanonicalLongParser parser = new CanonicalLongParser();
    for (long value : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
      assertEquals(value, parser.signed(Long.toString(value)));
    }
    SplittableRandom random = new SplittableRandom(42);
    for (int index = 0; index < 1_000; index++) {
      long value = random.nextLong();
      assertEquals(value, parser.signed(Long.toString(value)));
    }
    assertTrue(parser.isValid());
  }

  @Test
  void rejectsNoncanonicalSpellingAndOverflowWithoutThrowing() {
    for (String text : new String[] {
        null, "", "-", "+1", "-0", "00", "01", "-01", " 1", "1 ", "1\n",
        "1.0", "١", "9223372036854775808", "-9223372036854775809"}) {
      CanonicalLongParser parser = new CanonicalLongParser();
      parser.signed(text);
      assertFalse(parser.isValid(), text);
      assertEquals(7, parser.signed("7"));
      assertFalse(parser.isValid(), "later valid fields cannot admit an invalid record");
    }
  }

  @Test
  void keepsPositiveAdmissionSeparateFromSignedFields() {
    CanonicalLongParser parser = new CanonicalLongParser();
    assertEquals(Long.MAX_VALUE, parser.positive(Long.toString(Long.MAX_VALUE)));
    assertTrue(parser.isValid());
    assertEquals(0, parser.positive("0"));
    assertFalse(parser.isValid());
    parser = new CanonicalLongParser();
    parser.positive("-1");
    assertFalse(parser.isValid());
  }
}
