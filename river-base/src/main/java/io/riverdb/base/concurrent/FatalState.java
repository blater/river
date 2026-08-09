package io.riverdb.base.concurrent;

import io.riverdb.base.error.StatusCode;

/** Database/component contract for fencing new work after a fatal transition. */
public interface FatalState {
  StatusCode admissionStatus();

  StatusCode fatalStatus();

  boolean isFenced();

  StatusCode fence(StatusCode fatalStatus);
}
