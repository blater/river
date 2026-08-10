package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Immediate engine participant invoked under the commit publication barrier. */
public interface TransactionCommitParticipant {
  StatusCode commit(long transactionId);

  long committedSequence();
}
