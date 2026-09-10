package io.riverdb.format.wal;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Checksummed footer binding one complete force batch to its predecessor. */
public final class WalCommitGroupCodec {
  public static final int FOOTER_BYTES = 80;
  public static final int VERSION = 1;

  private static final long MAGIC = 0x524956455247524FL; // RIVERGRO
  private static final int CHECKSUM_OFFSET = 72;

  private WalCommitGroupCodec() {}

  public static StatusCode encodeReserved(
      long groupStart,
      long recordBytes,
      long recordCount,
      long firstJournalSequence,
      long lastJournalSequence,
      int previousDigest,
      int groupChecksum,
      ByteBuffer target,
      CRC32C checksum) {
    if (target == null || checksum == null || target.capacity() < FOOTER_BYTES
        || !validBounds(groupStart, recordBytes, recordCount,
            firstJournalSequence, lastJournalSequence)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.clear();
    target.limit(FOOTER_BYTES);
    FormatBytes.putLong(target, 0, MAGIC);
    FormatBytes.putInt(target, 8, VERSION);
    FormatBytes.putInt(target, 12, FOOTER_BYTES);
    FormatBytes.putLong(target, 16, recordBytes + FOOTER_BYTES);
    FormatBytes.putLong(target, 24, recordBytes);
    FormatBytes.putLong(target, 32, recordCount);
    FormatBytes.putLong(target, 40, firstJournalSequence);
    FormatBytes.putLong(target, 48, lastJournalSequence);
    FormatBytes.putLong(target, 56, groupStart);
    FormatBytes.putInt(target, 64, previousDigest);
    FormatBytes.putInt(target, 68, groupChecksum);
    int value = checksum(target, checksum);
    FormatBytes.putInt(target, CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, CHECKSUM_OFFSET + 4, ~value);
    return StatusCode.OK;
  }

  public static boolean matchesMagic(ByteBuffer source) {
    return source.remaining() >= Long.BYTES
        && FormatBytes.getLong(source, source.position()) == MAGIC;
  }

  /** Advances past a validated footer; failure leaves the source position unchanged. */
  public static StatusCode decodeHeader(
      ByteBuffer source, WalCommitGroupHeader result, CRC32C checksum) {
    if (source == null || result == null || checksum == null
        || source.remaining() < FOOTER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int start = source.position();
    long groupBytes = FormatBytes.getLong(source, start + 16);
    long recordBytes = FormatBytes.getLong(source, start + 24);
    long recordCount = FormatBytes.getLong(source, start + 32);
    long firstSequence = FormatBytes.getLong(source, start + 40);
    long lastSequence = FormatBytes.getLong(source, start + 48);
    long groupStart = FormatBytes.getLong(source, start + 56);
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    if (!matchesMagic(source)
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) != FOOTER_BYTES
        || !validBounds(groupStart, recordBytes, recordCount, firstSequence, lastSequence)
        || groupBytes != recordBytes + FOOTER_BYTES
        || FormatBytes.getInt(source, start + CHECKSUM_OFFSET + 4) != ~stored
        || checksum(source, checksum) != stored) {
      return StatusCode.CORRUPTION;
    }
    result.set(groupBytes, recordBytes, recordCount, firstSequence, lastSequence,
        groupStart, FormatBytes.getInt(source, start + 64),
        FormatBytes.getInt(source, start + 68));
    source.position(start + FOOTER_BYTES);
    return StatusCode.OK;
  }

  /** Digest of an already encoded or validated footer, at its current position. */
  public static int chainDigest(ByteBuffer footer) {
    return FormatBytes.getInt(footer, footer.position() + CHECKSUM_OFFSET);
  }

  private static boolean validBounds(
      long start, long bytes, long count, long first, long last) {
    return start >= WalFileHeaderCodec.HEADER_BYTES
        && bytes >= WalRecordCodec.HEADER_BYTES && bytes <= Long.MAX_VALUE - FOOTER_BYTES
        && start <= Long.MAX_VALUE - bytes - FOOTER_BYTES
        && count > 0 && count <= bytes / WalRecordCodec.HEADER_BYTES
        && first > 0 && last >= first && last - first == count - 1;
  }

  private static int checksum(ByteBuffer footer, CRC32C checksum) {
    int start = footer.position();
    int limit = footer.limit();
    checksum.reset();
    footer.limit(start + CHECKSUM_OFFSET);
    checksum.update(footer);
    footer.limit(limit);
    footer.position(start);
    return (int) checksum.getValue();
  }
}
