package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SqlResultTextLanesTest {
  @Test
  void copiesStoredTextBeforeHeapBufferIsReused() {
    SqlResultTextLanes lanes = new SqlResultTextLanes();
    assertEquals(StatusCode.OK, lanes.reserve(2, 32, 16));
    byte[] encoded = "A£河🌊".getBytes(StandardCharsets.UTF_8);
    ByteBuffer bytes = ByteBuffer.allocate(16);
    for (int index = 0; index < encoded.length; index++) bytes.put(2 + index, encoded[index]);
    HeapRowResult row = new HeapRowResult();
    row.set(bytes, 1, 2, encoded.length);
    assertEquals(StatusCode.OK, lanes.setUtf8(0, row, 0, encoded.length));
    assertEquals(StatusCode.OK, lanes.setUtf8(1,
        ByteBuffer.wrap(encoded), 0, encoded.length));
    for (int index = 0; index < encoded.length; index++) bytes.put(2 + index, (byte) 0);
    for (int index = 0; index < 2; index++) {
      char[] copy = new char[8];
      int length = lanes.copy(index, copy, 0);
      assertEquals("A£河🌊", new String(copy, 0, length));
      assertEquals(encoded.length, lanes.byteLength(index));
    }
  }

  @Test
  void trustedPublicationChecksBoundsButDoesNotReadmitText() {
    SqlResultTextLanes lanes = new SqlResultTextLanes();
    assertEquals(StatusCode.OK, lanes.reserve(1, 8, 8));
    ByteBuffer bytes = ByteBuffer.wrap(new byte[] {(byte) 0xc0});
    assertEquals(StatusCode.OK, lanes.setUtf8(0, bytes, 0, 1));
    assertEquals(1, lanes.byteLength(0));
    assertEquals(-1, lanes.length(0));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, lanes.setUtf8(0, bytes, 1, 1));
    assertEquals(1, lanes.byteLength(0));
    HeapRowResult row = new HeapRowResult();
    row.set(bytes, 1, 0, 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, lanes.setUtf8(0, row, 1, 1));
    assertEquals(1, lanes.byteLength(0));
  }
}
