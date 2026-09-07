package io.riverdb.platform.riverd;

import io.riverdb.base.error.StatusCode;

/** Exclusive lock capability retained on the verified instance-lock descriptor. */
public interface RiverLock {
  StatusCode close();
}
