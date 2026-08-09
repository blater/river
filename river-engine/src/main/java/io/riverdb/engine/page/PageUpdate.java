package io.riverdb.engine.page;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Caller capability for one engine-owned mutable page staging buffer. */
public final class PageUpdate {
  private SinglePageStore owner;
  private long token;
  private ByteBuffer writablePayload;
  private int payloadBytes;
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

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    token = 0;
    writablePayload = null;
    payloadBytes = 0;
    return StatusCode.OK;
  }

  StatusCode claim(
      SinglePageStore store,
      long updateToken,
      ByteBuffer payload,
      int bytes) {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = store;
    token = updateToken;
    writablePayload = payload;
    payloadBytes = bytes;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(SinglePageStore store, long updateToken) {
    return active && owner == store && token == updateToken;
  }

  void complete() {
    active = false;
    writablePayload = null;
  }
}
