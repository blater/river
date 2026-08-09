package io.riverdb.base.concurrent;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.atomic.AtomicReference;

/** First-failure-wins implementation of the fatal-state fencing contract. */
public final class FatalStateFence implements FatalState {
  private final AtomicReference<StatusCode> fatalStatus =
      new AtomicReference<>(StatusCode.OK);

  @Override
  public StatusCode admissionStatus() {
    return fatalStatus.get() == StatusCode.OK ? StatusCode.OK : StatusCode.FENCED;
  }

  @Override
  public StatusCode fatalStatus() {
    return fatalStatus.get();
  }

  @Override
  public boolean isFenced() {
    return fatalStatus.get() != StatusCode.OK;
  }

  @Override
  public StatusCode fence(StatusCode candidate) {
    if (!candidate.isFatal()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return fatalStatus.compareAndSet(StatusCode.OK, candidate)
        ? StatusCode.OK
        : StatusCode.FENCED;
  }
}
