package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Publishes an already-forced ordered transaction group under the snapshot barrier. */
public interface TransactionGroupCommitParticipant {
  StatusCode publishForcedGroup();
}
