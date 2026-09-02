package io.riverdb.format.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class VacuumProgressCodecTest {
  @Test
  void freezesASeparateSystemKeyAndLongResumableWatermark() {
    assertEquals(Long.MAX_VALUE - 3, CatalogKeyspace.SYSTEM_SPACE);
    assertTrue(CatalogKeyspace.VACUUM_PROGRESS_KEY >
        CatalogKeyspace.ALLOCATION_WATERMARK_KEY);
    ByteBuffer bytes = validProgress(VacuumProgressCodec.STATE_COMPLETE);
    assertArrayEquals(
        HexFormat.of().parseHex(
            "3150434156564952"
                + "01000000"
                + "b0000000"
                + "02000000"
                + "00000000"
                + "0100000000000000"
                + "0200000000000000"
                + "ffffffffffffff7f"
                + "ffffffffffffff7f"
                + "02000000"
                + "03000000"
                + "04000000"
                + "05000000"
                + "06000000"
                + "ffffff7f"
                + "0700000000000000"
                + "0800000000000000"
                + "0900000000000000"
                + "0a00000000000000"
                + "0b00000000000000"
                + "0c00000000000000"
                + "0d00000000000000"
                + "ffffffffffffff7f"
                + "0f00000000000000"
                + "0f00000000000000"
                + "0000000000000000"),
        Arrays.copyOf(bytes.array(), VacuumProgressCodec.BYTES - 8));
    assertEquals(-1_402_582_105, FormatBytes.getInt(bytes, VacuumProgressCodec.BYTES - 8));
    assertEquals(~(-1_402_582_105), FormatBytes.getInt(bytes, VacuumProgressCodec.BYTES - 4));
    VacuumProgress result = new VacuumProgress();
    assertEquals(
        StatusCode.OK,
        VacuumProgressCodec.decode(bytes, 0, result, new CRC32C()));
    assertEquals(Long.MAX_VALUE, result.sourceMaximumLogicalRowId());
    assertEquals(Long.MAX_VALUE, result.lastCopiedLogicalRowId());
    assertEquals(Long.MAX_VALUE, result.progressGeneration());
    assertEquals(Integer.MAX_VALUE, result.nextPageId());
    assertEquals(15, result.sourceCommitSequence());
    assertEquals(15, result.appliedCommitSequence());
  }

  @Test
  void rejectsTruncationOldVersionAliasAndIncompleteCompletionWithoutStaleState() {
    ByteBuffer bytes = validProgress(VacuumProgressCodec.STATE_COMPLETE);
    VacuumProgress result = new VacuumProgress();
    CRC32C checksum = new CRC32C();
    assertEquals(StatusCode.OK, VacuumProgressCodec.decode(bytes, 0, result, checksum));

    assertEquals(
        StatusCode.CORRUPTION,
        VacuumProgressCodec.decode(
            ByteBuffer.allocate(VacuumProgressCodec.BYTES - 1), 0, result, checksum));
    assertEquals(0, result.state());

    bytes = validProgress(VacuumProgressCodec.STATE_COMPLETE);
    FormatBytes.putInt(bytes, 8, 0);
    reseal(bytes, checksum);
    assertEquals(StatusCode.CORRUPTION, VacuumProgressCodec.decode(bytes, 0, result, checksum));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        encode(bytes, VacuumProgressCodec.STATE_COMPLETE, Long.MAX_VALUE - 1, 2, 3, 4, 5));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        encode(bytes, VacuumProgressCodec.STATE_BUILDING, 1, 2, 2, 4, 5));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        encode(
            bytes,
            VacuumProgressCodec.STATE_COMPLETE,
            Long.MAX_VALUE,
            2,
            3,
            4,
            5,
            15,
            14));
  }

  private static ByteBuffer validProgress(int state) {
    ByteBuffer bytes = ByteBuffer.allocate(VacuumProgressCodec.BYTES);
    long last = state == VacuumProgressCodec.STATE_COMPLETE ? Long.MAX_VALUE : 17;
    assertEquals(StatusCode.OK, encode(bytes, state, last, 2, 3, 4, 5));
    return bytes;
  }

  private static StatusCode encode(
      ByteBuffer bytes, int state, long lastCopied, int source, int root, int logical, int version) {
    return encode(
        bytes,
        state,
        lastCopied,
        source,
        root,
        logical,
        version,
        15,
        state == VacuumProgressCodec.STATE_COMPLETE ? 15 : 14);
  }

  private static StatusCode encode(
      ByteBuffer bytes,
      int state,
      long lastCopied,
      int source,
      int root,
      int logical,
      int version,
      long sourceCommit,
      long appliedCommit) {
    return VacuumProgressCodec.encode(
        bytes,
        0,
        state,
        1,
        2,
        Long.MAX_VALUE,
        lastCopied,
        source,
        root,
        logical,
        version,
        6,
        Integer.MAX_VALUE,
        7,
        8,
        9,
        10,
        11,
        Math.min(lastCopied, 12),
        13,
        Long.MAX_VALUE,
        sourceCommit,
        appliedCommit,
        new CRC32C());
  }

  private static void reseal(ByteBuffer bytes, CRC32C checksum) {
    int checksumOffset = VacuumProgressCodec.BYTES - 8;
    int value = FormatBytes.checksum(bytes, 0, checksumOffset, checksum);
    FormatBytes.putInt(bytes, checksumOffset, value);
    FormatBytes.putInt(bytes, checksumOffset + 4, ~value);
  }
}
