package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverFileResult;

final class RiverDaemonStopCompletion {
  private static final long POLL_MILLIS = 50L;

  private RiverDaemonStopCompletion() {
  }

  static StatusCode await(RiverDaemonTarget target, long deadline) {
    while (!expired(deadline)) {
      StatusCode status = target.lockHeld();
      if (status == StatusCode.NOT_OWNER) {
        status = ensureClean(target);
        if (status != StatusCode.TIMEOUT && status != StatusCode.RETRY) return status;
      } else if (status != StatusCode.OK) return status;
      if (!sleepPoll()) return StatusCode.CANCELLED;
    }
    return StatusCode.TIMEOUT;
  }

  static StatusCode ensureClean(RiverDaemonTarget target) {
    StatusCode status = target.revalidate(false);
    if (status != StatusCode.OK && status != StatusCode.NOT_OWNER) return status;
    RiverDaemonStopDirectory.Scan scan = RiverDaemonStopDirectory.scan(target.directory, false);
    if (!scan.status.isOk()) return scan.status;
    if (scan.request != null || scan.acceptedCount != 0) return StatusCode.TIMEOUT;
    RiverFileResult runtime = new RiverFileResult();
    status = target.openRuntime(runtime);
    if (status == StatusCode.OK) {
      runtime.file().close();
      return StatusCode.TIMEOUT;
    }
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }

  static long deadline(long timeoutMillis) {
    long now = System.nanoTime();
    long nanos = timeoutMillis > Long.MAX_VALUE / 1_000_000L
        ? Long.MAX_VALUE : timeoutMillis * 1_000_000L;
    long result = now + nanos;
    return result < now ? Long.MAX_VALUE : result;
  }

  static boolean expired(long deadline) { return System.nanoTime() >= deadline; }

  static boolean sleepPoll() {
    try {
      Thread.sleep(POLL_MILLIS);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
