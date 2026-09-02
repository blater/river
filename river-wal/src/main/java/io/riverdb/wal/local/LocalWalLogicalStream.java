package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Reusable caller-held capability for one non-interleavable logical WAL stream. */
public final class LocalWalLogicalStream {
  private LocalWal owner;
  private long token;
  private long transactionId;
  private int formatId;
  private int formatVersion;
  private boolean active;

  public boolean isActive() {
    return active;
  }

  public StatusCode reset() {
    if (active) return StatusCode.CONFLICT;
    clear();
    return StatusCode.OK;
  }

  long transactionId() {
    return transactionId;
  }

  int formatId() {
    return formatId;
  }

  int formatVersion() {
    return formatVersion;
  }

  StatusCode claim(
      LocalWal wal, long value, long transaction, int format, int version) {
    if (active) return StatusCode.CONFLICT;
    owner = wal;
    token = value;
    transactionId = transaction;
    formatId = format;
    formatVersion = version;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(LocalWal wal, long value) {
    return active && owner == wal && token == value;
  }

  void complete() {
    active = false;
    clear();
  }

  private void clear() {
    owner = null;
    token = 0;
    transactionId = 0;
    formatId = 0;
    formatVersion = 0;
  }
}
