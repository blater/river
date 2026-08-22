package io.riverdb.format.checkpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class CheckpointManifestCodecTest {
  @Test
  void freezesRootsLongWatermarksAndBoundedDirtyExtents() {
    ByteBuffer bytes = ByteBuffer.allocate(CheckpointManifestCodec.BYTES + 8);
    assertEquals(
        StatusCode.OK,
        CheckpointManifestCodec.begin(
            bytes,
            8,
            1,
            2,
            3,
            4,
            5,
            6,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE - 4,
            Integer.MAX_VALUE - 3,
            Integer.MAX_VALUE - 2,
            Integer.MAX_VALUE - 1,
            2,
            7,
            8,
            9,
            10,
            11));
    assertEquals(
        StatusCode.OK,
        CheckpointManifestCodec.encodeExtent(bytes, 8, 0, 1, 2, 12));
    assertEquals(
        StatusCode.OK,
        CheckpointManifestCodec.encodeExtent(
            bytes, 8, 1, Integer.MAX_VALUE - 2, 2, Long.MAX_VALUE));
    assertEquals(StatusCode.OK, CheckpointManifestCodec.seal(bytes, 8, new CRC32C()));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "334d504b4356495203000000a804000001000000000000000200000000000000"
                + "0300000000000000040000000000000005000000000000000600000000000000"
                + "feffffffffffff7fffffffffffffff7fffffff7ffbffff7ffcffff7ffdffff7f"
                + "feffff7f02000000070000000000000008000000000000000900000000000000"
                + "0a000000000000000b0000000000000000000000000000000000000000000000"
                + "01000000020000000c00000000000000fdffff7f02000000ffffffffffffff7f"),
        Arrays.copyOfRange(bytes.array(), 8, 8 + 192));
    CheckpointManifest manifest = new CheckpointManifest();
    assertEquals(
        StatusCode.OK,
        CheckpointManifestCodec.decode(
            bounded(bytes, 8, CheckpointManifestCodec.BYTES), 0, manifest, new CRC32C()));
    assertEquals(Long.MAX_VALUE - 1, manifest.maximumLogicalRowId());
    assertEquals(Long.MAX_VALUE, manifest.maximumVersionId());
    assertEquals(Integer.MAX_VALUE, manifest.nextPageId());
    CheckpointExtent extent = new CheckpointExtent();
    assertEquals(
        StatusCode.OK,
        CheckpointManifestCodec.decodeExtent(
            bounded(bytes, 8, CheckpointManifestCodec.BYTES), 0, manifest, 1, extent));
    assertEquals(Integer.MAX_VALUE - 2, extent.firstPageId());
    assertEquals(2, extent.pageCount());
    assertEquals(Long.MAX_VALUE, extent.flushGeneration());
  }

  @Test
  void rejectsOldCorruptOverlappingAndUnsealedManifestsWithoutStalePublication() {
    ByteBuffer bytes = validManifest(2);
    CheckpointManifest manifest = new CheckpointManifest();
    CRC32C checksum = new CRC32C();
    assertEquals(StatusCode.OK, CheckpointManifestCodec.decode(bytes, 0, manifest, checksum));
    assertEquals(2, manifest.extentCount());

    FormatBytes.putInt(bytes, 8, 2);
    reseal(bytes, checksum);
    assertEquals(StatusCode.CORRUPTION, CheckpointManifestCodec.decode(bytes, 0, manifest, checksum));
    assertEquals(0, manifest.extentCount());

    bytes = validManifest(2);
    FormatBytes.putInt(bytes, CheckpointManifestCodec.EXTENTS_OFFSET + 16, 2);
    reseal(bytes, checksum);
    assertEquals(StatusCode.CORRUPTION, CheckpointManifestCodec.decode(bytes, 0, manifest, checksum));

    bytes = validManifest(2);
    FormatBytes.putInt(bytes, 88, FormatBytes.getInt(bytes, 84));
    reseal(bytes, checksum);
    assertEquals(StatusCode.CORRUPTION, CheckpointManifestCodec.decode(bytes, 0, manifest, checksum));

    bytes = validManifest(2);
    bytes.put(
        CheckpointManifestCodec.CHECKSUM_OFFSET,
        (byte) (bytes.get(CheckpointManifestCodec.CHECKSUM_OFFSET) ^ 1));
    assertEquals(StatusCode.CORRUPTION, CheckpointManifestCodec.decode(bytes, 0, manifest, checksum));
    assertEquals(
        StatusCode.CORRUPTION,
        CheckpointManifestCodec.decode(
            ByteBuffer.allocate(CheckpointManifestCodec.BYTES - 1), 0, manifest, checksum));
  }

  @Test
  void admitsExactlySixtyFourOrderedExtents() {
    ByteBuffer bytes = beginManifest(CheckpointManifestCodec.MAXIMUM_EXTENTS, 130);
    for (int index = 0; index < CheckpointManifestCodec.MAXIMUM_EXTENTS; index++) {
      assertEquals(
          StatusCode.OK,
          CheckpointManifestCodec.encodeExtent(bytes, 0, index, 1 + index * 2, 2, index + 1));
    }
    assertEquals(StatusCode.OK, CheckpointManifestCodec.seal(bytes, 0, new CRC32C()));
    assertEquals(
        StatusCode.OK,
        CheckpointManifestCodec.decode(bytes, 0, new CheckpointManifest(), new CRC32C()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CheckpointManifestCodec.begin(
            bytes, 0, 1, 2, 3, 4, 5, 6, 7, 8, 130, 1, 2, 3, 4,
            CheckpointManifestCodec.MAXIMUM_EXTENTS + 1, 1, 1, 1, 1, 1));
  }

  private static ByteBuffer validManifest(int extentCount) {
    ByteBuffer bytes = beginManifest(extentCount, 20);
    if (extentCount > 0) {
      assertEquals(StatusCode.OK, CheckpointManifestCodec.encodeExtent(bytes, 0, 0, 1, 2, 1));
    }
    if (extentCount > 1) {
      assertEquals(StatusCode.OK, CheckpointManifestCodec.encodeExtent(bytes, 0, 1, 3, 2, 2));
    }
    assertEquals(StatusCode.OK, CheckpointManifestCodec.seal(bytes, 0, new CRC32C()));
    return bytes;
  }

  private static ByteBuffer beginManifest(int extentCount, int nextPageId) {
    ByteBuffer bytes = ByteBuffer.allocate(CheckpointManifestCodec.BYTES);
    assertEquals(
        StatusCode.OK,
        CheckpointManifestCodec.begin(
            bytes, 0, 1, 2, 3, 4, 5, 6, 7, 8, nextPageId, 1, 2, 3, 4,
            extentCount, 1, 1, 1, 1, 1));
    return bytes;
  }

  private static void reseal(ByteBuffer bytes, CRC32C checksum) {
    FormatBytes.putInt(bytes, CheckpointManifestCodec.CHECKSUM_OFFSET, 0);
    FormatBytes.putInt(bytes, CheckpointManifestCodec.CHECKSUM_OFFSET + 4, 0);
    int value = FormatBytes.checksum(
        bytes, 0, CheckpointManifestCodec.CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(bytes, CheckpointManifestCodec.CHECKSUM_OFFSET, value);
    FormatBytes.putInt(bytes, CheckpointManifestCodec.CHECKSUM_OFFSET + 4, ~value);
  }

  private static ByteBuffer bounded(ByteBuffer source, int offset, int bytes) {
    ByteBuffer result = source.duplicate();
    result.position(offset);
    result.limit(offset + bytes);
    return result.slice();
  }
}
