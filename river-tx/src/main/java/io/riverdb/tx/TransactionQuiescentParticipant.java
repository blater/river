package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Immediate participant invoked while transaction admission is quiescent. */
@FunctionalInterface
public interface TransactionQuiescentParticipant {
  StatusCode execute();
}
