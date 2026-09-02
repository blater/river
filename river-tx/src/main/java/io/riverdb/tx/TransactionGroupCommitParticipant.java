package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Installs an already-prepared forced group under the snapshot barrier. */
public interface TransactionGroupCommitParticipant {
  StatusCode installPreparedGroup();
}
