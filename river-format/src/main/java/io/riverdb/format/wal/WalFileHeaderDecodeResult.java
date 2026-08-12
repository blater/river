package io.riverdb.format.wal;

/** Caller-owned output for WAL file-header decoding. */
public final class WalFileHeaderDecodeResult {
  private WalFileHeader header;

  public WalFileHeader header() {
    return header;
  }

  public void set(WalFileHeader value) {
    header = value;
  }

  public void reset() {
    header = null;
  }
}
