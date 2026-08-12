package io.riverdb.base.concurrent;

import io.riverdb.base.error.StatusCode;

/** Database/component contract for fencing new work after an explicit fail-stop transition. */
public interface FatalState {
  StatusCode admissionStatus();

  StatusCode fatalStatus();

  boolean isFenced();

  /**
   * Records a contextual fail-stop cause and fences admission. Calling this method classifies the
   * particular failure as fail-stop; it does not make every occurrence of that status globally
   * fatal. Repeating the winning cause is idempotent. A different later cause returns FENCED.
   */
  StatusCode fence(StatusCode failStopCause);
}
