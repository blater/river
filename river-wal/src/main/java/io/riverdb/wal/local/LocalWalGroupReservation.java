package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Caller-held capability for one atomically admitted contiguous WAL record group. */
public final class LocalWalGroupReservation {
  private final ByteBuffer[] payloads = new ByteBuffer[LocalWal.MAX_PENDING_RECORDS];
  private final int[] sizes = new int[LocalWal.MAX_PENDING_RECORDS];
  private LocalWal owner;
  private long token;
  private long streamToken;
  private int count;
  private boolean active;

  public int recordCount() { return count; }
  public boolean isActive() { return active; }
  public ByteBuffer writablePayload(int record) {
    return record >= 0 && record < count ? payloads[record] : null;
  }
  public int payloadBytes(int record) {
    return record >= 0 && record < count ? sizes[record] : -1;
  }
  public StatusCode reset() {
    if (active) return StatusCode.CONFLICT;
    clear();
    return StatusCode.OK;
  }

  StatusCode claim(LocalWal wal, long value, long logicalStreamToken, int records) {
    if (active) return StatusCode.CONFLICT;
    owner = wal;
    token = value;
    streamToken = logicalStreamToken;
    count = records;
    active = true;
    return StatusCode.OK;
  }
  void set(int record, ByteBuffer payload, int bytes) {
    payloads[record] = payload;
    sizes[record] = bytes;
  }
  boolean isOwnedBy(LocalWal wal, long value) {
    return active && owner == wal && token == value;
  }
  boolean belongsToStream(long value) { return streamToken == value; }
  void complete() { active = false; clearPayloads(); }
  private void clear() {
    clearPayloads(); owner = null; token = 0; streamToken = 0; count = 0;
  }
  private void clearPayloads() {
    for (int index = 0; index < count; index++) { payloads[index] = null; sizes[index] = 0; }
  }
}
