package io.riverdb.base.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class Utf8TextArenaTest {
  @Test
  void growsGeometricallyAndReadsUnicodeWithoutExposingStorage() {
    Utf8TextArena arena = new Utf8TextArena();
    assertEquals(0, arena.capacity());
    assertEquals(StatusCode.OK, arena.reserve(2, 32));
    assertEquals(StatusCode.OK, arena.append("A£河🌊", 4));
    assertEquals(10, arena.used());
    assertEquals(0, arena.lastOffset());
    assertEquals(10, arena.lastLength());
    assertEquals(0x41, arena.byteAt(0));

    byte[] encoded = new byte[10];
    assertEquals(StatusCode.OK, arena.copyBytes(0, 10, encoded, 0));
    assertArrayEquals("A£河🌊".getBytes(java.nio.charset.StandardCharsets.UTF_8), encoded);

    char[] decoded = new char[5];
    int decodedLength = arena.copyChars(0, arena.used(), decoded, 0);
    assertEquals(5, decodedLength);
    assertEquals("A£河🌊", new String(decoded, 0, decodedLength));
  }

  @Test
  void validatesSurrogatesAndBoundsBeforeChangingLastAppend() {
    Utf8TextArena arena = new Utf8TextArena();
    assertEquals(StatusCode.OK, arena.reserve(8, 8));
    assertEquals(StatusCode.OK, arena.append("ok", 2));
    int used = arena.used();
    int offset = arena.lastOffset();
    int length = arena.lastLength();

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        arena.append("bad" + Character.highSurrogate(0x10000), 8));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, arena.append("seven!!", 7));
    assertEquals(used, arena.used());
    assertEquals(offset, arena.lastOffset());
    assertEquals(length, arena.lastLength());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, arena.copyBytes(-1, 1, new byte[1], 0));
    assertEquals(-1, arena.copyChars(0, used, new char[1], 0));
    assertEquals(-1, arena.byteAt(used));
  }

  @Test
  void enforcesScalarAndByteLimitsExactly() {
    Utf8TextArena scalarLimited = new Utf8TextArena();
    assertEquals(StatusCode.OK, scalarLimited.reserve(16, 16));
    assertEquals(StatusCode.OK, scalarLimited.append("🌊a", 2));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, scalarLimited.append("🌊a", 1));

    Utf8TextArena byteLimited = new Utf8TextArena();
    assertEquals(StatusCode.OK, byteLimited.reserve(0, 4));
    assertEquals(StatusCode.OK, byteLimited.append("🌊", 1));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, byteLimited.append("a", 1));
    assertEquals(4, byteLimited.used());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, byteLimited.reserve(5, 4));
    assertEquals(4, byteLimited.maximumBytes());
  }

  @Test
  void resetRetainsCapacityAndAllowsReuse() {
    Utf8TextArena arena = new Utf8TextArena();
    assertEquals(StatusCode.OK, arena.reserve(1, 64));
    assertEquals(StatusCode.OK, arena.append("widen", 5));
    int capacity = arena.capacity();
    arena.reset();
    assertEquals(0, arena.used());
    assertEquals(-1, arena.lastOffset());
    assertEquals(0, arena.lastLength());
    assertEquals(capacity, arena.capacity());
    assertEquals(StatusCode.OK, arena.append("reuse", 5));
  }

  @Test
  void appendsCallerOwnedCharacterSlicesWithoutIntermediateObjects() {
    Utf8TextArena arena = new Utf8TextArena();
    assertEquals(StatusCode.OK, arena.reserve(16, 16));
    char[] source = "xA£河🌊y".toCharArray();
    assertEquals(StatusCode.OK, arena.append(source, 0, 0, 0));
    assertEquals(StatusCode.OK, arena.append(source, 1, 5, 4));
    assertEquals(10, arena.lastLength());

    char[] decoded = new char[5];
    assertEquals(5, arena.copyChars(0, arena.used(), decoded, 0));
    assertEquals("A£河🌊", new String(decoded));
    int used = arena.used();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, arena.append(source, -1, 1, 1));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, arena.append(source, 1, 5, 3));
    assertEquals(used, arena.used());
  }

  @Test
  void appendsCanonicalUtf8WithoutChangingSourceState() {
    Utf8TextArena arena = new Utf8TextArena();
    assertEquals(StatusCode.OK, arena.reserve(16, 16));
    ByteBuffer source = ByteBuffer.wrap(new byte[] {9, 'A', (byte) 0xc2, (byte) 0xa3, 8});
    source.position(1).limit(4);
    assertEquals(StatusCode.OK, arena.append(source, 1, 3, 2));
    assertEquals(1, source.position());
    assertEquals(4, source.limit());
    assertEquals(0xa3, arena.byteAt(2));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        arena.append(ByteBuffer.wrap(new byte[] {(byte) 0xc0}), 0, 1, 1));
    assertEquals(3, arena.used());
  }
}
