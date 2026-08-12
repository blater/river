package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Caller-held capability for provider-owned writable WAL payload storage. */
public final class LocalWalReservation {
  private LocalWal owner;
  private long token;
  private ByteBuffer writablePayload;
  private int payloadBytes;
  private long recordStartOffset;
  private long recordEndOffset;
  private boolean active;

  public ByteBuffer writablePayload() {
    return writablePayload;
  }

  public int payloadBytes() {
    return payloadBytes;
  }

  public boolean isActive() {
    return active;
  }

  public long recordStartOffset() {
    return recordStartOffset;
  }

  public long recordEndOffset() {
    return recordEndOffset;
  }

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    token = 0;
    writablePayload = null;
    payloadBytes = 0;
    recordStartOffset = 0;
    recordEndOffset = 0;
    return StatusCode.OK;
  }

  StatusCode claim(
      LocalWal wal,
      long reservationToken,
      ByteBuffer payload,
      int bytes,
      long startOffset,
      long endOffset) {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = wal;
    token = reservationToken;
    writablePayload = payload;
    payloadBytes = bytes;
    recordStartOffset = startOffset;
    recordEndOffset = endOffset;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(LocalWal wal, long reservationToken) {
    return active && owner == wal && token == reservationToken;
  }

  void complete() {
    active = false;
    writablePayload = null;
  }
}
