package io.riverdb.wal.local;

import io.riverdb.format.wal.WalCommitGroupCodec;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Owns reusable checksum, footer storage, and digest state for one WAL owner. */
final class LocalWalCommitGroup {
  private final ByteBuffer footer = ByteBuffer.allocate(WalCommitGroupCodec.FOOTER_BYTES);
  private final CRC32C checksum = new CRC32C();
  private long recordBytes;
  private long firstSequence;
  private long lastSequence;
  private int digest;
  private int pendingDigest;

  ByteBuffer footer() { return footer; }
  CRC32C checksum() { return checksum; }
  long recordBytes() { return recordBytes; }
  long firstSequence() { return firstSequence; }
  long lastSequence() { return lastSequence; }
  int digest() { return digest; }
  int pendingDigest() { return pendingDigest; }

  void begin(long sequence) {
    checksum.reset();
    recordBytes = 0;
    firstSequence = sequence;
  }

  void include(ByteBuffer record, int bytes, long sequence) {
    includeRaw(record, bytes);
    recordBytes += bytes;
    lastSequence = sequence;
  }

  void includeRaw(ByteBuffer record, int bytes) {
    int position = record.position();
    int limit = record.limit();
    record.position(0);
    record.limit(bytes);
    checksum.update(record);
    record.position(position);
    record.limit(limit);
  }

  int checksumValue() { return (int) checksum.getValue(); }

  void setPendingDigest(int value) { pendingDigest = value; }
  void setDigest(int value) { digest = value; }
  void commitPendingDigest() { digest = pendingDigest; }
  void resetPending() {
    recordBytes = 0;
    firstSequence = 0;
    lastSequence = 0;
    pendingDigest = 0;
  }
}
