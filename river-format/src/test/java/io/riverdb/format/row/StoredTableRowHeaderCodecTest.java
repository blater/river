package io.riverdb.format.row;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class StoredTableRowHeaderCodecTest {
  @Test
  void encodesCanonicalLittleEndianAtAbsoluteOffset() {
    byte[] storage = new byte[StoredTableRowHeaderCodec.HEADER_BYTES + 4];
    Arrays.fill(storage, (byte) 0x5a);
    ByteBuffer target = ByteBuffer.wrap(storage).order(ByteOrder.BIG_ENDIAN);
    target.position(1);
    target.limit(storage.length - 1);
    int position = target.position();
    int limit = target.limit();

    assertEquals(
        StatusCode.OK,
        StoredTableRowHeaderCodec.encode(
            target, 2, 0x0102_0304_0506_0708L, 0x1112_1314_1516_1718L));

    assertArrayEquals(
        HexFormat.of().parseHex(
            "574f525453564952010000000000000008070605040302011817161514131211"),
        Arrays.copyOfRange(storage, 2, 2 + StoredTableRowHeaderCodec.HEADER_BYTES));
    assertEquals((byte) 0x5a, storage[1]);
    assertEquals((byte) 0x5a, storage[storage.length - 1]);
    assertEquals(position, target.position());
    assertEquals(limit, target.limit());
  }

  @Test
  void decodesPositiveLongBoundariesIndependentOfBufferOrder() {
    ByteBuffer source = ByteBuffer.allocateDirect(
        StoredTableRowHeaderCodec.HEADER_BYTES + 5).order(ByteOrder.LITTLE_ENDIAN);
    source.position(3);
    int position = source.position();
    int limit = source.limit();
    assertEquals(
        StatusCode.OK,
        StoredTableRowHeaderCodec.encode(source, 3, 1, Long.MAX_VALUE));

    StoredTableRowHeader result = new StoredTableRowHeader();
    assertEquals(
        StatusCode.OK,
        StoredTableRowHeaderCodec.decode(source, 3, Long.MAX_VALUE, result));
    assertEquals(1, result.rowLayoutId());
    assertEquals(Long.MAX_VALUE, result.logicalRowId());
    assertEquals(position, source.position());
    assertEquals(limit, source.limit());
  }

  @Test
  void rejectsBadApiArgumentsAndResetsResult() {
    ByteBuffer bytes = ByteBuffer.allocate(StoredTableRowHeaderCodec.HEADER_BYTES);
    StoredTableRowHeader result = populatedResult();

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.decode(null, 0, 1, result));
    assertReset(result);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.decode(bytes, -1, 1, result));
    assertReset(result);
    bytes.limit(0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.decode(bytes, 1, 1, result));
    assertReset(result);
    bytes.limit(bytes.capacity());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.decode(bytes, 0, 0, result));
    assertReset(result);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.decode(bytes, 0, 1, null));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(null, 0, 1, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(bytes.asReadOnlyBuffer(), 0, 1, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(bytes, -1, 1, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(
            ByteBuffer.allocate(StoredTableRowHeaderCodec.HEADER_BYTES - 1), 0, 1, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(bytes, 1, 1, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(bytes, 0, 0, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(bytes, 0, 1, 0));
  }

  @Test
  void encodeFailureLeavesDestinationUnchanged() {
    byte[] storage = new byte[StoredTableRowHeaderCodec.HEADER_BYTES + 2];
    Arrays.fill(storage, (byte) 0x6d);
    byte[] sentinel = storage.clone();
    ByteBuffer target = ByteBuffer.wrap(storage);

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(target, 1, 0, 9));
    assertArrayEquals(sentinel, storage);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(target, 1, 7, -1));
    assertArrayEquals(sentinel, storage);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        StoredTableRowHeaderCodec.encode(target, 3, 7, 9));
    assertArrayEquals(sentinel, storage);
  }

  @Test
  void publishesUnknownPositiveLayoutForDescriptorDispatch() {
    ByteBuffer bytes = encoded(37, 41);
    StoredTableRowHeader result = new StoredTableRowHeader();

    assertEquals(StatusCode.OK, StoredTableRowHeaderCodec.decode(bytes, 0, 41, result));
    assertEquals(37, result.rowLayoutId());
    assertEquals(41, result.logicalRowId());
  }

  @Test
  void rejectsLogicalIdentityMismatchAndResetsResult() {
    ByteBuffer bytes = encoded(37, 41);
    StoredTableRowHeader result = populatedResult();

    assertEquals(
        StatusCode.CORRUPTION,
        StoredTableRowHeaderCodec.decode(bytes, 0, 43, result));
    assertReset(result);
  }

  @Test
  void rejectsTruncatedAndCorruptDurableHeaders() {
    ByteBuffer valid = encoded(7, 9);
    StoredTableRowHeader result = populatedResult();
    valid.limit(StoredTableRowHeaderCodec.HEADER_BYTES - 1);
    assertEquals(
        StatusCode.CORRUPTION,
        StoredTableRowHeaderCodec.decode(valid, 0, 9, result));
    assertReset(result);

    assertCorruption(0, 0, 7, 9);
    assertCorruption(8, StoredTableRowHeaderCodec.VERSION + 1L, 7, 9);
    assertCorruption(12, 1, 7, 9);
    assertCorruption(16, 0, 7, 9);
    assertCorruption(16, -1, 7, 9);
    assertCorruption(24, 0, 7, 9);
    assertCorruption(24, -1, 7, 9);
    assertCorruption(24, 10, 7, 9);
  }

  private static void assertCorruption(
      int offset, long value, long expectedLayoutId, long expectedRowId) {
    ByteBuffer bytes = encoded(expectedLayoutId, expectedRowId);
    if (offset == 8 || offset == 12) {
      FormatBytes.putInt(bytes, offset, (int) value);
    } else {
      FormatBytes.putLong(bytes, offset, value);
    }
    StoredTableRowHeader result = populatedResult();
    assertEquals(
        StatusCode.CORRUPTION,
        StoredTableRowHeaderCodec.decode(bytes, 0, expectedRowId, result));
    assertReset(result);
  }

  private static ByteBuffer encoded(long rowLayoutId, long logicalRowId) {
    ByteBuffer bytes = ByteBuffer.allocate(StoredTableRowHeaderCodec.HEADER_BYTES);
    assertEquals(
        StatusCode.OK,
        StoredTableRowHeaderCodec.encode(bytes, 0, rowLayoutId, logicalRowId));
    return bytes;
  }

  private static StoredTableRowHeader populatedResult() {
    StoredTableRowHeader result = new StoredTableRowHeader();
    result.set(23, 29);
    return result;
  }

  private static void assertReset(StoredTableRowHeader result) {
    assertEquals(0, result.rowLayoutId());
    assertEquals(0, result.logicalRowId());
  }
}
