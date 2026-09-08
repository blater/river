package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monotonic credential-validity gate shared by the launcher and authenticated
 * session admission.  The foreground lifecycle owner polls the same gate and
 * performs shutdown; this value object owns no watcher or callback thread.
 */
public final class CredentialValidityFence {
  private static final int OPEN = 0;
  private static final int CLOSED = 1;

  private final long notBeforeMillis;
  private final long notAfterMillis;
  private final AtomicInteger state = new AtomicInteger(OPEN);

  private CredentialValidityFence(long notBeforeMillis, long notAfterMillis) {
    this.notBeforeMillis = notBeforeMillis;
    this.notAfterMillis = notAfterMillis;
  }

  public static StatusCode create(
      long notBeforeMillis,
      long notAfterMillis,
      CredentialValidityFenceOpenResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!validBounds(notBeforeMillis, notAfterMillis)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return result.complete(new CredentialValidityFence(notBeforeMillis, notAfterMillis));
  }

  /** Performs one wall-clock read and one monotonic state read. */
  public StatusCode checkNow() {
    long nowMillis = System.currentTimeMillis();
    if (state.get() != OPEN) return StatusCode.ACCESS_DENIED;
    if (nowMillis < notBeforeMillis || nowMillis >= notAfterMillis) {
      state.set(CLOSED);
      return StatusCode.ACCESS_DENIED;
    }
    return StatusCode.OK;
  }

  public boolean isClosed() {
    return state.get() != OPEN;
  }

  public long notBeforeMillis() {
    return notBeforeMillis;
  }

  public long notAfterMillis() {
    return notAfterMillis;
  }

  /** Permanently closes admission. A later wall-clock value cannot reopen it. */
  public StatusCode close() {
    state.set(CLOSED);
    return StatusCode.OK;
  }

  private static boolean validBounds(long notBeforeMillis, long notAfterMillis) {
    return notBeforeMillis >= 0 && notAfterMillis > notBeforeMillis;
  }

}
