package io.riverdb.base.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class Utf8TextTest {
  @Test
  void roundTripsCanonicalUtf8WithoutAllocatingStorage() {
    String value = "River £ 河 🌊";
    ByteBuffer encoded = ByteBuffer.allocateDirect(Utf8Text.encodedLength(value));
    int bytes = Utf8Text.encode(value, 11, encoded);
    assertEquals(17, bytes);
    assertEquals(11, Utf8Text.validate(encoded, 0, bytes, 11));

    char[] decoded = new char[32];
    int chars = Utf8Text.decode(encoded, 0, bytes, decoded, 0);
    assertEquals(value, new String(decoded, 0, chars));
  }

  @Test
  void countsScalarsRatherThanUtf16Units() {
    assertEquals(4, Utf8Text.encodedLength("🌊", 1));
    assertEquals(-1, Utf8Text.encodedLength("🌊a", 1));
    assertEquals(-1, Utf8Text.encodedLength(String.valueOf((char) 0xd800), 1));
    char[] valid = {'a', Character.highSurrogate(0x1f30a),
        Character.lowSurrogate(0x1f30a)};
    assertEquals(2, Utf8Text.scalarCount(valid, 0, valid.length));
    char[] invalid = {'a', Character.highSurrogate(0x1f30a)};
    assertEquals(-1, Utf8Text.scalarCount(invalid, 0, invalid.length));
  }

  @Test
  void rejectsMalformedAndNonCanonicalUtf8() {
    ByteBuffer malformed = ByteBuffer.wrap(new byte[] {
        (byte) 0xc0, (byte) 0x80
    });
    assertEquals(-1, Utf8Text.validate(malformed, 0, 2, 1));

    ByteBuffer surrogate = ByteBuffer.wrap(new byte[] {
        (byte) 0xed, (byte) 0xa0, (byte) 0x80
    });
    assertEquals(-1, Utf8Text.validate(surrogate, 0, 3, 1));
  }

  @Test
  void unsignedUtf8OrderMatchesCodePointOrder() {
    ByteBuffer values = ByteBuffer.allocate(16);
    int first = Utf8Text.encode("£", 1, values);
    int second = Utf8Text.encode("河", 1, values);
    assertEquals(-1, Integer.signum(Utf8Text.compare(
        values, 0, first, values, first, second)));
  }
}
